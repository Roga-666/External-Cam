package com.muh.usbcameraviewer.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.muh.usbcameraviewer.R
import com.muh.usbcameraviewer.camera.MetaProbeState
import com.muh.usbcameraviewer.camera.MetaUsbPassiveProbe

/** Visible only for the known Meta retail demo device in the USB diagnostics list. */
@Composable
fun MetaUsbProbePanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<MetaProbeState>(MetaProbeState.Idle) }
    val probe = remember(context, scope) { MetaUsbPassiveProbe(context, scope) { state = it } }

    DisposableEffect(probe) {
        probe.register()
        onDispose { probe.close() }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.meta_probe_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.meta_probe_hint),
            Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = probe::startOnTap,
            enabled = state !is MetaProbeState.Running && state !is MetaProbeState.WaitingPermission,
        ) {
            Text(stringResource(R.string.meta_probe_button))
        }
        val result =
            when (val current = state) {
                MetaProbeState.Idle -> null
                MetaProbeState.WaitingPermission -> stringResource(R.string.meta_probe_waiting)
                MetaProbeState.Running -> stringResource(R.string.meta_probe_running)
                MetaProbeState.MissingDevice -> stringResource(R.string.meta_probe_missing)
                MetaProbeState.PermissionDenied -> stringResource(R.string.meta_probe_denied)
                MetaProbeState.OpenFailed -> stringResource(R.string.meta_probe_error)
                MetaProbeState.NoIncomingData -> stringResource(R.string.meta_probe_silent)
                is MetaProbeState.IncomingData ->
                    stringResource(R.string.meta_probe_received, current.bytes, current.successfulReads)
            }
        if (result != null) {
            Text(
                result,
                Modifier.padding(top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
