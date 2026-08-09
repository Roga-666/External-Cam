package com.muh.usbcameraviewer.camera

import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * Library-agnostic UVC camera facade. The only implementation talking to
 * AUSBC is AusbcCameraController; tests use FakeCameraController.
 *
 * `preview` is Any to keep this interface (and unit tests) free of Android
 * View types; the real implementation requires an AspectRatioTextureView.
 */
@Suppress("TooManyFunctions") // one cohesive facade over the whole camera lifecycle + controls
interface CameraController {
    val events: SharedFlow<CameraEvent>

    /** Register USB attach/detach monitoring. Idempotent. */
    fun startMonitoring()

    /** Unregister monitoring and release everything. */
    fun stopMonitoring()

    /** Re-trigger the system USB permission dialog for the pending device. */
    fun requestPermission()

    /** Open the pending device rendering into [preview]; null preferred = device default. */
    fun open(
        preview: Any,
        preferred: Resolution?,
    )

    /** Close the camera stream, keep monitoring. */
    fun close()

    /** Change the preview resolution (implementations may restart the stream). Does its own IO. */
    suspend fun setResolution(resolution: Resolution)

    fun setRotation(degrees: Int)

    fun setMirror(mirrored: Boolean)

    /** Set image brightness as a 0–100 percentage. No-op when the stream is not open. */
    fun setBrightness(percent: Int)

    /** Set image contrast as a 0–100 percentage. No-op when the stream is not open. */
    fun setContrast(percent: Int)

    /** Current brightness/contrast as 0–100 percentages, or null when unavailable. */
    fun getBrightness(): Int?

    fun getContrast(): Int?

    /** Capture a JPEG into [target]. */
    suspend fun capturePhoto(target: File): Result<File>

    /** Start MP4 recording into [target]. */
    suspend fun startRecording(
        target: File,
        withAudio: Boolean,
        bitRate: Int,
    ): Result<Unit>

    /** Stop recording, return finalized file. Safe to call after detach. */
    suspend fun stopRecording(): Result<File>

    /**
     * True if the preview is delivering frames (a bright pixel exists — endoscope LEDs make any
     * live frame bright). Used after a resolution change to detect a mode the camera advertises but
     * cannot actually stream (delivers a black frame), so the caller can revert.
     */
    suspend fun isPreviewLive(): Boolean
}
