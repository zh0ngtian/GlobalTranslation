package com.example.globaltranslation

import android.Manifest
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import android.graphics.Bitmap
import java.io.File
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real CameraX capture lifecycle, independent of translation credentials. */
@RunWith(AndroidJUnit4::class)
class CameraCaptureTest {
    @get:Rule(order = 0) val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun capturesOnceAndPreservesPhotoAcrossSettingsAndRotation() {
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("capture") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("相机错误", substring = true)).assertCountEquals(0)
        compose.onNodeWithTag("script_selector").assertDoesNotExist()
        val preview = compose.onNodeWithTag("camera_preview").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(preview.height >= root.height * .95f)
        compose.onNodeWithTag("camera_gestures").performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("focus_indicator").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("已对焦") or hasContentDescription("未能合焦，请重试")).fetchSemanticsNodes().isNotEmpty()
        }
        val result = compose.onNodeWithTag("focus_indicator").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
        File(compose.activity.cacheDir, "focus-result.txt").writeText(result.joinToString())
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(compose.activity.cacheDir, "camera-fullscreen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val zoomBefore = compose.onNodeWithTag("camera_preview").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        File(compose.activity.cacheDir, "camera-zoom.txt").writeText("before=$zoomBefore")
        compose.onNodeWithTag("camera_gestures").performTouchInput {
            pinch(
                start0 = center + Offset(-50f, 0f),
                end0 = center + Offset(-180f, 0f),
                start1 = center + Offset(50f, 0f),
                end1 = center + Offset(180f, 0f),
                durationMillis = 500L,
            )
        }
        val zoomAfter = compose.onNodeWithTag("camera_preview").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        File(compose.activity.cacheDir, "camera-zoom.txt").appendText("\nafter=$zoomAfter")
        org.junit.Assert.assertTrue(
            "Pinch did not change zoom: before=$zoomBefore, after=$zoomAfter",
            compose.onAllNodesWithTag("camera_zoom").fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onAllNodes(hasText("相机错误", substring = true)).assertCountEquals(0)
        compose.onNodeWithTag("capture").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("photo_overlay").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[com.example.globaltranslation.ui.camera.CameraViewModel::class.java]
            val photo = requireNotNull(vm.uiState.value.photo)
            org.junit.Assert.assertEquals(preview.width / preview.height, photo.width.toFloat() / photo.height, .01f)
            File(compose.activity.cacheDir, "capture-frame.txt").writeText("preview=${preview.width}x${preview.height}; photo=${photo.width}x${photo.height}")
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("设置") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithContentDescription("返回相机").performClick()
        compose.onNodeWithTag("photo_overlay").assertExists()
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("photo_overlay").assertExists()
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitForIdle()
        compose.onNodeWithText("重新拍照").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("capture") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
}
