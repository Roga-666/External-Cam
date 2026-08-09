package com.muh.usbcameraviewer.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectDetachTest {
    private val ourDevice = "/dev/bus/usb/001/002"
    private val otherDevice = "/dev/bus/usb/001/003"

    @Test
    fun `disconnect while device still attached is self-inflicted and ignored`() {
        assertFalse(shouldTreatDisconnectAsDetach(ourDevice, setOf(ourDevice)))
    }

    @Test
    fun `disconnect after real unplug is a detach`() {
        assertTrue(shouldTreatDisconnectAsDetach(ourDevice, emptySet()))
    }

    @Test
    fun `disconnect with no pending device is a detach`() {
        assertTrue(shouldTreatDisconnectAsDetach(null, setOf(ourDevice)))
    }

    @Test
    fun `another device's disconnect does not detach ours`() {
        assertFalse(shouldTreatDisconnectAsDetach(ourDevice, setOf(ourDevice, otherDevice)))
    }
}
