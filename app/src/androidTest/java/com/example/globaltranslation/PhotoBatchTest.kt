package com.example.globaltranslation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.provider.*
import com.example.globaltranslation.data.provider.*
import com.example.globaltranslation.ui.camera.*
import com.example.globaltranslation.ui.theme.GlobalTranslationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Private, opt-in fixtures. Always export evidence, including failed layouts, before assertions. */
@RunWith(AndroidJUnit4::class)
class PhotoBatchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun suppliedPhotosRenderEveryTranslation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.noBackupFilesDir, "acceptance-batch")
        val manifest = File(input, "manifest.json")
        assumeTrue("No private batch supplied", manifest.isFile)
        val cases = JSONArray(manifest.readText())
        val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "api"
        val credential = File(context.noBackupFilesDir, "acceptance-api-key")
        assumeTrue("No test credential supplied", phase != "api" || credential.isFile)
        val key = if (phase == "api") credential.readText().trim() else "replay"
        credential.delete()
        val output = File(context.cacheDir, "photo-batch").apply { mkdirs() }
        val recognizer = MlKitPhotoRecognizer()
        val actual = DeepSeekTranslator(OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
        val active = mutableStateOf<CameraViewModel?>(null)
        compose.setContent { GlobalTranslationTheme { active.value?.let { PhotoTranslationApp(it) } } }
        val failures = mutableListOf<String>()
        for (index in 0 until cases.length()) {
            val config = cases.getJSONObject(index)
            val id = config.getString("id")
            val source = requireNotNull(BitmapFactory.decodeFile(File(input, config.getString("file")).path))
            val rotation = config.optInt("rotation", 0)
            val oriented = if (rotation == 0) source else if (rotation == 270) rotatePhotoCounterClockwise(source)
                else Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            val scale = config.optInt("scale", 1)
            val photo = if (scale == 1) oriented else Bitmap.createScaledBitmap(oriented, oriented.width * scale, oriented.height * scale, true)
            val script = TextScript.valueOf(config.getString("script"))
            val started = System.currentTimeMillis()
            if (phase == "ocr") {
                val blocks = runBlocking { recognizer.recognize(photo, script) }
                File(output, "$id.json").writeText(JSONObject().put("id", id).put("rotation", rotation).put("script", script.name)
                    .put("ocrMillis", System.currentTimeMillis() - started).put("width", photo.width).put("height", photo.height)
                    .put("blocks", JSONArray().apply { blocks.forEach { put(entry(it)) } }).toString(2))
                continue
            }
            val preferences = object : TranslationPreferences {
                override val settings = MutableStateFlow(TranslationSettings(script = script,
                    templates = listOf(PromptTemplate("art", "测试要求", config.optString("requirements", "使用佛教美术术语"))), selectedTemplateId = "art"))
                override suspend fun update(transform: (TranslationSettings) -> TranslationSettings) { settings.value = transform(settings.value) }
            }
            val keys = object : ApiKeyRepository {
                override val status = MutableStateFlow(ApiKeyStatus(true, 0))
                override suspend fun read() = key
                override suspend fun save(value: String) = error("Unused")
                override suspend fun clear() = error("Unused")
            }
            val translator = if (phase != "replay") actual else object : PhotoTranslator {
                override suspend fun translate(blocks: List<PhotoTextBlock>, options: TranslationOptions, apiKey: String): TranslationResult {
                    val cached = JSONObject(File(input, "$id-result.json").readText()).getJSONArray("blocks")
                    val entries = (0 until cached.length()).map { cached.getJSONObject(it) }.associateBy { it.getString("id") }
                    require(blocks.all { entries[it.id]?.getString("source") == it.text }) { "OCR changed; cached translations no longer match" }
                    return TranslationResult(blocks.associate { it.id to entries.getValue(it.id).getString("translation") })
                }
            }
            lateinit var vm: CameraViewModel
            compose.runOnIdle {
                vm = CameraViewModel(recognizer, translator, preferences, keys)
                active.value = vm
            }
            compose.waitUntil(5000) { vm.uiState.value.settingsLoaded }
            compose.runOnIdle { vm.captured(requireNotNull(vm.beginCapture()), photo) }
            compose.waitUntil(70_000) { !vm.uiState.value.isBusy }
            val state = vm.uiState.value
            val result = JSONObject().put("id", id).put("rotation", rotation).put("script", script.name)
                .put("width", photo.width).put("height", photo.height).put("elapsedMs", System.currentTimeMillis() - started)
                .put("error", state.error ?: JSONObject.NULL)
            val entries = JSONArray()
            compose.runOnIdle {
                val view = PhotoOverlayView(context)
                view.show(photo, state.blocks, state.translations) {}
                view.layout(0, 0, photo.width, photo.height)
                val bitmap = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(output, "$id-overlay.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                state.blocks.forEach { block ->
                    val placement = view.placements.firstOrNull { it.blockId == block.id }
                    entries.put(entry(block).put("translation", state.translations[block.id])
                        .put("abbreviated", placement?.abbreviated).put("fontPx", placement?.fontSizePx)
                        .put("renderedCharacters", placement?.renderedCharacters)
                        .put("renderedRotation", placement?.rotationDegrees)
                        .put("placement", placement?.bounds?.let { bounds(it) }))
                }
                if (view.placements.size != state.blocks.size || view.placements.any { it.abbreviated || it.renderedCharacters != state.translations[it.blockId]?.length }) failures += "$id: missing or abbreviated overlay"
                if (view.placements.any { p -> kotlin.math.abs(p.rotationDegrees - state.blocks.first { it.id == p.blockId }.rotationDegrees) > .01f })
                    failures += "$id: incorrect translation direction"
                for ((i, a) in view.placements.withIndex()) for (b in view.placements.drop(i + 1)) {
                    if (a.bounds.left < b.bounds.right && b.bounds.left < a.bounds.right && a.bounds.top < b.bounds.bottom && b.bounds.top < a.bounds.bottom)
                        failures += "$id: overlapping ${a.blockId}/${b.blockId}"
                }
                bitmap.recycle()
            }
            compose.runOnIdle {
                fun find(view: View): PhotoOverlayView? {
                    if (view is PhotoOverlayView) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                val overlay = requireNotNull(find(compose.activity.window.decorView))
                result.put("uiWidth", overlay.width).put("uiHeight", overlay.height)
                    .put("uiCompleteBlocks", overlay.placements.count { !it.abbreviated && it.renderedCharacters == state.translations[it.blockId]?.length })
                if (overlay.placements.size != state.blocks.size || overlay.placements.any { it.abbreviated || it.renderedCharacters != state.translations[it.blockId]?.length })
                    failures += "$id: App viewport did not render all characters"
            }
            compose.onNodeWithTag("toggle_original").captureToImage().asAndroidBitmap().let { button ->
                var white = 0
                for (y in 0 until button.height) for (x in 0 until button.width) {
                    val pixel = button.getPixel(x, y)
                    if (android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) > 220 && android.graphics.Color.blue(pixel) > 220) white++
                }
                if (white < 20) failures += "$id: original toggle was covered by photo"
            }
            result.put("blocks", entries)
            File(output, "$id-result.json").writeText(result.toString(2))
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(output, "$id-ui.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            if (state.error != null || state.blocks.isEmpty() || state.translations.size != state.blocks.size) failures += "$id: pipeline incomplete"
            compose.runOnIdle { vm.resetPhoto(); active.value = null }
        }
        File(output, "failures.json").writeText(JSONArray(failures).toString(2))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun bounds(value: TextBounds) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
    private fun entry(block: PhotoTextBlock) = JSONObject().put("id", block.id).put("source", block.text).put("bounds", bounds(block.bounds))
        .put("rotationDegrees", block.rotationDegrees)
        .put("cornerPoints", JSONArray().apply { block.cornerPoints.forEach { put(JSONArray(listOf(it.x, it.y))) } })
}
