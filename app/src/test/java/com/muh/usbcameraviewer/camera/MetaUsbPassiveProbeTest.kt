package com.muh.usbcameraviewer.camera

import android.hardware.usb.UsbConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaUsbPassiveProbeTest {
    @Test
    fun recognizesReportedAtcDemoInterface() {
        assertTrue(matchesMetaRetailTransport(0x2833, 0x00A0, UsbConstants.USB_CLASS_VENDOR_SPEC, 1, 1))
    }

    @Test
    fun refusesOtherDevicesAndProtocols() {
        assertFalse(matchesMetaRetailTransport(0x2833, 0x00A1, UsbConstants.USB_CLASS_VENDOR_SPEC, 1, 1))
        assertFalse(matchesMetaRetailTransport(0x2833, 0x00A0, UsbConstants.USB_CLASS_VIDEO, 1, 1))
        assertFalse(matchesMetaRetailTransport(0x2833, 0x00A0, UsbConstants.USB_CLASS_VENDOR_SPEC, 0, 1))
        assertFalse(matchesMetaRetailTransport(0x2833, 0x00A0, UsbConstants.USB_CLASS_VENDOR_SPEC, 1, 0))
        assertFalse(matchesMetaRetailTransport(0x1234, 0x00A0, UsbConstants.USB_CLASS_VENDOR_SPEC, 1, 1))
    }
}
