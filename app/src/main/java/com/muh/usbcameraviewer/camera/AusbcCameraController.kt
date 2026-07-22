package com.muh.usbcameraviewer.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.View
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.serenegiant.usb.USBMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The only class that talks to AUSBC (`com.jiangdg.*` / `com.serenegiant.*`).
 *
 * Targets AndroidUSBCamera **3.2.7** — the newest release whose full module set
 * (libausbc + libuvc + libnative) actually publishes on JitPack; 3.3.x ships a
 * libausbc AAR but its libuvc/libnative artifacts fail to build there. The 3.2.7
 * API differs from the 3.3.x code in the task brief; every difference is noted at
 * its call site below and in the task report.
 */
@Singleton
class AusbcCameraController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CameraController {
        private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<CameraEvent> = _events

        private var client: MultiCameraClient? = null

        // 3.2.7 has no CameraUVC subclass; MultiCameraClient.Camera is concrete and UVC-capable.
        private var camera: MultiCameraClient.Camera? = null
        private var pendingDevice: UsbDevice? = null
        private var ctrlBlock: USBMonitor.UsbControlBlock? = null

        // 3.2.7 exposes no render rotation/mirror on the camera, so rotate/mirror are
        // applied as View transforms on the preview surface (see setRotation/setMirror).
        private var previewView: View? = null
        private var recordingFile: File? = null

        private fun UsbDevice.displayName(): String = productName?.takeIf { it.isNotBlank() } ?: "USB Camera"

        override fun startMonitoring() {
            if (client != null) return
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                _events.tryEmit(CameraEvent.OpenFailed("no-usb-host"))
                return
            }
            // 3.2.7: IDeviceConnectCallBack is a top-level callback interface
            // (com.jiangdg.ausbc.callback), not nested in MultiCameraClient.
            client =
                MultiCameraClient(
                    context,
                    object : IDeviceConnectCallBack {
                        override fun onAttachDev(device: UsbDevice?) {
                            device ?: return
                            pendingDevice = device
                            _events.tryEmit(CameraEvent.Attached(device.displayName()))
                            client?.requestPermission(device)
                        }

                        override fun onDetachDec(device: UsbDevice?) {
                            if (device == pendingDevice) {
                                teardownCamera()
                                pendingDevice = null
                                _events.tryEmit(CameraEvent.Detached)
                            }
                        }

                        override fun onConnectDev(
                            device: UsbDevice?,
                            ctrlBlock: USBMonitor.UsbControlBlock?,
                        ) {
                            device ?: return
                            this@AusbcCameraController.ctrlBlock = ctrlBlock
                            _events.tryEmit(CameraEvent.PermissionGranted(device.displayName()))
                        }

                        override fun onDisConnectDec(
                            device: UsbDevice?,
                            ctrlBlock: USBMonitor.UsbControlBlock?,
                        ) {
                            teardownCamera()
                            _events.tryEmit(CameraEvent.Detached)
                        }

                        override fun onCancelDev(device: UsbDevice?) {
                            device ?: return
                            _events.tryEmit(CameraEvent.PermissionDenied(device.displayName()))
                        }
                    },
                )
            client?.register()
        }

        override fun stopMonitoring() {
            teardownCamera()
            client?.unRegister()
            client?.destroy()
            client = null
        }

        override fun requestPermission() {
            val device = pendingDevice ?: return
            client?.requestPermission(device)
        }

        override fun open(
            preview: Any,
            preferred: Resolution?,
        ) {
            val device = pendingDevice
            val block = ctrlBlock
            if (device == null || block == null) {
                _events.tryEmit(CameraEvent.OpenFailed("no device or control block"))
                return
            }
            val view =
                preview as? AspectRatioTextureView
                    ?: run {
                        _events.tryEmit(CameraEvent.OpenFailed("preview is not AspectRatioTextureView"))
                        return
                    }
            previewView = view
            // 3.2.7: instantiate MultiCameraClient.Camera directly (no CameraUVC class).
            val cam = MultiCameraClient.Camera(context, device)
            cam.setUsbControlBlock(block)
            cam.setCameraStateCallBack(
                object : ICameraStateCallBack {
                    // 3.2.7: first param is MultiCameraClient.Camera (was ICamera in 3.3.x); msg nullable.
                    override fun onCameraState(
                        self: MultiCameraClient.Camera,
                        code: ICameraStateCallBack.State,
                        msg: String?,
                    ) {
                        when (code) {
                            ICameraStateCallBack.State.OPENED -> {
                                val sizes =
                                    cam.getAllPreviewSizes()
                                        .map { Resolution(it.width, it.height) }
                                        .distinct()
                                        .sortedByDescending { it.width * it.height }
                                val current =
                                    cam.getPreviewSize()
                                        ?.let { Resolution(it.width, it.height) }
                                        ?: cam.getCameraRequest()
                                            ?.let { Resolution(it.previewWidth, it.previewHeight) }
                                        ?: sizes.firstOrNull()
                                        ?: Resolution(0, 0)
                                _events.tryEmit(
                                    CameraEvent.Opened(device.displayName(), current, sizes),
                                )
                            }
                            ICameraStateCallBack.State.CLOSED -> Unit
                            ICameraStateCallBack.State.ERROR ->
                                _events.tryEmit(CameraEvent.OpenFailed(msg ?: "camera error"))
                        }
                    }
                },
            )
            // 3.2.7 CameraRequest.Builder only exposes preview dimensions + camera-id/AF/AE flags;
            // there is no setRenderMode/setAspectRatioShow/setRawPreviewData as in the 3.3.x brief.
            val request =
                CameraRequest.Builder()
                    .setPreviewWidth(preferred?.width ?: DEFAULT_PREVIEW_WIDTH)
                    .setPreviewHeight(preferred?.height ?: DEFAULT_PREVIEW_HEIGHT)
                    .create()
            cam.openCamera(view, request)
            camera = cam
        }

        override fun close() = teardownCamera()

        private fun teardownCamera() {
            runCatching { camera?.captureVideoStop() }
            runCatching { camera?.closeCamera() }
            camera = null
            previewView = null
        }

        override fun setResolution(resolution: Resolution) {
            camera?.updateResolution(resolution.width, resolution.height)
        }

        override fun setRotation(degrees: Int) {
            // 3.2.7 has no render-side rotation; rotate the preview surface itself.
            previewView?.rotation = degrees.toFloat()
        }

        override fun setMirror(mirrored: Boolean) {
            // 3.2.7 has no render-side flip; mirror the preview surface horizontally.
            previewView?.scaleX = if (mirrored) -1f else 1f
        }

        override suspend fun capturePhoto(target: File): Result<File> =
            suspendCancellableCoroutine { cont ->
                val cam =
                    camera ?: run {
                        cont.resume(Result.failure(IllegalStateException("camera not open")))
                        return@suspendCancellableCoroutine
                    }
                cam.captureImage(
                    object : ICaptureCallBack {
                        override fun onBegin() = Unit

                        override fun onComplete(path: String?) {
                            if (cont.isActive) {
                                cont.resume(
                                    if (path != null) {
                                        Result.success(File(path))
                                    } else {
                                        Result.failure(IllegalStateException("null capture path"))
                                    },
                                )
                            }
                        }

                        override fun onError(error: String?) {
                            if (cont.isActive) {
                                cont.resume(
                                    Result.failure(IllegalStateException(error ?: "capture failed")),
                                )
                            }
                        }
                    },
                    target.absolutePath,
                )
            }

        override suspend fun startRecording(
            target: File,
            withAudio: Boolean,
            bitRate: Int,
        ): Result<Unit> {
            val cam = camera ?: return Result.failure(IllegalStateException("camera not open"))
            if (withAudio || bitRate > 0) {
                // 3.2.7's captureVideoStart(callBack, path, durationInSec) takes neither an audio
                // toggle nor a bit-rate; record video-only until a version that supports them.
                Log.w(TAG, "AUSBC 3.2.7 ignores withAudio=$withAudio / bitRate=$bitRate; recording video-only")
            }
            recordingFile = target
            return suspendCancellableCoroutine { cont ->
                cam.captureVideoStart(
                    object : ICaptureCallBack {
                        override fun onBegin() {
                            if (cont.isActive) cont.resume(Result.success(Unit))
                        }

                        override fun onComplete(path: String?) = Unit // fires at stop

                        override fun onError(error: String?) {
                            if (cont.isActive) {
                                cont.resume(
                                    Result.failure(IllegalStateException(error ?: "record failed")),
                                )
                            }
                        }
                    },
                    target.absolutePath,
                    0L,
                )
            }
        }

        override suspend fun stopRecording(): Result<File> {
            val file =
                recordingFile
                    ?: return Result.failure(IllegalStateException("not recording"))
            runCatching { camera?.captureVideoStop() }
            recordingFile = null
            return if (file.exists() && file.length() > 0) {
                Result.success(file)
            } else {
                Result.failure(IllegalStateException("recording file missing or empty"))
            }
        }

        private companion object {
            const val TAG = "AusbcCameraController"
            const val DEFAULT_PREVIEW_WIDTH = 1280
            const val DEFAULT_PREVIEW_HEIGHT = 720
        }
    }
