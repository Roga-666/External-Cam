package com.muh.usbcameraviewer.camera

import java.io.File
import kotlinx.coroutines.flow.SharedFlow

/**
 * Library-agnostic UVC camera facade. The only implementation talking to
 * AUSBC is AusbcCameraController; tests use FakeCameraController.
 *
 * `preview` is Any to keep this interface (and unit tests) free of Android
 * View types; the real implementation requires an AspectRatioTextureView.
 */
interface CameraController {
    val events: SharedFlow<CameraEvent>

    /** Register USB attach/detach monitoring. Idempotent. */
    fun startMonitoring()

    /** Unregister monitoring and release everything. */
    fun stopMonitoring()

    /** Re-trigger the system USB permission dialog for the pending device. */
    fun requestPermission()

    /** Open the pending device rendering into [preview]; null preferred = device default. */
    fun open(preview: Any, preferred: Resolution?)

    /** Close the camera stream, keep monitoring. */
    fun close()

    fun setResolution(resolution: Resolution)
    fun setRotation(degrees: Int)
    fun setMirror(mirrored: Boolean)

    /** Capture a JPEG into [target]. */
    suspend fun capturePhoto(target: File): Result<File>

    /** Start MP4 recording into [target]. */
    suspend fun startRecording(target: File, withAudio: Boolean, bitRate: Int): Result<Unit>

    /** Stop recording, return finalized file. Safe to call after detach. */
    suspend fun stopRecording(): Result<File>
}
