package com.example.globaltranslation.ui.camera

import android.graphics.Bitmap
import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.provider.*
import com.example.globaltranslation.testing.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val blocks = listOf(PhotoTextBlock("one", "NASA 25 N·m", TextBounds(0f, 0f, 100f, 40f)),
        PhotoTextBlock("two", "OK", TextBounds(0f, 50f, 100f, 90f)))
    private class Prefs : TranslationPreferences {
        override val settings = MutableStateFlow(TranslationSettings())
        override suspend fun update(transform: (TranslationSettings) -> TranslationSettings) { settings.value = transform(settings.value) }
    }
    private class Keys(var key: String = "test-only") : ApiKeyRepository {
        override val status = MutableStateFlow(ApiKeyStatus(key.isNotBlank(), 0))
        override suspend fun read() = key
        override suspend fun save(value: String) { key = value; status.value = ApiKeyStatus(true, 1) }
        override suspend fun clear() { key = ""; status.value = ApiKeyStatus(false, 2) }
    }
    private inner class Harness(key: String = "test-only") {
        val prefs = Prefs()
        val keys = Keys(key)
        val scripts = mutableListOf<TextScript>()
        val calls = mutableListOf<Pair<List<PhotoTextBlock>, TranslationOptions>>()
        var recognized = blocks
        var action: suspend (List<PhotoTextBlock>) -> TranslationResult = { TranslationResult(it.associate { b -> b.id to "译文" }) }
        val vm = CameraViewModel(object : PhotoTextRecognizer {
            override suspend fun recognize(image: Any, script: TextScript): List<PhotoTextBlock> { scripts += script; return recognized }
        }, object : PhotoTranslator {
            override suspend fun translate(blocks: List<PhotoTextBlock>, options: TranslationOptions, apiKey: String): TranslationResult {
                calls += blocks to options; return action(blocks)
            }
        }, prefs, keys)
        fun capture() { vm.captured(vm.beginCapture()!!, mock(Bitmap::class.java)) }
    }

    @Test fun captureTranslatesAndChangesWaitForExplicitAction() = runTest {
        val h = Harness(); h.capture()
        assertEquals(2, h.vm.uiState.value.translations.size)
        h.vm.selectTarget("it")
        assertTrue(h.vm.uiState.value.isResultStale)
        assertEquals(1, h.calls.size)
        h.vm.translate()
        assertEquals(1, h.scripts.size)
        assertEquals("it", h.calls.last().second.target.code)
        h.vm.selectScript(TextScript.JAPANESE)
        assertTrue(h.vm.uiState.value.needsRecognition)
        assertEquals(2, h.calls.size)
        h.vm.translate()
        assertEquals(listOf(TextScript.LATIN, TextScript.JAPANESE), h.scripts)
        assertFalse(h.vm.uiState.value.isResultStale)
    }

    @Test fun partialRetryOnlySendsUnfinishedBlocks() = runTest {
        val h = Harness(); h.action = { TranslationResult(mapOf("one" to "第一段"), "限流") }; h.capture()
        assertEquals(1, h.vm.uiState.value.translations.size)
        h.action = { TranslationResult(it.associate { b -> b.id to "第二段" }) }; h.vm.translate()
        assertEquals(listOf("two"), h.calls.last().first.map { it.id })
        assertEquals(mapOf("one" to "第一段", "two" to "第二段"), h.vm.uiState.value.translations)
        assertNull(h.vm.uiState.value.error)
    }

    @Test fun missingKeyAndEmptyOcrNeverCallApi() = runTest {
        val noKey = Harness(""); noKey.capture()
        assertTrue(noKey.calls.isEmpty()); assertTrue(noKey.vm.uiState.value.error!!.contains("Key"))
        val empty = Harness(); empty.recognized = emptyList(); empty.capture()
        assertTrue(empty.calls.isEmpty()); assertTrue(empty.vm.uiState.value.error!!.contains("未识别"))
        assertFalse(empty.vm.uiState.value.isBusy)
    }

    @Test fun cancellationAndDuplicateClicksDoNotPublishLateResults() = runTest {
        val h = Harness()
        val release = CompletableDeferred<Unit>()
        h.action = { withContext(NonCancellable) { release.await() }; TranslationResult(mapOf("one" to "旧译文")) }
        h.capture(); h.vm.translate()
        assertNull(h.vm.beginCapture())
        assertEquals(1, h.calls.size)
        h.vm.resetPhoto(); release.complete(Unit); runCurrent()
        assertNull(h.vm.uiState.value.photo)
        assertTrue(h.vm.uiState.value.translations.isEmpty())
        assertFalse(h.vm.uiState.value.isBusy)
    }

    @Test fun timeoutRetainsOcrForRetry() = runTest {
        val h = Harness(); h.action = { delay(70_000); TranslationResult(emptyMap()) }; h.capture()
        advanceTimeBy(60_001); runCurrent()
        assertTrue(h.vm.uiState.value.error!!.contains("超时"))
        assertEquals(blocks, h.vm.uiState.value.blocks)
        assertFalse(h.vm.uiState.value.isBusy)
    }

    @Test fun deletingSelectedTemplateFallsBackAndOldCaptureIsIgnored() = runTest {
        val h = Harness()
        h.vm.saveTemplate(null, "机械", "Use mechanical terminology")
        val id = h.prefs.settings.value.templates.single().id
        h.vm.selectTemplate(id); h.capture()
        assertEquals("Use mechanical terminology", h.calls.single().second.additionalRequirements)
        h.vm.deleteTemplate(id)
        assertNull(h.prefs.settings.value.selectedTemplateId)
        assertTrue(h.vm.uiState.value.isResultStale)
        h.vm.resetPhoto()
        val token = h.vm.beginCapture()!!; h.vm.cancel()
        h.vm.captured(token, mock(Bitmap::class.java))
        assertNull(h.vm.uiState.value.photo)
    }
}
