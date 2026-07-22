package com.muh.usbcameraviewer.viewer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.muh.usbcameraviewer.MainDispatcherRule
import com.muh.usbcameraviewer.camera.CameraEvent
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.FakeCameraController
import com.muh.usbcameraviewer.camera.Resolution
import com.muh.usbcameraviewer.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    private fun vm() = ViewerViewModel(fake, settingsRepo()) { error("no view in unit test") }

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
        val vm = ViewerViewModel(fake, settings) { error("no view in unit test") }
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
        val vm = ViewerViewModel(fake, settings) { error("no view in unit test") }
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
}
