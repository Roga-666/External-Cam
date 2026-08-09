package com.muh.usbcameraviewer.settings

import com.muh.usbcameraviewer.camera.Resolution

// Top-level (not in VideoQuality's companion object): enum constants are initialized before
// their companion object, so a companion-object constant isn't visible yet at this point.
private const val LOW_BIT_RATE_BPS = 2_000_000
private const val MEDIUM_BIT_RATE_BPS = 6_000_000
private const val HIGH_BIT_RATE_BPS = 12_000_000

enum class VideoQuality(val bitRate: Int) {
    LOW(LOW_BIT_RATE_BPS),
    MEDIUM(MEDIUM_BIT_RATE_BPS),
    HIGH(HIGH_BIT_RATE_BPS),
}

enum class ResolutionPolicy { REMEMBER_LAST, ALWAYS_ASK }

data class AppSettings(
    val resolutionPolicy: ResolutionPolicy = ResolutionPolicy.REMEMBER_LAST,
    val lastResolution: Resolution? = null,
    val videoQuality: VideoQuality = VideoQuality.MEDIUM,
    val recordAudio: Boolean = false,
    val filenamePrefix: String = "UCV",
    val keepScreenOn: Boolean = true,
    val brightnessPercent: Int? = null,
    val contrastPercent: Int? = null,
)
