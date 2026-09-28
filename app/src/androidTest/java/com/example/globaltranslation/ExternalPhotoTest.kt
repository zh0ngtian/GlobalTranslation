package com.example.globaltranslation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.provider.*
import com.example.globaltranslation.data.provider.*
import com.example.globaltranslation.ui.camera.*
import com.example.globaltranslation.ui.theme.GlobalTranslationTheme
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Opt-in test: inject a photo and credential into private storage, never into the APK/repository. */
@RunWith(AndroidJUnit4::class)
class ExternalPhotoTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun suppliedPhotoThroughProductionPipeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.noBackupFilesDir, "acceptance-photo.jpg")
        val credential = File(context.noBackupFilesDir, "acceptance-api-key")
        val replay = InstrumentationRegistry.getArguments().getString("replay") == "true"
        assumeTrue("No external photo supplied", input.isFile && (replay || credential.isFile))
        val key = if (replay) "cached-results" else credential.readText().trim()
        credential.delete()
        val photo = requireNotNull(BitmapFactory.decodeFile(input.path))
        input.delete()
        val output = File(context.cacheDir, "external-photo").apply { mkdirs() }
        val preferences = object : TranslationPreferences {
            override val settings = MutableStateFlow(TranslationSettings(templates = listOf(PromptTemplate("buddhism", "佛教与艺术史", "使用佛教与艺术史通行术语，保留编号、人名和不确定标记；不增补原文内容。"))))
            override suspend fun update(transform: (TranslationSettings) -> TranslationSettings) { settings.value = transform(settings.value) }
        }
        val keys = object : ApiKeyRepository {
            override val status = MutableStateFlow(ApiKeyStatus(true, 0))
            override suspend fun read() = key
            override suspend fun save(value: String) = error("Not used")
            override suspend fun clear() = error("Not used")
        }
        var ocrCount = 0
        var ocrMillis = 0L
        val ocr = MlKitPhotoRecognizer()
        val recognizer = object : PhotoTextRecognizer {
            override suspend fun recognize(image: Any, script: TextScript): List<PhotoTextBlock> {
                val started = System.currentTimeMillis()
                return ocr.recognize(image, script).also { ocrCount++; ocrMillis = System.currentTimeMillis() - started }
            }
        }
        val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val translator: PhotoTranslator = if (!replay) DeepSeekTranslator(client) else object : PhotoTranslator {
            override suspend fun translate(blocks: List<PhotoTextBlock>, options: TranslationOptions, apiKey: String): TranslationResult {
                val cached = JSONObject(File(output, "result.json").readText()).getJSONArray("results")
                    .getJSONObject(if (options.additionalRequirements.isBlank()) 0 else 1).getJSONArray("blocks")
                return TranslationResult((0 until cached.length()).associate { i ->
                    val item = cached.getJSONObject(i); item.getString("id") to item.getString("translation")
                })
            }
        }
        lateinit var vm: CameraViewModel
        compose.runOnUiThread {
            vm = CameraViewModel(recognizer, translator, preferences, keys)
            vm.captured(requireNotNull(vm.beginCapture()), photo)
        }
        compose.setContent { GlobalTranslationTheme { PhotoTranslationApp(vm) } }
        val results = JSONArray()
        for (mode in listOf("base", "buddhism")) {
            val started = System.currentTimeMillis()
            if (mode == "buddhism") compose.runOnIdle { vm.selectTemplate("buddhism") }
            if (mode == "buddhism") {
                compose.waitUntil(5000) { vm.uiState.value.settings.selectedTemplateId == "buddhism" }
                compose.runOnIdle { vm.translate() }
            }
            compose.waitUntil(70_000) { !vm.uiState.value.isBusy }
            val state = vm.uiState.value
            assertNull(state.error, state.error)
            assertTrue(state.blocks.isNotEmpty())
            assertEquals(state.blocks.size, state.translations.size)
            assertEquals(1, ocrCount)
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(output, "$mode-ui.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val entries = JSONArray()
            compose.runOnUiThread {
                val view = PhotoOverlayView(context)
                view.show(photo, state.blocks, state.translations) {}
                view.layout(0, 0, 984, 1250)
                val bitmap = Bitmap.createBitmap(984, 1250, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                assertEquals(state.blocks.size, view.placements.size)
                assertTrue("Short rows must remain complete at default zoom", view.placements.filter { placement ->
                    state.blocks.first { it.id == placement.blockId }.text.matches(Regex("^\\d+\\..*", RegexOption.DOT_MATCHES_ALL))
                }.let { rows -> rows.size == 21 && rows.none { it.abbreviated } })
                assertTrue("Photo should display all supplied translations", view.placements.none { it.abbreviated })
                for ((i, a) in view.placements.withIndex()) for (b in view.placements.drop(i + 1)) {
                    assertFalse("Overlapping translations: ${a.blockId}/${b.blockId}",
                        a.bounds.left < b.bounds.right && b.bounds.left < a.bounds.right &&
                        a.bounds.top < b.bounds.bottom && b.bounds.top < a.bounds.bottom)
                }
                state.blocks.forEach { block ->
                    val placement = view.placements.firstOrNull { it.blockId == block.id }
                    entries.put(JSONObject().put("id", block.id).put("source", block.text)
                        .put("translation", state.translations[block.id])
                        .put("bounds", JSONArray(listOf(block.bounds.left, block.bounds.top, block.bounds.right, block.bounds.bottom)))
                        .put("abbreviated", placement?.abbreviated).put("fontPx", placement?.fontSizePx)
                        .put("placement", placement?.bounds?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) }))
                }
                File(output, "$mode-overlay.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            results.put(JSONObject().put("mode", mode).put("elapsedMs", System.currentTimeMillis() - started).put("blocks", entries))
        }
        File(output, if (replay) "layout-result.json" else "result.json").writeText(JSONObject().put("width", photo.width).put("height", photo.height)
            .put("ocrMillis", ocrMillis).put("ocrRuns", ocrCount).put("results", results).toString(2))
        val text = vm.uiState.value.blocks.joinToString("\n") { it.text }
        assertTrue("Title missing", text.contains("Bouddha"))
        assertTrue("Last numbered entry missing", text.contains("21."))
        fun findOverlay(view: View): PhotoOverlayView? {
            if (view is PhotoOverlayView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findOverlay(view.getChildAt(i))?.let { return it }
            return null
        }
        lateinit var overlay: PhotoOverlayView
        compose.runOnIdle {
            overlay = requireNotNull(findOverlay(compose.activity.window.decorView))
            val block = vm.uiState.value.blocks.first { it.text.startsWith("1.") }
            assertTrue("Actual App viewport must show complete translations", overlay.placements.none { it.abbreviated })
            val area = overlay.placements.first { it.blockId == block.id }.bounds
            val event = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, (area.left + area.right) / 2, (area.top + area.bottom) / 2, 0)
            overlay.onTouchEvent(event); event.recycle()
        }
        compose.onNodeWithText("完整译文").assertExists()
        compose.onNodeWithText("1. 弥勒的登位").assertExists()
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            File(output, "buddhism-detail.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle {
            val width = overlay.width.toFloat()
            val y = overlay.height * .45f
            repeat(1) { attempt ->
                val down = android.os.SystemClock.uptimeMillis() + attempt * 200
                fun touch(action: Int, count: Int, span: Float, elapsed: Long) {
                    val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                    val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                        x = width / 2 + if (i == 0) -span / 2 else span / 2
                        this.y = y; pressure = 1f; size = 1f
                    } }
                    val event = MotionEvent.obtain(down, down + elapsed, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    overlay.onTouchEvent(event); event.recycle()
                }
                touch(MotionEvent.ACTION_DOWN, 1, width * .2f, 0)
                touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, width * .2f, 16)
                touch(MotionEvent.ACTION_MOVE, 2, width * .3f, 32)
                touch(MotionEvent.ACTION_MOVE, 2, width * .6f, 48)
                touch(MotionEvent.ACTION_MOVE, 2, width * .95f, 64)
                touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, width * .95f, 80)
                touch(MotionEvent.ACTION_UP, 1, width * .95f, 96)
            }
        }
        compose.runOnIdle {
            val down = android.os.SystemClock.uptimeMillis()
            for ((action, x, elapsed) in listOf(Triple(MotionEvent.ACTION_DOWN, overlay.width * .1f, 0L),
                Triple(MotionEvent.ACTION_MOVE, overlay.width * .9f, 200L), Triple(MotionEvent.ACTION_UP, overlay.width * .9f, 220L))) {
                val event = MotionEvent.obtain(down, down + elapsed, action, x, overlay.height * .5f, 0)
                overlay.onTouchEvent(event); event.recycle()
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(output, "buddhism-zoom.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.runOnIdle {
            assertTrue("Zoom did not reveal readable translations", overlay.placements.any { !it.abbreviated })
            vm.resetPhoto()
        }
    }
}
