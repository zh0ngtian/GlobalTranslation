package com.example.globaltranslation

import android.Manifest
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
        compose.onNodeWithTag("capture").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("photo_overlay").fetchSemanticsNodes().isNotEmpty() }
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
