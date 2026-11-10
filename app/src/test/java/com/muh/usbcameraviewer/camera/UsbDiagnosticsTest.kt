package com.muh.usbcameraviewer.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDiagnosticsTest {
    @Test
    fun cameraClassDeviceIsRecognized() {
        assertTrue(isLikelyUvcDevice(14, emptyList()))
    }

    @Test
    fun compositeCameraWithVideoInterfaceIsRecognized() {
        assertTrue(isLikelyUvcDevice(0, listOf(1, 14)))
        assertTrue(isLikelyUvcDevice(239, listOf(14, 1)))
    }

    @Test
    fun unrelatedUsbDeviceIsNotMarkedAsVideo() {
        assertFalse(isLikelyUvcDevice(0, listOf(8, 3)))
        assertFalse(isLikelyUvcDevice(239, listOf(1, 3)))
    }
}
