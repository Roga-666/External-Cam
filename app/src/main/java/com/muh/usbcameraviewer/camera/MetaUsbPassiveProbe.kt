package com.muh.usbcameraviewer.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val META_VENDOR_ID = 0x2833
private const val RETAIL_DEMO_PRODUCT_ID = 0x00A0
private const val BULK_IN_ADDRESS = 0x81
private const val BULK_OUT_ADDRESS = 0x01
private const val TEST_DURATION_MS = 3_000L
private const val TRANSFER_TIMEOUT_MS = 250
private const val MAX_CAPTURE_BYTES = 32 * 1024
private const val TRANSFER_BUFFER_BYTES = 4 * 1024

/** Only the reported retail-demo interface is eligible; never probe unrelated USB hardware. */
internal fun matchesMetaRetailTransport(
    vendorId: Int,
    productId: Int,
    interfaceClass: Int,
    interfaceSubclass: Int,
    interfaceProtocol: Int,
): Boolean =
    vendorId == META_VENDOR_ID &&
        productId == RETAIL_DEMO_PRODUCT_ID &&
        interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
        interfaceSubclass == 1 &&
        interfaceProtocol == 1

private fun UsbDevice.atcInterface(): UsbInterface? =
    (0 until interfaceCount)
        .map(::getInterface)
        .firstOrNull { intf ->
            matchesMetaRetailTransport(
                vendorId,
                productId,
                intf.interfaceClass,
                intf.interfaceSubclass,
                intf.interfaceProtocol,
            ) &&
                (0 until intf.endpointCount).any { index ->
                    val endpoint = intf.getEndpoint(index)
                    endpoint.address == BULK_IN_ADDRESS &&
                        endpoint.direction == UsbConstants.USB_DIR_IN &&
                        endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                } &&
                (0 until intf.endpointCount).any { index ->
                    val endpoint = intf.getEndpoint(index)
                    endpoint.address == BULK_OUT_ADDRESS &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT &&
                        endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                }
        }

sealed interface MetaProbeState {
    data object Idle : MetaProbeState

    data object WaitingPermission : MetaProbeState

    data object Running : MetaProbeState

    data object MissingDevice : MetaProbeState

    data object PermissionDenied : MetaProbeState

    data object OpenFailed : MetaProbeState

    data object NoIncomingData : MetaProbeState

    data class IncomingData(val bytes: Int, val successfulReads: Int) : MetaProbeState
}

/**
 * A deliberately narrow diagnostic, NOT an ATC driver:
 * - no controlTransfer(), no bulk OUT, no configuration or firmware commands
 * - only an explicitly tapped test of the single observed 2833:00A0 ATC interface
 * - claimInterface(force=false), bounded bulk IN reads, always close/release
 * - payload is discarded in RAM; no serial, images, raw frames, or bytes are logged/saved
 *
 * A silent result cannot rule out video: proprietary transports may require initialization.
 */
class MetaUsbPassiveProbe(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onState: (MetaProbeState) -> Unit,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${context.packageName}.ATC_READ_ONLY_PROBE_PERMISSION"
    private var activeJob: Job? = null
    private var awaitingPermission = false

    private val permissionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != permissionAction || !awaitingPermission) return
                awaitingPermission = false
                val device = findDevice()
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                    device != null &&
                    usbManager.hasPermission(device)
                ) {
                    start(device)
                } else {
                    onState(MetaProbeState.PermissionDenied)
                }
            }
        }

    fun register() {
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            IntentFilter(permissionAction),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun close() {
        awaitingPermission = false
        activeJob?.cancel()
        context.unregisterReceiver(permissionReceiver)
    }

    fun startOnTap() {
        if (activeJob?.isActive == true || awaitingPermission) return
        val device = findDevice()
        if (device == null || device.atcInterface() == null) {
            onState(MetaProbeState.MissingDevice)
            return
        }
        if (usbManager.hasPermission(device)) {
            start(device)
            return
        }
        awaitingPermission = true
        onState(MetaProbeState.WaitingPermission)
        // Android's USB permission API officially supports immutable PendingIntents.
        // A mutable PendingIntent with a package-only (non-component) Intent is rejected
        // on Android 14+; keep the response app-scoped and immutable instead.
        val permissionIntent =
            PendingIntent.getBroadcast(
                context,
                4381,
                Intent(permissionAction).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        try {
            usbManager.requestPermission(device, permissionIntent)
        } catch (_: SecurityException) {
            awaitingPermission = false
            onState(MetaProbeState.PermissionDenied)
        } catch (_: IllegalArgumentException) {
            awaitingPermission = false
            onState(MetaProbeState.OpenFailed)
        }
    }

    private fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == META_VENDOR_ID && it.productId == RETAIL_DEMO_PRODUCT_ID
        }

    private fun start(device: UsbDevice) {
        onState(MetaProbeState.Running)
        activeJob =
            scope.launch {
                val result = withContext(Dispatchers.IO) { readOnlyProbe(device) }
                onState(result)
            }
    }

    private fun readOnlyProbe(device: UsbDevice): MetaProbeState {
        val intf = device.atcInterface() ?: return MetaProbeState.MissingDevice
        if (!usbManager.hasPermission(device)) return MetaProbeState.PermissionDenied
        val connection = usbManager.openDevice(device) ?: return MetaProbeState.OpenFailed
        val inEndpoint =
            (0 until intf.endpointCount)
                .map(intf::getEndpoint)
                .first { it.address == BULK_IN_ADDRESS }
        var claimed = false
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        try {
            // Never detach a driver, never take control of a different interface.
            if (!connection.claimInterface(intf, false)) return MetaProbeState.OpenFailed
            claimed = true
            var totalBytes = 0
            var reads = 0
            val deadline = SystemClock.elapsedRealtime() + TEST_DURATION_MS
            while (SystemClock.elapsedRealtime() < deadline && totalBytes < MAX_CAPTURE_BYTES) {
                if (!usbManager.deviceList.containsKey(device.deviceName)) return MetaProbeState.MissingDevice
                val length = minOf(buffer.size, MAX_CAPTURE_BYTES - totalBytes)
                val received = connection.bulkTransfer(inEndpoint, buffer, length, TRANSFER_TIMEOUT_MS)
                if (received > 0) {
                    totalBytes += received
                    reads++
                }
            }
            return if (totalBytes > 0) {
                MetaProbeState.IncomingData(totalBytes, reads)
            } else {
                MetaProbeState.NoIncomingData
            }
        } catch (_: SecurityException) {
            return MetaProbeState.PermissionDenied
        } catch (_: RuntimeException) {
            return MetaProbeState.OpenFailed
        } finally {
            buffer.fill(0)
            if (claimed) connection.releaseInterface(intf)
            connection.close()
        }
    }
}
