package com.muh.usbcameraviewer.capture

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FilenamesTest {

    private val instant = Instant.parse("2026-07-22T14:05:09Z")
    private val zone = ZoneId.of("UTC")

    @Test
    fun `photo name uses prefix and timestamp`() {
        assertEquals("UCV_20260722_140509.jpg", Filenames.photo("UCV", instant, zone))
    }

    @Test
    fun `video name uses prefix and timestamp`() {
        assertEquals("UCV_20260722_140509.mp4", Filenames.video("UCV", instant, zone))
    }

    @Test
    fun `custom prefix respected`() {
        assertEquals("scope_20260722_140509.jpg", Filenames.photo("scope", instant, zone))
    }
}
