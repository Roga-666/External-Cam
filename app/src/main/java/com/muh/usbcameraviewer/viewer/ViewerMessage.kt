package com.muh.usbcameraviewer.viewer

import com.muh.usbcameraviewer.camera.Resolution

/**
 * One-shot user-facing messages emitted by [ViewerViewModel]. The UI layer resolves them to
 * localized strings; the ViewModel stays free of hardcoded English.
 */
sealed interface ViewerMessage {
    data object LowStorage : ViewerMessage

    data object CameraPermissionDenied : ViewerMessage

    data class PhotoFailed(val reason: String?) : ViewerMessage

    data class RecordingFailed(val reason: String?) : ViewerMessage

    /** A recording interrupted by detach/navigation was salvaged and saved. */
    data object RecordingSaved : ViewerMessage

    data class ResolutionUnsupported(val resolution: Resolution) : ViewerMessage
}
