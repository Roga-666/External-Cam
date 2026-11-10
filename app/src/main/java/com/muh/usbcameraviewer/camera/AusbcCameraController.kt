package com.muh.usbcameraviewer.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.utils.CameraUtils
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
 * Context handed to [USBMonitor] fixes this without touching the library.
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
 * USBMonitor fires onDisconnect whenever ANY UsbControlBlock is closed — including the clone
 * closed by the library's own closeCamera() while the device stays plugged in. A disconnect is a
 * real detach only when the device we are using is no longer present on the USB bus.
 */
internal fun shouldTreatDisconnectAsDetach(
    pendingDeviceName: String?,
    attachedDeviceNames: Collection<String>,
): Boolean = pendingDeviceName == null || pendingDeviceName !in attachedDeviceNames

private const val BLACK_LUMINANCE = 12
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val COLOR_MASK = 0xFF

internal fun luminanceOf(pixel: Int): Int =
    maxOf(
        (pixel shr RED_SHIFT) and COLOR_MASK,
        (pixel shr GREEN_SHIFT) and COLOR_MASK,
        pixel and COLOR_MASK,
    )

/**
 * A stream is live when any sampled pixel is bright (endoscope LEDs light every real frame) OR
 * the sample grid changed between two probes — sensor noise makes consecutive frames differ even
 * in the dark, while a dead stream repeats one identical frame. Brightness alone falsely reverted
 * resolution switches in dark scenes.
 */
internal fun samplesIndicateLive(
    previous: IntArray?,
    current: IntArray,
): Boolean =
    current.any { it > BLACK_LUMINANCE } ||
        (previous != null && !previous.contentEquals(current))

/**
 * Bake the display-only rotate/mirror View transforms into a captured bitmap so the saved file
 * matches what the user sees. Mirror is applied before rotation, matching how the preview stacks
 * scaleX on top of the rotated view. Recycles [bitmap] when a new one is produced.
 */
internal fun bakeOrientation(
    bitmap: Bitmap,
    rotationDegrees: Int,
    mirrored: Boolean,
): Bitmap {
    val degrees = ((rotationDegrees % FULL_TURN_DEGREES) + FULL_TURN_DEGREES) % FULL_TURN_DEGREES
    if (degrees == 0 && !mirrored) return bitmap
    val quarterTurned = degrees % HALF_TURN_DEGREES != 0
    val outW = if (quarterTurned) bitmap.height else bitmap.width
    val outH = if (quarterTurned) bitmap.width else bitmap.height
    val baked = Bitmap.createBitmap(outW, outH, bitmap.config ?: Bitmap.Config.ARGB_8888)
    Canvas(baked).apply {
        concat(orientationMatrix(bitmap.width, bitmap.height, degrees, mirrored))
        drawBitmap(bitmap, 0f, 0f, null)
    }
    bitmap.recycle()
    return baked
}

/**
 * Maps source-bitmap coordinates to the baked output: mirror first (as the preview stacks
 * scaleX on the rotated view), then rotate, then shift into the (possibly dimension-swapped)
 * output rect. Pure math, unit-tested separately from the raster step.
 */
internal fun orientationMatrix(
    width: Int,
    height: Int,
    degrees: Int,
    mirrored: Boolean,
): Matrix =
    Matrix().apply {
        val cx = width / 2f
        val cy = height / 2f
        if (mirrored) postScale(-1f, 1f, cx, cy)
        if (degrees != 0) postRotate(degrees.toFloat(), cx, cy)
        if (degrees % HALF_TURN_DEGREES != 0) {
            postTranslate((height - width) / 2f, (width - height) / 2f)
        }
    }

private const val HALF_TURN_DEGREES = 180

/**
 * Content transform for the preview TextureView: letterbox the (stretched-to-view) stream to its
 * correct aspect, then mirror, then rotate — all around the view center. Doing rotation inside
 * this matrix (instead of View.rotation) keeps the letterbox correct for 90°/270°, where the
 * displayed aspect inverts and a view-level rotation showed a stretched, overflowing picture.
 */
@Suppress("LongParameterList") // one value per pipeline input; a param object would add noise
internal fun previewTransformMatrix(
    streamW: Int,
    streamH: Int,
    viewW: Float,
    viewH: Float,
    degrees: Int,
    mirrored: Boolean,
): Matrix {
    val quarterTurned = degrees % HALF_TURN_DEGREES != 0
    val displayedAspect =
        if (quarterTurned) streamH.toFloat() / streamW else streamW.toFloat() / streamH
    val (shownW, shownH) =
        if (displayedAspect >= viewW / viewH) {
            viewW to viewW / displayedAspect
        } else {
            viewH * displayedAspect to viewH
        }
    // Pre-rotation content size: rotation swaps width/height on quarter turns.
    val preW = if (quarterTurned) shownH else shownW
    val preH = if (quarterTurned) shownW else shownH
    val cx = viewW / 2f
    val cy = viewH / 2f
    return Matrix().apply {
        setScale(preW / viewW, preH / viewH, cx, cy)
        if (mirrored) postScale(-1f, 1f, cx, cy)
        if (degrees != 0) postRotate(degrees.toFloat(), cx, cy)
    }
}

/**
 * The stream-buffer region currently visible on screen, given the full display pipeline:
 * buffer stretched to the view, [previewTransformMatrix], then the UI's pinch-zoom/pan
 * (scale around the view center plus translation). Inverse-maps the view rect into buffer
 * coordinates and clips to the buffer. Used to crop photo captures to what the user sees.
 */
@Suppress("LongParameterList") // one value per pipeline stage; a param object would add noise
internal fun visibleStreamRect(
    streamW: Int,
    streamH: Int,
    viewW: Float,
    viewH: Float,
    degrees: Int,
    mirrored: Boolean,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
): android.graphics.Rect {
    val cx = viewW / 2f
    val cy = viewH / 2f
    val pipeline =
        Matrix().apply {
            setScale(viewW / streamW, viewH / streamH)
            postConcat(previewTransformMatrix(streamW, streamH, viewW, viewH, degrees, mirrored))
            postScale(zoom, zoom, cx, cy)
            postTranslate(offsetX, offsetY)
        }
    val inverse = Matrix()
    if (!pipeline.invert(inverse)) return android.graphics.Rect(0, 0, streamW, streamH)
    val visible = android.graphics.RectF(0f, 0f, viewW, viewH)
    inverse.mapRect(visible)
    val left = visible.left.toInt().coerceIn(0, streamW - 1)
    val top = visible.top.toInt().coerceIn(0, streamH - 1)
    val right = visible.right.toInt().coerceIn(left + 1, streamW)
    val bottom = visible.bottom.toInt().coerceIn(top + 1, streamH)
    return android.graphics.Rect(left, top, right, bottom)
}

private const val FULL_TURN_DEGREES = 360

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

        // AUSBC 3.2.7's MultiCameraClient filters out some class-0 composite UVC cameras.
        // Own the USB discovery/permission monitor while retaining its camera streaming engine.
        private var monitor: USBMonitor? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        // 3.2.7 has no CameraUVC subclass; MultiCameraClient.Camera is concrete and UVC-capable.
        private var camera: MultiCameraClient.Camera? = null
        private var pendingDevice: UsbDevice? = null
        private var ctrlBlock: USBMonitor.UsbControlBlock? = null

        // 3.2.7 exposes no render rotation/mirror on the camera, so rotate/mirror are
        // applied as View transforms on the preview surface (see setRotation/setMirror).
        private var previewView: AspectRatioTextureView? = null
        private var layoutListener: android.view.View.OnLayoutChangeListener? = null

        // Retained so captures can bake the orientation the user sees into the saved file
        // (the View transforms above are display-only and invisible to getBitmap()).
        @Volatile private var rotationDegrees: Int = 0

        @Volatile private var mirrored: Boolean = false

        /** Grid luminances of the previous liveness probe, for frame-change detection. */
        private var lastLivenessSamples: IntArray? = null

        // UI pinch-zoom state, mirrored here so photo captures crop to the visible region.
        @Volatile private var zoomFactor: Float = 1f

        @Volatile private var zoomOffsetX: Float = 0f

        @Volatile private var zoomOffsetY: Float = 0f

        // AUSBC 3.2.7's captureImage/captureVideoStart write to a file only after passing
        // CameraUtils.hasStoragePermission() — WRITE_EXTERNAL_STORAGE, which cannot be granted
        // on Android 13+, so those APIs always fail. We instead grab frames straight off the
        // preview TextureView (getBitmap) and encode photos/videos ourselves, via MediaStore.
        @Volatile private var currentSize: Resolution = Resolution(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)

        @Volatile private var availableSizes: List<Resolution> = emptyList()

        @Volatile private var videoRecorder: FrameVideoRecorder? = null

        private fun UsbDevice.displayName(): String = productName?.takeIf { it.isNotBlank() } ?: "USB Camera"

        private fun attachedUsbDeviceNames(): Set<String> =
            (context.getSystemService(Context.USB_SERVICE) as? UsbManager)?.deviceList?.keys ?: emptySet()

        /**
         * Grab the frame currently rendered on the preview surface at the given size, on the
         * main thread. Requesting the stream size (not the view size) avoids a blurry upscale.
         */
        private suspend fun grabBitmap(
            width: Int,
            height: Int,
        ): Bitmap? =
            withContext(Dispatchers.Main.immediate) {
                val view = previewView ?: return@withContext null
                if (!view.isAvailable || width <= 0 || height <= 0) return@withContext null
                view.getBitmap(width, height)
            }

        /** Recognize video interfaces even when the USB device itself is composite class 0. */
        private fun isCameraDevice(device: UsbDevice): Boolean =
            isLikelyUvcDevice(
                device.deviceClass,
                (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass },
            ) || CameraUtils.isFilterDevice(context, device)

        /**
         * Proactively enumerate attached devices. USB broadcasts are not always delivered
         * to apps already open, and the stock AUSBC filtering drops some valid UVC cameras.
         */
        private fun scanAttachedCameras() {
            if (pendingDevice != null) return
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            usbManager.deviceList.values
                .firstOrNull(::isCameraDevice)
                ?.let(::selectCamera)
        }

        private fun selectCamera(device: UsbDevice) {
            if (!isCameraDevice(device)) return
            val current = pendingDevice
            if (current != null) return
            pendingDevice = device
            _events.tryEmit(CameraEvent.Attached(device.displayName()))
            monitor?.requestPermission(device)
        }

        override fun startMonitoring() {
            if (monitor != null) return
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                _events.tryEmit(CameraEvent.NoUsbHost)
                return
            }
            // Use USBMonitor directly: MultiCameraClient's hard-coded isUsbCamera() only
            // checks video-class device descriptors and VIDEO interfaces on class-239
            // devices. Many valid UVC devices report device class 0 instead.
            monitor =
                USBMonitor(
                    ReceiverFlagContext(context),
                    object : USBMonitor.OnDeviceConnectListener {
                        override fun onAttach(device: UsbDevice?) {
                            if (device == null || !isCameraDevice(device)) return
                            mainHandler.post { if (monitor != null) selectCamera(device) }
                        }

                        override fun onDetach(device: UsbDevice?) {
                            device ?: return
                            mainHandler.post {
                                if (pendingDevice?.deviceName != device.deviceName) return@post
                                teardownCamera()
                                ctrlBlock = null
                                pendingDevice = null
                                _events.tryEmit(CameraEvent.Detached)
                                scanAttachedCameras()
                            }
                        }

                        override fun onConnect(
                            device: UsbDevice?,
                            block: USBMonitor.UsbControlBlock?,
                            createNew: Boolean,
                        ) {
                            device ?: return
                            mainHandler.post {
                                if (pendingDevice?.deviceName != device.deviceName) return@post
                                ctrlBlock = block
                                _events.tryEmit(CameraEvent.PermissionGranted(device.displayName()))
                            }
                        }

                        override fun onDisconnect(
                            device: UsbDevice?,
                            block: USBMonitor.UsbControlBlock?,
                        ) {
                            mainHandler.post {
                                // The library may close a cloned control block while the
                                // physical camera remains connected. That is not a detach.
                                if (pendingDevice?.deviceName != device?.deviceName) return@post
                                if (!shouldTreatDisconnectAsDetach(
                                        pendingDevice?.deviceName,
                                        attachedUsbDeviceNames(),
                                    )
                                ) {
                                    return@post
                                }
                                teardownCamera()
                                ctrlBlock = null
                                pendingDevice = null
                                _events.tryEmit(CameraEvent.Detached)
                                scanAttachedCameras()
                            }
                        }

                        override fun onCancel(device: UsbDevice?) {
                            device ?: return
                            mainHandler.post {
                                if (pendingDevice?.deviceName != device.deviceName) return@post
                                ctrlBlock = null
                                _events.tryEmit(CameraEvent.PermissionDenied(device.displayName()))
                            }
                        }
                    },
                )
            monitor?.register()
            scanAttachedCameras()
        }

        override fun stopMonitoring() {
            teardownCamera()
            val oldMonitor = monitor
            monitor = null
            oldMonitor?.unregister()
            oldMonitor?.destroy()
            pendingDevice = null
            ctrlBlock = null
        }

        override fun requestPermission() {
            if (monitor == null) return
            val device = pendingDevice
            if (device == null) {
                scanAttachedCameras()
            } else if (device.deviceName in attachedUsbDeviceNames()) {
                monitor?.requestPermission(device)
            } else {
                pendingDevice = null
                ctrlBlock = null
                scanAttachedCameras()
            }
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
            // computed from the view dimensions, which change on rotation. Single listener: a
            // re-open on the same retained view must not stack another copy.
            layoutListener?.let(view::removeOnLayoutChangeListener)
            val listener =
                android.view.View.OnLayoutChangeListener { _, left, top, right, bottom, oldL, oldT, oldR, oldB ->
                    if (right - left != oldR - oldL || bottom - top != oldB - oldT) {
                        applyPreviewTransform(currentSize)
                    }
                }
            layoutListener = listener
            view.addOnLayoutChangeListener(listener)
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
            // The AndroidView factory hands the view over BEFORE its SurfaceTexture exists;
            // AUSBC's openCameraInternal calls setPreviewTexture(view.surfaceTexture) without a
            // null check and kills the process ("surfaceTexture must not be null") — hit on the
            // settings/gallery return path, where the open races surface creation. Open now only
            // if the surface is live, otherwise when it becomes available.
            if (view.isAvailable) {
                cam.openCamera(view, request)
            } else {
                view.surfaceTextureListener =
                    object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            view.surfaceTextureListener = null
                            if (camera == cam) cam.openCamera(view, request)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture) = true

                        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
                    }
                // The surface may have appeared between the isAvailable check and the listener
                // registration; don't strand the open waiting for a callback that already fired.
                if (view.isAvailable) {
                    view.surfaceTextureListener = null
                    cam.openCamera(view, request)
                }
            }
            camera = cam
        }

        override fun close() = teardownCamera()

        private fun teardownCamera() {
            val recorder = videoRecorder
            videoRecorder = null
            if (recorder != null) {
                // Torn down mid-record (detach, navigation): finalize and surface what was
                // captured so far instead of silently discarding it.
                val file = runCatching { recorder.stop() }.getOrNull()
                _events.tryEmit(
                    CameraEvent.RecordingFinalized(file?.takeIf { it.exists() && it.length() > 0 }),
                )
            }
            runCatching { camera?.closeCamera() }
            camera = null
            layoutListener?.let { previewView?.removeOnLayoutChangeListener(it) }
            layoutListener = null
            previewView = null
            lastLivenessSamples = null
        }

        override suspend fun setResolution(resolution: Resolution) {
            withContext(Dispatchers.IO) { setResolutionBlocking(resolution) }
        }

        override suspend fun retryWithYuyv(resolution: Resolution): Boolean =
            withContext(Dispatchers.IO) {
                setResolutionBlocking(resolution, forceYuyv = true)
            }

        /**
         * Some UVC cameras accept MJPEG configuration but subsequently never deliver frames.
         * The ViewModel detects the dead stream and requests one forced YUYV retry before
         * reverting to the last working resolution. Forced retries do not emit an OpenFailed
         * event; the ViewModel owns the retry/revert flow.
         */
        private fun setResolutionBlocking(
            resolution: Resolution,
            forceYuyv: Boolean = false,
        ): Boolean {
            val uvc = camera?.let(::uvcCameraOf) ?: return false
            val device = pendingDevice
            val surface = previewView?.surfaceTexture
            return runCatching {
                uvc.stopPreview()
                val mjpegConfigured =
                    !forceYuyv &&
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
                if (!mjpegConfigured) {
                    uvc.setPreviewSize(
                        resolution.width,
                        resolution.height,
                        UVCCamera.DEFAULT_PREVIEW_MIN_FPS,
                        UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
                        UVCCamera.FRAME_FORMAT_YUYV,
                        PREVIEW_BANDWIDTH,
                    )
                }
                if (surface != null) uvc.setPreviewTexture(surface)
                uvc.startPreview()
                lastLivenessSamples = null
                currentSize = resolution
                applyPreviewTransform(resolution)
                if (device != null) {
                    _events.tryEmit(CameraEvent.Opened(device.displayName(), resolution, availableSizes))
                }
                true
            }.getOrElse {
                if (!forceYuyv) {
                    _events.tryEmit(CameraEvent.OpenFailed("resolution change failed"))
                }
                false
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
            // A tiny scaled grab is enough for the luminance grid and far cheaper than a full frame.
            val bitmap = grabBitmap(LIVENESS_SAMPLES, LIVENESS_SAMPLES) ?: return false
            val samples = IntArray(LIVENESS_SAMPLES * LIVENESS_SAMPLES)
            for (y in 0 until LIVENESS_SAMPLES) {
                for (x in 0 until LIVENESS_SAMPLES) {
                    samples[y * LIVENESS_SAMPLES + x] = luminanceOf(bitmap.getPixel(x, y))
                }
            }
            bitmap.recycle()
            val previous = lastLivenessSamples
            lastLivenessSamples = samples
            return samplesIndicateLive(previous, samples)
        }

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
                view.setTransform(
                    previewTransformMatrix(size.width, size.height, vw, vh, rotationDegrees, mirrored),
                )
            }
        }

        override fun setRotation(degrees: Int) {
            // 3.2.7 has no render-side rotation; rotate inside the content transform, where
            // the letterbox math accounts for the swapped aspect on 90°/270°.
            rotationDegrees = degrees
            applyPreviewTransform(currentSize)
        }

        override fun setMirror(mirrored: Boolean) {
            // 3.2.7 has no render-side flip; mirror inside the content transform.
            this.mirrored = mirrored
            applyPreviewTransform(currentSize)
        }

        override fun setZoom(
            zoom: Float,
            offsetX: Float,
            offsetY: Float,
        ) {
            // Display-side zoom lives in the Compose layer; retained here only so photo
            // captures can crop to the visible region.
            zoomFactor = zoom
            zoomOffsetX = offsetX
            zoomOffsetY = offsetY
        }

        override fun setBrightness(percent: Int) {
            val uvc = camera?.let(::uvcCameraOf) ?: return
            runCatching { uvc.setBrightness(percent.coerceIn(0, PERCENT_MAX)) }
        }

        override fun setContrast(percent: Int) {
            val uvc = camera?.let(::uvcCameraOf) ?: return
            runCatching { uvc.setContrast(percent.coerceIn(0, PERCENT_MAX)) }
        }

        override fun getBrightness(): Int? {
            val uvc = camera?.let(::uvcCameraOf) ?: return null
            return runCatching { uvc.brightness }.getOrNull()
        }

        override fun getContrast(): Int? {
            val uvc = camera?.let(::uvcCameraOf) ?: return null
            return runCatching { uvc.contrast }.getOrNull()
        }

        override suspend fun capturePhoto(target: File): Result<File> {
            val size = currentSize
            val raw =
                grabBitmap(size.width, size.height)
                    ?: return Result.failure(IllegalStateException("no preview frame yet"))
            val cropped = cropToVisible(raw, size)
            return withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = bakeOrientation(cropped, rotationDegrees, mirrored)
                    try {
                        FileOutputStream(target).use { out ->
                            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                            check(ok) { "JPEG compression failed" }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    target
                }
            }
        }

        /** Crop a grabbed frame to the pinch-zoomed visible region; full frame at 1× zoom. */
        private fun cropToVisible(
            raw: Bitmap,
            size: Resolution,
        ): Bitmap {
            val vw = previewView?.width?.toFloat() ?: 0f
            val vh = previewView?.height?.toFloat() ?: 0f
            val crop =
                if (zoomFactor <= 1f || vw <= 0f || vh <= 0f) {
                    null
                } else {
                    visibleStreamRect(
                        size.width, size.height, vw, vh,
                        rotationDegrees, mirrored, zoomFactor, zoomOffsetX, zoomOffsetY,
                    ).takeIf { it.width() < size.width || it.height() < size.height }
                }
            if (crop == null) return raw
            val cropped = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height())
            if (cropped != raw) raw.recycle()
            return cropped
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
                            // Rotation rides as a muxer orientation hint (players rotate on
                            // playback); mirror is baked per-frame. Fixed at record start.
                            orientationHintDegrees = rotationDegrees,
                            mirrored = mirrored,
                            // Crop each frame to the pinch-zoomed visible region (live-follows
                            // zoom); the recorder letterboxes non-matching aspects.
                            frameProvider = {
                                view.takeIf { it.isAvailable }
                                    ?.getBitmap(w, h)
                                    ?.let { cropToVisible(it, Resolution(w, h)) }
                            },
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
            const val PERCENT_MAX = 100
        }
    }
