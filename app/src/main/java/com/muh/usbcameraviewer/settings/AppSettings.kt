package com.muh.usbcameraviewer.settings

import com.muh.usbcameraviewer.camera.Resolution

enum class VideoQuality(val bitRate: Int) {
    LOW(2_000_000),
    MEDIUM(6_000_000),
    HIGH(12_000_000),
}

enum class ResolutionPolicy { REMEMBER_LAST, ALWAYS_ASK }

data class AppSettings(
    val resolutionPolicy: ResolutionPolicy = ResolutionPolicy.REMEMBER_LAST,
    val lastResolution: Resolution? = null,
    val videoQuality: VideoQuality = VideoQuality.MEDIUM,
    val recordAudio: Boolean = false,
    val filenamePrefix: String = "UCV",
    val keepScreenOn: Boolean = true,
)
