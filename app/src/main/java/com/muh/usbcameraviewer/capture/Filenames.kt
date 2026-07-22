package com.muh.usbcameraviewer.capture

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Filenames {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun photo(prefix: String, instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        "${prefix}_${FORMAT.format(instant.atZone(zone))}.jpg"

    fun video(prefix: String, instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        "${prefix}_${FORMAT.format(instant.atZone(zone))}.mp4"
}
