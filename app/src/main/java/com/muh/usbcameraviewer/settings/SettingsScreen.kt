package com.muh.usbcameraviewer.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muh.usbcameraviewer.BuildConfig
import com.muh.usbcameraviewer.R
import com.muh.usbcameraviewer.camera.connectedUsbDevices

// Single Compose screen laying out every settings section; splitting it would scatter
// closely related UI without reducing complexity, so LongMethod is suppressed here.
@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var usbDevices by remember { mutableStateOf(connectedUsbDevices(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Put diagnostics first so they are immediately available after using
            // the no-camera USB diagnostics shortcut.
            SectionLabel(stringResource(R.string.settings_usb_diagnostics))
            Text(
                stringResource(R.string.settings_usb_diagnostics_hint),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = { usbDevices = connectedUsbDevices(context) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.settings_usb_refresh))
            }
            if (usbDevices.isEmpty()) {
                Text(
                    stringResource(R.string.settings_usb_empty),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            usbDevices.forEach { device ->
                val uvcStatus =
                    if (device.isLikelyUvc) {
                        stringResource(R.string.settings_usb_uvc_found)
                    } else {
                        stringResource(R.string.settings_usb_uvc_not_found)
                    }
                ListItem(
                    headlineContent = { Text(device.displayName) },
                    supportingContent = {
                        Text(stringResource(R.string.settings_usb_device_details, device.usbId, uvcStatus))
                    },
                )
            }
            HorizontalDivider()

            SectionLabel(stringResource(R.string.settings_resolution_policy))
            RadioRow(
                label = stringResource(R.string.settings_resolution_remember),
                selected = settings.resolutionPolicy == ResolutionPolicy.REMEMBER_LAST,
                onSelect = { viewModel.setResolutionPolicy(ResolutionPolicy.REMEMBER_LAST) },
            )
            RadioRow(
                label = stringResource(R.string.settings_resolution_ask),
                selected = settings.resolutionPolicy == ResolutionPolicy.ALWAYS_ASK,
                onSelect = { viewModel.setResolutionPolicy(ResolutionPolicy.ALWAYS_ASK) },
            )
            HorizontalDivider()

            SectionLabel(stringResource(R.string.settings_video_quality))
            RadioRow(
                stringResource(R.string.settings_quality_low),
                settings.videoQuality == VideoQuality.LOW,
            ) { viewModel.setVideoQuality(VideoQuality.LOW) }
            RadioRow(
                stringResource(R.string.settings_quality_medium),
                settings.videoQuality == VideoQuality.MEDIUM,
            ) { viewModel.setVideoQuality(VideoQuality.MEDIUM) }
            RadioRow(
                stringResource(R.string.settings_quality_high),
                settings.videoQuality == VideoQuality.HIGH,
            ) { viewModel.setVideoQuality(VideoQuality.HIGH) }
            HorizontalDivider()

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_record_audio)) },
                supportingContent = { Text(stringResource(R.string.settings_record_audio_hint)) },
                trailingContent = {
                    Switch(checked = settings.recordAudio, onCheckedChange = viewModel::setRecordAudio)
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_keep_screen_on)) },
                trailingContent = {
                    Switch(checked = settings.keepScreenOn, onCheckedChange = viewModel::setKeepScreenOn)
                },
            )
            HorizontalDivider()

            var prefix by remember(settings.filenamePrefix) { mutableStateOf(settings.filenamePrefix) }
            OutlinedTextField(
                value = prefix,
                onValueChange = { prefix = it },
                label = { Text(stringResource(R.string.settings_filename_prefix)) },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
            )
            LaunchedEffect(prefix) {
                kotlinx.coroutines.delay(400) // debounce
                if (prefix != settings.filenamePrefix) viewModel.setFilenamePrefix(prefix)
            }
            HorizontalDivider()

            SectionLabel(stringResource(R.string.settings_about))
            Text(
                stringResource(R.string.settings_about_body, BuildConfig.VERSION_NAME),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_licenses)) },
                modifier = Modifier.clickable(onClick = onOpenLicenses),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
