package com.example.globaltranslation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.provider.*
import com.example.globaltranslation.ui.camera.*
import com.example.globaltranslation.ui.theme.GlobalTranslationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Prefs : TranslationPreferences {
        override val settings = MutableStateFlow(TranslationSettings())
        override suspend fun update(transform: (TranslationSettings) -> TranslationSettings) { settings.value = transform(settings.value) }
    }
    private class Keys : ApiKeyRepository {
        private var key = ""
        override val status = MutableStateFlow(ApiKeyStatus())
        override suspend fun read() = key
        override suspend fun save(value: String) { key = value; status.value = ApiKeyStatus(true, 1) }
        override suspend fun clear() { key = ""; status.value = ApiKeyStatus(false, 2) }
    }
    @Test fun templatesLanguagesKeyAndPhotoSurviveSettingsNavigation() {
        val prefs = Prefs()
        val keys = Keys()
        var translationCalls = 0
        val vm = CameraViewModel(object : PhotoTextRecognizer {
            override suspend fun recognize(image: Any, script: TextScript) = listOf(PhotoTextBlock("one", "Torque 25 N·m", TextBounds(10f, 10f, 390f, 190f)))
        }, object : PhotoTranslator {
            override suspend fun translate(blocks: List<PhotoTextBlock>, options: TranslationOptions, apiKey: String) = TranslationResult(mapOf("one" to if (options.target.code == "it") "Coppia 25 N·m" else "扭矩25 N·m")).also { translationCalls++ }
        }, prefs, keys)
        compose.setContent { GlobalTranslationTheme { PhotoTranslationApp(vm) } }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithTag("key_input").performScrollTo().performTextInput("test-only")
        compose.onNodeWithTag("save_key").performScrollTo().performClick()
        compose.onNodeWithTag("add_template").performScrollTo().performClick()
        compose.onNodeWithTag("template_name").performTextInput("机械工程")
        compose.onNodeWithTag("template_body").performTextInput("保留单位和型号")
        compose.onNodeWithTag("save_template").performClick()
        compose.onNodeWithTag("prompt_selector").performScrollTo().performClick()
        compose.onNode(hasText("机械工程") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithTag("script_selector").performScrollTo().performClick()
        compose.onAllNodesWithText("日文").onFirst().performClick()
        compose.onNodeWithTag("target_selector").performScrollTo().performClick()
        compose.onNodeWithText("意大利语").performClick()
        compose.onNodeWithContentDescription("返回相机").performClick()
        compose.onNodeWithTag("script_selector").assertDoesNotExist()
        compose.onNodeWithTag("target_selector").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(TextScript.JAPANESE, prefs.settings.value.script)
            assertEquals("it", prefs.settings.value.targetLanguage)
            assertEquals("保留单位和型号", prefs.settings.value.options.additionalRequirements)
            vm.selectScript(TextScript.LATIN)
            vm.captured(vm.beginCapture()!!, Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888))
        }
        compose.onNodeWithTag("photo_overlay").assertExists()
        // The native photo view must not paint its background over the toolbar above it.
        val settingsIcon = compose.onNodeWithContentDescription("设置").captureToImage().asAndroidBitmap()
        val pixels = IntArray(settingsIcon.width * settingsIcon.height)
        settingsIcon.getPixels(pixels, 0, settingsIcon.width, 0, 0, settingsIcon.width, settingsIcon.height)
        assertTrue("Settings icon was covered by photo drawing", pixels.any {
            android.graphics.Color.red(it) > 230 && android.graphics.Color.green(it) > 230 && android.graphics.Color.blue(it) > 230
        })
        val proof = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "translation-ui.png").outputStream().use { proof.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val callsBeforeToggle = translationCalls
        val translatedPixels = compose.onNodeWithTag("photo_overlay").captureToImage().asAndroidBitmap()
        compose.onNodeWithTag("toggle_original").performClick()
        compose.onNodeWithText("查看译文").assertExists()
        compose.onNodeWithTag("photo_overlay").performClick()
        compose.onNodeWithText("完整译文").assertDoesNotExist()
        val originalPixels = compose.onNodeWithTag("photo_overlay").captureToImage().asAndroidBitmap()
        assertFalse(translatedPixels.sameAs(originalPixels))
        compose.onNodeWithTag("toggle_original").performClick()
        compose.onNodeWithText("查看原图").assertExists()
        assertTrue(translatedPixels.sameAs(compose.onNodeWithTag("photo_overlay").captureToImage().asAndroidBitmap()))
        assertEquals(callsBeforeToggle, translationCalls)
        compose.onNodeWithTag("photo_overlay").performClick()
        compose.onNodeWithText("完整译文").assertExists()
        compose.onNodeWithText("Coppia 25 N·m").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.onNode(hasText("删除") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithContentDescription("返回相机").performClick()
        compose.onNodeWithTag("photo_overlay").assertExists()
        compose.onNodeWithTag("prompt_selector").assertDoesNotExist()
        compose.runOnIdle { assertNull(prefs.settings.value.selectedTemplateId) }
        compose.onNodeWithText("尚未应用更改，请点击下方按钮。").assertExists()
        compose.onNodeWithTag("retranslate").performClick()
        compose.runOnIdle { assertFalse(vm.uiState.value.isResultStale) }
        compose.onNodeWithContentDescription("向左旋转照片").performClick()
        compose.runOnIdle {
            assertEquals(200, vm.uiState.value.photo?.width)
            assertEquals(400, vm.uiState.value.photo?.height)
            assertTrue(vm.uiState.value.blocks.isEmpty())
            assertTrue(vm.uiState.value.translations.isEmpty())
            assertTrue(vm.uiState.value.needsRecognition)
            assertFalse(vm.uiState.value.isBusy)
        }
        compose.onNodeWithTag("retranslate").performClick()
        compose.runOnIdle { assertFalse(vm.uiState.value.needsRecognition); vm.resetPhoto() }
    }
}
