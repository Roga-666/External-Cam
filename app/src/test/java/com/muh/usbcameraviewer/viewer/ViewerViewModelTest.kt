package com.muh.usbcameraviewer.viewer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.muh.usbcameraviewer.MainDispatcherRule
import com.muh.usbcameraviewer.camera.CameraError
import com.muh.usbcameraviewer.camera.CameraEvent
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.CaptureMode
import com.muh.usbcameraviewer.camera.FakeCameraController
import com.muh.usbcameraviewer.camera.Resolution
import com.muh.usbcameraviewer.capture.CaptureStore
import com.muh.usbcameraviewer.capture.StorageChecker
import com.muh.usbcameraviewer.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeCaptureStore(private val root: File) : CaptureStore {
    val published = mutableListOf<String>()

    override fun tempFile(displayName: String): File = File(root, displayName)

    override suspend fun publishPhoto(
        src: File,
        displayName: String,
    ): String = "content://fake/photo/$displayName".also { published += it }

    override suspend fun publishVideo(
        src: File,
        displayName: String,
    ): String = "content://fake/video/$displayName".also { published += it }
}

class FakeStorageChecker(var available: Long = Long.MAX_VALUE) : StorageChecker {
    override fun availableBytes(): Long = available
}

class ViewerViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @get:Rule val tmp = TemporaryFolder()

    private val fake = FakeCameraController()
    private val storage = FakeStorageChecker()
    private val res720 = Resolution(1280, 720)
    private val res1080 = Resolution(1920, 1080)

    private fun settingsRepo(): SettingsRepository =
        SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(mainDispatcher.dispatcher + Job()),
                produceFile = { File(tmp.root, "test.preferences_pb") },
            ),
        )

    private fun vm() =
        ViewerViewModel(
            fake,
            settingsRepo(),
            FakeCaptureStore(tmp.root),
            storage,
        ) { error("no view in unit test") }

    @Test
    fun `starts monitoring and begins in NoDevice`() =
        runTest {
            val vm = vm()
            assertTrue(fake.monitoring)
            assertEquals(CameraUiState.NoDevice, vm.state.value)
        }

    @Test
    fun `attach grant open reaches Streaming`() =
        runTest {
            val vm = vm()
            vm.state.test {
                assertEquals(CameraUiState.NoDevice, awaitItem())
                fake.emit(CameraEvent.Attached("Endoscope"))
                assertEquals(CameraUiState.WaitingPermission("Endoscope"), awaitItem())
                fake.emit(CameraEvent.PermissionGranted("Endoscope"))
                assertEquals(CameraUiState.Connecting("Endoscope"), awaitItem())
                vm.onPreviewReady(preview = "texture-view")
                fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720, res1080)))
                val streaming = awaitItem() as CameraUiState.Streaming
                assertEquals(res720, streaming.resolution)
                assertEquals(listOf(res720, res1080), streaming.availableResolutions)
            }
            assertEquals(1, fake.openCalls.size)
        }

    @Test
    fun `permission denied shows PermissionNeeded and retry re-requests`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionDenied("Endoscope"))
            assertEquals(CameraUiState.PermissionNeeded("Endoscope"), vm.state.value)
            vm.onPermissionRetry()
            assertEquals(1, fake.permissionRequests)
        }

    @Test
    fun `camera permission granted re-requests USB permission`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            vm.onCameraPermissionResult(granted = true)
            assertEquals(1, fake.permissionRequests)
        }

    @Test
    fun `camera permission denied emits a message and does not re-request`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            vm.messages.test {
                vm.onCameraPermissionResult(granted = false)
                assertEquals(ViewerMessage.CameraPermissionDenied, awaitItem())
            }
            assertEquals(0, fake.permissionRequests)
        }

    @Test
    fun `detach from any state returns to NoDevice`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
            fake.emit(CameraEvent.Detached)
            assertEquals(CameraUiState.NoDevice, vm.state.value)
        }

    @Test
    fun `open failure surfaces Error and retry closes then reopens permission flow`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.OpenFailed("libusb error"))
            val error = vm.state.value as CameraUiState.Error
            vm.onRetryAfterError()
            assertTrue(fake.closed)
            assertEquals(1, fake.permissionRequests)
        }

    @Test
    fun `selecting resolution forwards to controller and persists`() =
        runTest {
            val settings = settingsRepo()
            val vm =
                ViewerViewModel(fake, settings, FakeCaptureStore(tmp.root), storage) {
                    error("no view in unit test")
                }
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720, res1080)))
            vm.onSelectResolution(res1080)
            assertEquals(listOf(res1080), fake.resolutionCalls)
            assertEquals(res1080, settings.settings.first().lastResolution)
        }

    @Test
    fun `resolution that delivers no frames reverts to the previous one with a message`() =
        runTest {
            val settings = settingsRepo()
            val vm =
                ViewerViewModel(fake, settings, FakeCaptureStore(tmp.root), storage) {
                    error("no view in unit test")
                }
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720, res1080)))
            fake.previewLive = false // the new mode delivers only black frames
            vm.messages.test {
                vm.onSelectResolution(res1080)
                assertEquals(ViewerMessage.ResolutionUnsupported(res1080), awaitItem())
            }
            // Switched to the requested size, then reverted to the previous one.
            assertEquals(listOf(res1080, res720), fake.resolutionCalls)
            assertEquals(res720, settings.settings.first().lastResolution)
        }

    @Test
    fun `preferred resolution from settings passed to open`() =
        runTest {
            val settings = settingsRepo()
            settings.setLastResolution(res1080)
            val vm =
                ViewerViewModel(fake, settings, FakeCaptureStore(tmp.root), storage) {
                    error("no view in unit test")
                }
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            vm.onPreviewReady(preview = "texture-view")
            assertEquals(res1080, fake.openCalls.single().second)
        }

    @Test
    fun `rotate cycles 0-90-180-270-0 and mirror toggles`() =
        runTest {
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
    fun `shutter in photo mode captures and publishes, updates thumbnail`() =
        runTest {
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
    fun `toggle mode then shutter starts recording, ticker counts, shutter again stops and publishes`() =
        runTest {
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
    fun `photo capture failure emits message, stays streaming`() =
        runTest {
            fake.photoResult = { Result.failure(IllegalStateException("boom")) }
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
            vm.messages.test {
                vm.onShutter()
                assertTrue(awaitItem() is ViewerMessage.PhotoFailed)
            }
            assertTrue(vm.state.value is CameraUiState.Streaming)
            assertNull((vm.state.value as CameraUiState.Streaming).lastCaptureUri)
        }

    @Test
    fun `detach while recording stops and publishes partial file`() =
        runTest {
            val captureStore = FakeCaptureStore(tmp.root)
            val vm = ViewerViewModel(fake, settingsRepo(), captureStore, storage) { error("no view in unit test") }
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
            vm.onToggleCaptureMode()
            vm.onShutter() // start recording
            // The controller finalizes the recording itself on detach and reports the salvaged
            // file before the Detached event; the ViewModel publishes it.
            val partial = File(tmp.root, "partial.mp4").apply { writeText("mp4") }
            fake.emit(CameraEvent.RecordingFinalized(partial))
            fake.emit(CameraEvent.Detached)
            assertEquals(CameraUiState.NoDevice, vm.state.value)
            assertTrue(captureStore.published.any { it.endsWith(".mp4") })
        }

    @Test
    fun `attach emits WaitingPermission before permission is granted`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            assertEquals(CameraUiState.WaitingPermission("Endoscope"), vm.state.value)
        }

    @Test
    fun `no usb host support maps to unrecoverable Error`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.NoUsbHost)
            assertEquals(CameraUiState.Error(CameraError.NoUsbHostSupport), vm.state.value)
        }

    @Test
    fun `low storage blocks recording and emits message`() =
        runTest {
            storage.available = 10L * 1024 * 1024 // 10 MB, below the 200 MB floor
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
            vm.onToggleCaptureMode()
            vm.messages.test {
                vm.onShutter()
                assertEquals(ViewerMessage.LowStorage, awaitItem())
            }
            assertFalse((vm.state.value as CameraUiState.Streaming).isRecording)
            assertTrue(fake.recordingStarts.isEmpty())
        }

    @Test
    fun `preview disposed closes camera and demotes Streaming to Connecting`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
            vm.onPreviewDisposed()
            assertTrue(fake.closed)
            assertEquals(CameraUiState.Connecting("Endoscope"), vm.state.value)
        }

    @Test
    fun `preview disposed while recording finalizes the recording`() =
        runTest {
            val captureStore = FakeCaptureStore(tmp.root)
            val vm = ViewerViewModel(fake, settingsRepo(), captureStore, storage) { error("no view in unit test") }
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
            vm.onToggleCaptureMode()
            vm.onShutter() // start recording
            // close() (via onPreviewDisposed) finalizes the recording and emits
            // RecordingFinalized — the fake mirrors the real controller's contract.
            vm.onPreviewDisposed()
            assertTrue(captureStore.published.any { it.endsWith(".mp4") })
            assertEquals(CameraUiState.Connecting("Endoscope"), vm.state.value)
        }

    @Test
    fun `resolution switch is blocked while recording`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720, res1080)))
            vm.onToggleCaptureMode()
            vm.onShutter() // start recording
            vm.onSelectResolution(res1080)
            assertTrue(fake.resolutionCalls.isEmpty())
            // Stop the recording so its ticker coroutine ends and runTest can go idle.
            vm.onShutter()
        }

    @Test
    fun `zoom changes forward to controller`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
            vm.onZoomChanged(2f, 30f, -10f)
            assertEquals(listOf(Triple(2f, 30f, -10f)), fake.zoomCalls)
        }

    @Test
    fun `persisted brightness and contrast are applied on open`() =
        runTest {
            val settings = settingsRepo()
            settings.setBrightnessPercent(80)
            settings.setContrastPercent(20)
            val vm =
                ViewerViewModel(fake, settings, FakeCaptureStore(tmp.root), storage) {
                    error("no view in unit test")
                }
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
            assertEquals(listOf(80), fake.brightnessCalls)
            assertEquals(listOf(20), fake.contrastCalls)
            val s = vm.state.value as CameraUiState.Streaming
            assertEquals(80, s.brightnessPercent)
            assertEquals(20, s.contrastPercent)
        }

    @Test
    fun `brightness and contrast forward to controller and update state`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
            vm.onBrightness(70)
            vm.onContrast(30)
            assertEquals(listOf(70), fake.brightnessCalls)
            assertEquals(listOf(30), fake.contrastCalls)
            val s = vm.state.value as CameraUiState.Streaming
            assertEquals(70, s.brightnessPercent)
            assertEquals(30, s.contrastPercent)
        }

    @Test
    fun `backgrounding keeps the camera open and streaming`() =
        runTest {
            val vm = vm()
            fake.emit(CameraEvent.Attached("Endoscope"))
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            vm.onPreviewReady(preview = "texture-view")
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
            assertEquals(1, fake.openCalls.size)
            vm.onAppBackgrounded()
            assertFalse(fake.closed)
            assertTrue(vm.state.value is CameraUiState.Streaming)
            // Foregrounding while still Streaming does not re-open — the camera never closed.
            vm.onAppForegrounded()
            assertEquals(1, fake.openCalls.size)
        }
}
