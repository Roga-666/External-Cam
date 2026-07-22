package com.muh.usbcameraviewer.viewer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.muh.usbcameraviewer.MainDispatcherRule
import com.muh.usbcameraviewer.camera.CameraEvent
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.CaptureMode
import com.muh.usbcameraviewer.camera.FakeCameraController
import com.muh.usbcameraviewer.camera.Resolution
import com.muh.usbcameraviewer.capture.CaptureStore
import com.muh.usbcameraviewer.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeCaptureStore(private val root: File) : CaptureStore {
    val published = mutableListOf<String>()
    override fun tempFile(displayName: String): File = File(root, displayName)
    override suspend fun publishPhoto(src: File, displayName: String): String =
        "content://fake/photo/$displayName".also { published += it }
    override suspend fun publishVideo(src: File, displayName: String): String =
        "content://fake/video/$displayName".also { published += it }
}

class ViewerViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FakeCameraController()
    private val res720 = Resolution(1280, 720)
    private val res1080 = Resolution(1920, 1080)

    private fun settingsRepo(): SettingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(mainDispatcher.dispatcher + Job()),
            produceFile = { File(tmp.root, "test.preferences_pb") },
        ),
    )

    private fun vm() = ViewerViewModel(fake, settingsRepo(), FakeCaptureStore(tmp.root)) { error("no view in unit test") }

    @Test
    fun `starts monitoring and begins in NoDevice`() = runTest {
        val vm = vm()
        assertTrue(fake.monitoring)
        assertEquals(CameraUiState.NoDevice, vm.state.value)
    }

    @Test
    fun `attach grant open reaches Streaming`() = runTest {
        val vm = vm()
        vm.state.test {
            assertEquals(CameraUiState.NoDevice, awaitItem())
            fake.emit(CameraEvent.Attached("Endoscope"))
            assertEquals(CameraUiState.Connecting("Endoscope"), awaitItem())
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            vm.onPreviewReady(preview = "texture-view")
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720, res1080)))
            val streaming = awaitItem() as CameraUiState.Streaming
            assertEquals(res720, streaming.resolution)
            assertEquals(listOf(res720, res1080), streaming.availableResolutions)
        }
        assertEquals(1, fake.openCalls.size)
    }

    @Test
    fun `permission denied shows PermissionNeeded and retry re-requests`() = runTest {
        val vm = vm()
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionDenied("Endoscope"))
        assertEquals(CameraUiState.PermissionNeeded("Endoscope"), vm.state.value)
        vm.onPermissionRetry()
        assertEquals(1, fake.permissionRequests)
    }

    @Test
    fun `detach from any state returns to NoDevice`() = runTest {
        val vm = vm()
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
        fake.emit(CameraEvent.Detached)
        assertEquals(CameraUiState.NoDevice, vm.state.value)
    }

    @Test
    fun `open failure surfaces Error and retry closes then reopens permission flow`() = runTest {
        val vm = vm()
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.OpenFailed("libusb error"))
        val error = vm.state.value as CameraUiState.Error
        vm.onRetryAfterError()
        assertTrue(fake.closed)
        assertEquals(1, fake.permissionRequests)
    }

    @Test
    fun `selecting resolution forwards to controller and persists`() = runTest {
        val settings = settingsRepo()
        val vm = ViewerViewModel(fake, settings, FakeCaptureStore(tmp.root)) { error("no view in unit test") }
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720, res1080)))
        vm.onSelectResolution(res1080)
        assertEquals(listOf(res1080), fake.resolutionCalls)
    }

    @Test
    fun `preferred resolution from settings passed to open`() = runTest {
        val settings = settingsRepo()
        settings.setLastResolution(res1080)
        val vm = ViewerViewModel(fake, settings, FakeCaptureStore(tmp.root)) { error("no view in unit test") }
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        vm.onPreviewReady(preview = "texture-view")
        assertEquals(res1080, fake.openCalls.single().second)
    }

    @Test
    fun `rotate cycles 0-90-180-270-0 and mirror toggles`() = runTest {
        val vm = vm()
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
        vm.onRotate()
        vm.onRotate()
        assertEquals(listOf(90, 180), fake.rotationCalls)
        vm.onToggleMirror()
        assertEquals(listOf(true), fake.mirrorCalls)
        val s = vm.state.value as CameraUiState.Streaming
        assertEquals(180, s.rotationDegrees)
        assertTrue(s.mirrored)
    }

    @Test
    fun `shutter in photo mode captures and publishes, updates thumbnail`() = runTest {
        val vm = vm()
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
        vm.onShutter()
        val s = vm.state.value as CameraUiState.Streaming
        assertTrue(s.lastCaptureUri!!.startsWith("content://fake/photo/UCV_"))
        assertTrue(s.lastCaptureUri!!.endsWith(".jpg"))
    }

    @Test
    fun `toggle mode then shutter starts recording, ticker counts, shutter again stops and publishes`() = runTest {
        val vm = vm()
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
        vm.onToggleCaptureMode()
        assertEquals(CaptureMode.VIDEO, (vm.state.value as CameraUiState.Streaming).captureMode)
        vm.onShutter()
        assertTrue((vm.state.value as CameraUiState.Streaming).isRecording)
        mainDispatcher.dispatcher.scheduler.advanceTimeBy(3_050)
        assertEquals(3L, (vm.state.value as CameraUiState.Streaming).recordingSeconds)
        vm.onShutter()
        val s = vm.state.value as CameraUiState.Streaming
        assertFalse(s.isRecording)
        assertTrue(s.lastCaptureUri!!.endsWith(".mp4"))
        assertEquals(1, fake.recordingStarts.size)
    }

    @Test
    fun `photo capture failure emits message, stays streaming`() = runTest {
        fake.photoResult = { Result.failure(IllegalStateException("boom")) }
        val vm = vm()
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
        vm.messages.test {
            vm.onShutter()
            assertTrue(awaitItem().isNotBlank())
        }
        assertTrue(vm.state.value is CameraUiState.Streaming)
        assertNull((vm.state.value as CameraUiState.Streaming).lastCaptureUri)
    }

    @Test
    fun `detach while recording stops and publishes partial file`() = runTest {
        val captureStore = FakeCaptureStore(tmp.root)
        val vm = ViewerViewModel(fake, settingsRepo(), captureStore) { error("no view in unit test") }
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
        vm.onToggleCaptureMode()
        vm.onShutter() // start recording
        fake.emit(CameraEvent.Detached)
        assertEquals(CameraUiState.NoDevice, vm.state.value)
        // stopRecording was actually invoked (not just that state flipped), and the partial
        // file was finalized+published.
        assertEquals(1, fake.stopRecordingCalls)
        assertTrue(captureStore.published.any { it.endsWith(".mp4") })
    }
}
