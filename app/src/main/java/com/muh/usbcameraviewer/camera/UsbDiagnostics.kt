package com.muh.usbcameraviewer.camera

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import java.util.Locale

/**
 * A descriptor-only view of attached USB hardware. Does not request permission or open a device,
 * so it is safe to display even when a proprietary, non-UVC camera is attached.
 */
data class UsbDeviceReport(
    val displayName: String,
    val vendorId: Int,
    val productId: Int,
    val isLikelyUvc: Boolean,
) {
    val usbId: String
        get() = String.format(Locale.US, "VID %04X / PID %04X", vendorId, productId)
}

/**
 * Composite devices commonly use device class 0 or 239 while their VIDEO interface is class 14.
 * Checking the interfaces avoids incorrectly rejecting those devices in diagnostics.
 */
internal fun isLikelyUvcDevice(
    deviceClass: Int,
    interfaceClasses: Collection<Int>,
): Boolean =
    deviceClass == UsbConstants.USB_CLASS_VIDEO ||
        interfaceClasses.any { it == UsbConstants.USB_CLASS_VIDEO }

fun connectedUsbDevices(context: Context): List<UsbDeviceReport> {
    val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
    return manager.deviceList.values
        .map { device ->
            val classes =
                (0 until device.interfaceCount).map { index ->
                    device.getInterface(index).interfaceClass
                }
            UsbDeviceReport(
                displayName =
                    runCatching { device.productName }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "USB device",
                vendorId = device.vendorId,
                productId = device.productId,
                isLikelyUvc = isLikelyUvcDevice(device.deviceClass, classes),
            )
        }
        .sortedWith(compareBy({ !it.isLikelyUvc }, { it.displayName }))
}
