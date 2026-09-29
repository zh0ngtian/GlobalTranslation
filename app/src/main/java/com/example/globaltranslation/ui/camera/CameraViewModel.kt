package com.example.globaltranslation.ui.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.provider.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

fun rotatePhotoCounterClockwise(photo: Bitmap): Bitmap = Bitmap.createBitmap(
    photo, 0, 0, photo.width, photo.height, Matrix().apply { postRotate(-90f) }, true
)

enum class ProcessingStage(val label: String) {
    IDLE(""), CAPTURING("正在拍照…"), IMPORTING("正在读取图片…"), RECOGNIZING("正在识别文字…"), TRANSLATING("正在翻译…")
}

data class CameraUiState(
    val settings: TranslationSettings = TranslationSettings(),
    val settingsLoaded: Boolean = false,
    val hasApiKey: Boolean = false,
    val photo: Bitmap? = null,
    val blocks: List<PhotoTextBlock> = emptyList(),
    val ocrScript: TextScript? = null,
    val translations: Map<String, String> = emptyMap(),
    val resultOptions: TranslationOptions? = null,
    val stage: ProcessingStage = ProcessingStage.IDLE,
    val error: String? = null,
    val notice: String? = null
) {
    val isBusy get() = stage != ProcessingStage.IDLE
    val needsRecognition get() = ocrScript != settings.script || blocks.isEmpty()
    val isResultStale get() = photo != null && blocks.isNotEmpty() &&
        (ocrScript != settings.script || (resultOptions != null && resultOptions != settings.options))
}

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val recognizer: PhotoTextRecognizer,
    private val translator: PhotoTranslator,
    private val preferences: TranslationPreferences,
    private val keys: ApiKeyRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(CameraUiState())
    val uiState = mutableState.asStateFlow()
    private var generation = 0L
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            preferences.settings.catch {
                mutableState.update { it.copy(settingsLoaded = true, error = "无法读取设置，请重试。") }
            }.collect { settings -> mutableState.update { it.copy(settings = settings, settingsLoaded = true) } }
        }
        viewModelScope.launch {
            keys.status.collect { status -> mutableState.update { it.copy(hasApiKey = status.isConfigured) } }
        }
    }

    fun selectScript(script: TextScript) = editSettings { it.copy(script = script) }
    fun selectTarget(code: String) = editSettings { it.copy(targetLanguage = TargetLanguages.find(code).code) }
    fun selectTemplate(id: String?) = editSettings { settings ->
        settings.copy(selectedTemplateId = id?.takeIf { candidate -> settings.templates.any { it.id == candidate } })
    }

    fun saveTemplate(id: String?, name: String, body: String) {
        if (name.isBlank() || name.trim().length > 40 || body.length > 8000) {
            showError("模板名称需为 1–40 字，附加要求最多 8000 字。")
            return
        }
        val template = PromptTemplate(id ?: UUID.randomUUID().toString(), name.trim(), body.trim())
        editSettings { settings ->
            settings.copy(templates = settings.templates.filterNot { it.id == template.id } + template)
        }
    }

    fun deleteTemplate(id: String) = editSettings {
        it.copy(templates = it.templates.filterNot { template -> template.id == id },
            selectedTemplateId = it.selectedTemplateId.takeUnless { selected -> selected == id })
    }

    private fun editSettings(transform: (TranslationSettings) -> TranslationSettings) {
        viewModelScope.launch {
            try { preferences.update(transform) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError("设置保存失败，请重试。") }
        }
    }

    fun saveApiKey(value: String) {
        cancel()
        viewModelScope.launch {
            try {
                keys.save(value)
                mutableState.update { it.copy(notice = "API Key 已加密保存在本机。", error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { showError("请填写有效的 API Key，避免空格或换行。") }
            catch (_: Exception) { showError("无法安全保存 API Key，请重试。") }
        }
    }

    fun clearApiKey() {
        cancel()
        viewModelScope.launch {
            try {
                keys.clear()
                mutableState.update { it.copy(notice = "API Key 已清除。", error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError("无法清除 API Key，请重试。") }
        }
    }

    fun beginCapture(): Long? {
        if (uiState.value.isBusy || !uiState.value.settingsLoaded) return null
        cancel()
        mutableState.update { it.copy(stage = ProcessingStage.CAPTURING, error = null, notice = null) }
        return generation
    }

    fun captured(token: Long, photo: Bitmap) {
        if (token != generation || uiState.value.stage != ProcessingStage.CAPTURING) return
        mutableState.update { it.copy(photo = photo, blocks = emptyList(), ocrScript = null,
            translations = emptyMap(), resultOptions = null, stage = ProcessingStage.IDLE) }
        translate()
    }

    fun captureFailed(token: Long) {
        if (token != generation) return
        mutableState.update { it.copy(stage = ProcessingStage.IDLE, error = "拍照失败，请重试。") }
    }

    fun importPhoto(load: suspend () -> Bitmap) {
        if (uiState.value.isBusy || !uiState.value.settingsLoaded) return
        cancel()
        val token = generation
        mutableState.update { it.copy(stage = ProcessingStage.IMPORTING, error = null, notice = null) }
        val importing = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val photo = load()
                ensureActive()
                if (token != generation) return@launch
                mutableState.update { it.copy(photo = photo, blocks = emptyList(), ocrScript = null,
                    translations = emptyMap(), resultOptions = null, stage = ProcessingStage.IDLE) }
                operation = null
                translate()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == generation) showError("无法读取这张图片，请重新选择或使用其他图片。")
            } finally {
                if (token == generation) mutableState.update { it.copy(stage = ProcessingStage.IDLE) }
            }
        }
        operation = importing
        importing.start()
    }

    fun translate() {
        val initial = uiState.value
        val photo = initial.photo ?: return
        if (initial.isBusy) return
        val script = initial.settings.script
        val options = initial.settings.options
        val reuseOcr = initial.ocrScript == script && initial.blocks.isNotEmpty()
        val reuseTranslations = reuseOcr && initial.error != null && initial.resultOptions == options
        cancel()
        val token = generation
        mutableState.update { it.copy(error = null, notice = null,
            stage = if (reuseOcr) ProcessingStage.TRANSLATING else ProcessingStage.RECOGNIZING) }
        operation = viewModelScope.launch {
            try {
                val blocks = if (reuseOcr) initial.blocks else recognizer.recognize(photo, script)
                ensureActive()
                if (token != generation) return@launch
                val retained = if (reuseTranslations) initial.translations else emptyMap()
                mutableState.update { it.copy(blocks = blocks, ocrScript = script,
                    translations = retained, resultOptions = options) }
                if (blocks.isEmpty()) {
                    showError("未识别到文字。请检查文字体系，或对准清晰印刷文字重新拍照。")
                    return@launch
                }
                val key = try { keys.read() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    showError("无法读取本机 API Key，请在设置中重新保存。")
                    return@launch
                }
                if (key.isBlank()) {
                    showError("请先在设置中填写 DeepSeek API Key，然后重新翻译。")
                    return@launch
                }
                mutableState.update { it.copy(stage = ProcessingStage.TRANSLATING) }
                val pending = blocks.filterNot { it.id in retained }
                val result = withTimeout(60_000) { translator.translate(pending, options, key) }
                ensureActive()
                if (token == generation) {
                    val validIds = pending.map { it.id }.toSet()
                    val validResults = result.translations.filter { it.key in validIds && it.value.isNotBlank() }
                    val combined = retained + validResults
                    mutableState.update { it.copy(translations = combined,
                        error = result.error ?: if (combined.size < blocks.size) "部分文字未完成翻译，请重试。" else null) }
                }
            } catch (_: TimeoutCancellationException) {
                if (token == generation) showError("翻译请求超时，请检查网络后重试。")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == generation) showError("处理失败，请检查文字体系后重试。")
            } finally {
                if (token == generation) mutableState.update { it.copy(stage = ProcessingStage.IDLE) }
            }
        }
    }

    fun cancel() {
        generation++
        operation?.cancel()
        operation = null
        mutableState.update { it.copy(stage = ProcessingStage.IDLE) }
    }

    fun rotatePhoto() {
        val photo = uiState.value.photo ?: return
        cancel()
        val rotated = rotatePhotoCounterClockwise(photo)
        mutableState.update { it.copy(photo = rotated, blocks = emptyList(), ocrScript = null,
            translations = emptyMap(), resultOptions = null, error = null,
            notice = "照片已向左旋转，请重新识别并翻译。") }
    }

    fun resetPhoto() {
        cancel()
        mutableState.update { it.copy(photo = null, blocks = emptyList(), ocrScript = null,
            translations = emptyMap(), resultOptions = null, error = null, notice = null) }
    }

    fun showError(message: String) { mutableState.update { it.copy(error = message) } }
    fun clearMessage() { mutableState.update { it.copy(error = null, notice = null) } }
}
