package com.example.globaltranslation

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.provider.*
import com.example.globaltranslation.data.provider.MlKitPhotoRecognizer
import com.example.globaltranslation.ui.camera.*
import com.example.globaltranslation.ui.theme.GlobalTranslationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoImportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pickerCancellationAndSelectedContentUriUseProductionOcr() {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "GlobalTranslation-gallery-test.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GlobalTranslationTest")
        }))
        try {
            val bitmap = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("Buddha statue", 60f, 180f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f })
            }
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            var selected: Uri? = null
            var launches = 0
            val owner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                        assertTrue(contract is ActivityResultContracts.PickVisualMedia)
                        assertEquals(ActivityResultContracts.PickVisualMedia.ImageOnly, (input as PickVisualMediaRequest).mediaType)
                        launches++
                        dispatchResult(requestCode, if (selected == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                            Intent().setData(selected))
                    }
                }
            }
            val prefs = object : TranslationPreferences {
                override val settings = MutableStateFlow(TranslationSettings(templates = listOf(PromptTemplate("art", "美术", "使用佛教美术术语")), selectedTemplateId = "art"))
                override suspend fun update(transform: (TranslationSettings) -> TranslationSettings) { settings.value = transform(settings.value) }
            }
            val keys = object : ApiKeyRepository {
                override val status = MutableStateFlow(ApiKeyStatus(true, 0))
                override suspend fun read() = "test-only"
                override suspend fun save(value: String) = Unit
                override suspend fun clear() = Unit
            }
            var calls = 0
            lateinit var vm: CameraViewModel
            compose.runOnUiThread {
                vm = CameraViewModel(MlKitPhotoRecognizer(), object : PhotoTranslator {
                    override suspend fun translate(blocks: List<PhotoTextBlock>, options: TranslationOptions, apiKey: String): TranslationResult {
                        assertTrue(blocks.any { it.text.contains("Buddha statue") })
                        assertEquals("zh-Hans", options.target.code)
                        assertEquals("使用佛教美术术语", options.additionalRequirements)
                        calls++
                        return TranslationResult(blocks.associate { it.id to "佛像" })
                    }
                }, prefs, keys)
            }
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                GlobalTranslationTheme { PhotoTranslationApp(vm) }
            } }
            compose.onNodeWithTag("choose_photo").assertIsEnabled().performClick()
            compose.runOnIdle { assertNull(vm.uiState.value.photo); assertEquals(0, calls); selected = uri }
            compose.onNodeWithTag("choose_photo").performClick()
            compose.waitUntil(15_000) { vm.uiState.value.photo != null && !vm.uiState.value.isBusy }
            compose.onNodeWithTag("photo_overlay").assertExists()
            compose.runOnIdle { assertEquals(1, calls); assertNull(vm.uiState.value.error); selected = null }
            val previous = vm.uiState.value.photo
            compose.onNodeWithTag("choose_photo").performClick()
            compose.runOnIdle { assertSame(previous, vm.uiState.value.photo); assertEquals(1, calls); selected = uri }
            compose.onNodeWithTag("choose_photo").performClick()
            compose.waitUntil(15_000) { calls == 2 && !vm.uiState.value.isBusy }
            compose.runOnIdle { assertNotSame(previous, vm.uiState.value.photo); assertEquals(4, launches) }
            compose.onRoot().captureToImage().asAndroidBitmap().let { proof ->
                File(context.cacheDir, "gallery-ui.png").outputStream().use { proof.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.runOnIdle { vm.resetPhoto() }
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun decoderAppliesExifAndLimitsLargeImagesWithReadablePixels() = runBlocking {
        val file = File(context.cacheDir, "gallery-orientation-test.jpg")
        try {
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.BLUE)
                drawRect(0f, 0f, 60f, 80f, Paint().apply { color = Color.RED })
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
            }
            val oriented = loadSelectedPhoto(context.contentResolver, Uri.fromFile(file))
            assertEquals(80, oriented.width); assertEquals(120, oriented.height)
            assertTrue(Color.red(oriented.getPixel(40, 20)) > 220)
            assertTrue(Color.blue(oriented.getPixel(40, 100)) > 220)
            oriented.recycle()
            val large = Bitmap.createBitmap(5000, 100, Bitmap.Config.ARGB_8888)
            file.outputStream().use { large.compress(Bitmap.CompressFormat.PNG, 100, it) }; large.recycle()
            val decoded = loadSelectedPhoto(context.contentResolver, Uri.fromFile(file))
            assertEquals(4096, decoded.width); assertEquals(82, decoded.height)
            assertNotEquals(Bitmap.Config.HARDWARE, decoded.config); decoded.recycle()
            file.writeText("not an image")
            try { loadSelectedPhoto(context.contentResolver, Uri.fromFile(file)); fail("Invalid image accepted") }
            catch (_: java.io.IOException) { /* Corrupt images must fail before OCR. */ }
        } finally { file.delete() }
    }
}
