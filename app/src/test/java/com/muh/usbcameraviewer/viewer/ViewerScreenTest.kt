package com.muh.usbcameraviewer.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muh.usbcameraviewer.camera.CameraError
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.CaptureMode
import com.muh.usbcameraviewer.camera.Resolution
import com.muh.usbcameraviewer.ui.theme.UcvTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ViewerScreenTest {

    @get:Rule val compose = createComposeRule()

    private val streaming = CameraUiState.Streaming(
        deviceName = "Endoscope",
        resolution = Resolution(1280, 720),
        availableResolutions = listOf(Resolution(1280, 720), Resolution(1920, 1080)),
    )

    private fun setContent(state: CameraUiState, callbacks: ViewerCallbacks = ViewerCallbacks()) {
        compose.setContent { UcvTheme { ViewerContent(state = state, callbacks = callbacks) } }
    }

    @Test
    fun noDevice_showsPlugInHelp() {
        setContent(CameraUiState.NoDevice)
        compose.onNodeWithText("Plug in your camera").assertIsDisplayed()
    }

    @Test
    fun permissionNeeded_grantButtonInvokesRetry() {
        var retried = false
        setContent(
            CameraUiState.PermissionNeeded("Endoscope"),
            ViewerCallbacks(onPermissionRetry = { retried = true }),
        )
        compose.onNodeWithText("Grant permission").performClick()
        assertTrue(retried)
    }

    @Test
    fun connecting_showsSpinnerAndCreatesPreview() {
        var previewReady = false
        setContent(
            CameraUiState.Connecting("Endoscope"),
            ViewerCallbacks(onPreviewReady = { previewReady = true }),
        )
        compose.onNodeWithText("Connecting to Endoscope…").assertIsDisplayed()
        assertTrue(previewReady)
    }

    @Test
    fun streaming_showsShutterAndControls() {
        setContent(streaming)
        compose.onNodeWithContentDescription("Take photo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rotate preview").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open gallery").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose resolution").assertIsDisplayed()
    }

    @Test
    fun streaming_recording_showsElapsedChip() {
        setContent(streaming.copy(captureMode = CaptureMode.VIDEO, isRecording = true, recordingSeconds = 83))
        compose.onNodeWithText("01:23").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [28])
    fun streaming_legacySdk_shutterWithoutStoragePermission_doesNotCaptureYet() {
        var shutterInvoked = false
        setContent(streaming, ViewerCallbacks(onShutter = { shutterInvoked = true }))
        compose.onNodeWithContentDescription("Take photo").performClick()
        // WRITE_EXTERNAL_STORAGE isn't granted in this Robolectric environment, so the click
        // must take the permission-request path instead of invoking the shutter directly.
        assertFalse(shutterInvoked)
    }

    @Test
    fun error_showsMessageAndRetry() {
        var retried = false
        setContent(
            CameraUiState.Error(CameraError.OpenFailed("x")),
            ViewerCallbacks(onRetryAfterError = { retried = true }),
        )
        compose.onNodeWithText("Could not open the camera.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }
}
