package com.muh.usbcameraviewer.camera

data class Resolution(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x$height"
}

enum class CaptureMode { PHOTO, VIDEO }

sealed interface CameraError {
    data object NoUsbHostSupport : CameraError

    data object PermissionDenied : CameraError

    data class OpenFailed(val reason: String) : CameraError

    data object UnsupportedDevice : CameraError

    data class RecordingFailed(val reason: String) : CameraError
}

sealed interface CameraUiState {
    data object NoDevice : CameraUiState

    data class PermissionNeeded(val deviceName: String) : CameraUiState

    /**
     * Device attached, awaiting the system USB-permission decision. Shows the same
     * spinner as [Connecting] but must NOT compose the preview surface: opening the
     * camera before [CameraEvent.PermissionGranted] delivers a control block would
     * fire a bogus OpenFailed.
     */
    data class WaitingPermission(val deviceName: String) : CameraUiState

    data class Connecting(val deviceName: String) : CameraUiState

    data class Streaming(
        val deviceName: String,
        val resolution: Resolution,
        val availableResolutions: List<Resolution>,
        val rotationDegrees: Int = 0,
        val mirrored: Boolean = false,
        val captureMode: CaptureMode = CaptureMode.PHOTO,
        val isRecording: Boolean = false,
        val recordingSeconds: Long = 0,
        val lastCaptureUri: String? = null,
    ) : CameraUiState

    data class Error(val error: CameraError) : CameraUiState
}

sealed interface CameraEvent {
    data class Attached(val deviceName: String) : CameraEvent

    data object Detached : CameraEvent

    /** This device has no USB host (OTG) support; unrecoverable. */
    data object NoUsbHost : CameraEvent

    data class PermissionGranted(val deviceName: String) : CameraEvent

    data class PermissionDenied(val deviceName: String) : CameraEvent

    data class Opened(
        val deviceName: String,
        val resolution: Resolution,
        val availableResolutions: List<Resolution>,
    ) : CameraEvent

    data class OpenFailed(val reason: String) : CameraEvent
}
