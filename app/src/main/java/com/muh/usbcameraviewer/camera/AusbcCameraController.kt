package com.muh.usbcameraviewer.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.usb.UsbDevice
import android.os.Build
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Upgrades flag-less `registerReceiver` calls with [Context.RECEIVER_NOT_EXPORTED].
 *
 * USBMonitor (inside libausbc 3.2.7) registers its USB-permission receiver with the
 * two-argument `registerReceiver`, which throws SecurityException on Android 14+
 * (targetSdk 34+) because the filter contains a non-system action. Wrapping the
 * Context handed to [MultiCameraClient] fixes this without touching the library.
 * NOT_EXPORTED is safe: the filter's system broadcasts (USB attach/detach) are
 * delivered regardless, and the permission broadcast originates from this app's
 * own PendingIntent.
 */
internal class ReceiverFlagContext(base: Context) : ContextWrapper(base) {
    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
    ): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            super.registerReceiver(receiver, filter)
        }
}

/**
 * The only class that talks to AUSBC (`com.jiangdg.*` / `com.serenegiant.*`).
 *
 * Targets AndroidUSBCamera **3.2.7** — the newest release whose full module set
 * (libausbc + libuvc + libnative) actually publishes on JitPack; 3.3.x ships a
 * libausbc AAR but its libuvc/libnative artifacts fail to build there. The 3.2.7
 * API differs from the 3.3.x code in the task brief; every difference is noted at
 * its call site below and in the task report.
 */
@Suppress("TooManyFunctions") // single facade over the whole AUSBC lifecycle + capture surface
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
        private var previewView: AspectRatioTextureView? = null

        // AUSBC 3.2.7's captureImage/captureVideoStart write to a file only after passing
        // CameraUtils.hasStoragePermission() — WRITE_EXTERNAL_STORAGE, which cannot be granted
        // on Android 13+, so those APIs always fail. We instead grab frames straight off the
        // preview TextureView (getBitmap) and encode photos/videos ourselves, via MediaStore.
        @Volatile private var currentSize: Resolution = Resolution(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)

        @Volatile private var availableSizes: List<Resolution> = emptyList()

        @Volatile private var videoRecorder: FrameVideoRecorder? = null

        private fun UsbDevice.displayName(): String = productName?.takeIf { it.isNotBlank() } ?: "USB Camera"

        /** Grab the frame currently rendered on the preview surface, on the main thread. */
        private suspend fun grabBitmap(): Bitmap? =
            withContext(Dispatchers.Main.immediate) {
                val view = previewView ?: return@withContext null
                if (!view.isAvailable) return@withContext null
                view.bitmap
            }

        override fun startMonitoring() {
            if (client != null) return
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                _events.tryEmit(CameraEvent.NoUsbHost)
                return
            }
            // 3.2.7: IDeviceConnectCallBack is a top-level callback interface
            // (com.jiangdg.ausbc.callback), not nested in MultiCameraClient.
            client =
                MultiCameraClient(
                    ReceiverFlagContext(context),
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
                                // Device is truly gone: the control block is now stale.
                                ctrlBlock = null
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
                            // Device disconnected: drop the stale control block.
                            this@AusbcCameraController.ctrlBlock = null
                            _events.tryEmit(CameraEvent.Detached)
                        }

                        override fun onCancelDev(device: UsbDevice?) {
                            device ?: return
                            // Permission cancelled: no valid control block for this device.
                            ctrlBlock = null
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
            val view =
                preview as? AspectRatioTextureView
                    ?: run {
                        _events.tryEmit(CameraEvent.OpenFailed("preview is not AspectRatioTextureView"))
                        return
                    }
            openInternal(view, preferred)
        }

        private fun openInternal(
            view: AspectRatioTextureView,
            preferred: Resolution?,
        ) {
            // Close any camera opened earlier before constructing a new one, so a
            // re-open (returning from the gallery, foregrounding, resolution change) never
            // double-opens or leaks the native camera. ctrlBlock is preserved by teardownCamera().
            teardownCamera()
            val device = pendingDevice
            val block = ctrlBlock
            if (device == null || block == null) {
                _events.tryEmit(CameraEvent.OpenFailed("no device or control block"))
                return
            }
            previewView = view
            // Re-letterbox whenever the view resizes (e.g. the screen rotates) — the transform is
            // computed from the view dimensions, which change on rotation.
            view.addOnLayoutChangeListener { _, left, top, right, bottom, oldL, oldT, oldR, oldB ->
                if (right - left != oldR - oldL || bottom - top != oldB - oldT) {
                    applyPreviewTransform(currentSize)
                }
            }
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
                                currentSize = current
                                availableSizes = sizes
                                applyPreviewTransform(current)
                                attachButtonCallback(cam)
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
            runCatching { videoRecorder?.stop() }
            videoRecorder = null
            runCatching { camera?.closeCamera() }
            camera = null
            previewView = null
        }

        override suspend fun setResolution(resolution: Resolution): Unit =
            withContext(Dispatchers.IO) {
                setResolutionBlocking(resolution)
            }

        private fun setResolutionBlocking(resolution: Resolution) {
            // AUSBC 3.2.7's own resize paths are unusable: updateResolution() crashes (null
            // SurfaceTexture) and its getSuitableSize() collapses every request to 640x480 (it
            // returns the first advertised size that is EITHER the exact request OR 640x480, and
            // 640x480 is usually listed first). So drive the underlying UVCCamera directly:
            // stop, set the exact size (prefer MJPEG for high-res, fall back to YUYV), restart.
            val cam = camera ?: return
            val uvc = uvcCameraOf(cam) ?: return
            val device = pendingDevice
            val surface = previewView?.surfaceTexture
            runCatching {
                uvc.stopPreview()
                val ok =
                    runCatching {
                        uvc.setPreviewSize(
                            resolution.width,
                            resolution.height,
                            UVCCamera.DEFAULT_PREVIEW_MIN_FPS,
                            UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
                            UVCCamera.FRAME_FORMAT_MJPEG,
                            PREVIEW_BANDWIDTH,
                        )
                    }.isSuccess
                if (!ok) {
                    uvc.setPreviewSize(
                        resolution.width,
                        resolution.height,
                        UVCCamera.DEFAULT_PREVIEW_MIN_FPS,
                        UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
                        UVCCamera.FRAME_FORMAT_YUYV,
                        PREVIEW_BANDWIDTH,
                    )
                }
                // stopPreview() detaches the native preview window; re-attach the surface before
                // restarting or startPreview logs "window does not exist" and stays black.
                if (surface != null) uvc.setPreviewTexture(surface)
                uvc.startPreview()
                currentSize = resolution
                applyPreviewTransform(resolution)
                if (device != null) {
                    _events.tryEmit(CameraEvent.Opened(device.displayName(), resolution, availableSizes))
                }
            }.onFailure {
                _events.tryEmit(CameraEvent.OpenFailed("resolution change failed"))
            }
        }

        /**
         * Route the physical shutter button on the USB camera to a capture. Best-effort: not every
         * device reports button events.
         */
        private fun attachButtonCallback(cam: MultiCameraClient.Camera) {
            uvcCameraOf(cam)?.setButtonCallback { _, state ->
                if (state != 0) _events.tryEmit(CameraEvent.ShutterPressed)
            }
        }

        override suspend fun isPreviewLive(): Boolean {
            val bitmap = grabBitmap() ?: return false
            // Sample a coarse grid; any bright pixel means frames are flowing (endoscope LED is on).
            val last = LIVENESS_SAMPLES - 1
            return (0..last).any { r ->
                (0..last).any { c ->
                    val x = (bitmap.width - 1) * c / last
                    val y = (bitmap.height - 1) * r / last
                    luminanceOf(bitmap.getPixel(x, y)) > BLACK_LUMINANCE
                }
            }
        }

        private fun luminanceOf(pixel: Int): Int =
            maxOf(
                (pixel shr RED_SHIFT) and COLOR_MASK,
                (pixel shr GREEN_SHIFT) and COLOR_MASK,
                pixel and COLOR_MASK,
            )

        /** The UVCCamera is private to MultiCameraClient.Camera; reach it reflectively once open. */
        private fun uvcCameraOf(cam: MultiCameraClient.Camera): UVCCamera? =
            runCatching {
                val field = MultiCameraClient.Camera::class.java.getDeclaredField("mUvcCamera")
                field.isAccessible = true
                field.get(cam) as? UVCCamera
            }.getOrNull()

        /**
         * Letterbox the full-size preview TextureView to the stream's aspect ratio with a content
         * transform, so the picture is never stretched and the surface itself never resizes (which
         * would blank the native stream during a resolution change).
         */
        private fun applyPreviewTransform(size: Resolution) {
            val view = previewView ?: return
            if (size.width <= 0 || size.height <= 0) return
            view.post {
                val vw = view.width.toFloat()
                val vh = view.height.toFloat()
                if (vw <= 0f || vh <= 0f) return@post
                val viewAspect = vw / vh
                val streamAspect = size.width.toFloat() / size.height
                val (scaleX, scaleY) =
                    if (streamAspect >= viewAspect) {
                        1f to viewAspect / streamAspect
                    } else {
                        streamAspect / viewAspect to 1f
                    }
                view.setTransform(Matrix().apply { setScale(scaleX, scaleY, vw / 2f, vh / 2f) })
            }
        }

        override fun setRotation(degrees: Int) {
            // 3.2.7 has no render-side rotation; rotate the preview surface itself.
            previewView?.rotation = degrees.toFloat()
        }

        override fun setMirror(mirrored: Boolean) {
            // 3.2.7 has no render-side flip; mirror the preview surface horizontally.
            previewView?.scaleX = if (mirrored) -1f else 1f
        }

        override suspend fun capturePhoto(target: File): Result<File> {
            val bitmap =
                grabBitmap()
                    ?: return Result.failure(IllegalStateException("no preview frame yet"))
            return withContext(Dispatchers.IO) {
                runCatching {
                    FileOutputStream(target).use { out ->
                        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        check(ok) { "JPEG compression failed" }
                    }
                    target
                }
            }
        }

        override suspend fun startRecording(
            target: File,
            withAudio: Boolean,
            bitRate: Int,
        ): Result<Unit> {
            val view = previewView ?: return Result.failure(IllegalStateException("camera not open"))
            // Even preview dimensions the encoder can handle; the source is the TextureView bitmap.
            val w = currentSize.width.coerceAtLeast(2).let { it - (it % 2) }
            val h = currentSize.height.coerceAtLeast(2).let { it - (it % 2) }
            return withContext(Dispatchers.IO) {
                runCatching {
                    val recorder =
                        FrameVideoRecorder(
                            outputFile = target,
                            width = w,
                            height = h,
                            bitRate = if (bitRate > 0) bitRate else DEFAULT_BIT_RATE,
                            withAudio = withAudio,
                            frameProvider = { view.takeIf { it.isAvailable }?.getBitmap(w, h) },
                        )
                    recorder.start()
                    videoRecorder = recorder
                }
            }
        }

        override suspend fun stopRecording(): Result<File> {
            val recorder =
                videoRecorder
                    ?: return Result.failure(IllegalStateException("not recording"))
            videoRecorder = null
            return withContext(Dispatchers.IO) {
                val file = recorder.stop()
                if (file != null && file.exists() && file.length() > 0) {
                    Result.success(file)
                } else {
                    Result.failure(IllegalStateException("recording file missing or empty"))
                }
            }
        }

        private companion object {
            const val DEFAULT_PREVIEW_WIDTH = 1280
            const val DEFAULT_PREVIEW_HEIGHT = 720
            const val JPEG_QUALITY = 95
            const val DEFAULT_BIT_RATE = 6_000_000
            const val PREVIEW_BANDWIDTH = 1.0f
            const val LIVENESS_SAMPLES = 8
            const val BLACK_LUMINANCE = 12
            const val RED_SHIFT = 16
            const val GREEN_SHIFT = 8
            const val COLOR_MASK = 0xFF
        }
    }
