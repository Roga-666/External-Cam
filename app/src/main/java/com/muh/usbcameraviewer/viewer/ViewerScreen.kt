// ViewerCallbacks is intentionally kept in this file alongside the ViewerScreen it configures,
// rather than split into its own file, since the two are never used independently.
@file:Suppress("MatchingDeclarationName")

package com.muh.usbcameraviewer.viewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.muh.usbcameraviewer.R
import com.muh.usbcameraviewer.camera.CameraError
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.CaptureMode
import com.muh.usbcameraviewer.camera.Resolution

data class ViewerCallbacks(
    val onPreviewReady: (Any) -> Unit = {},
    val onPermissionRetry: () -> Unit = {},
    val onRetryAfterError: () -> Unit = {},
    val onSelectResolution: (Resolution) -> Unit = {},
    val onRotate: () -> Unit = {},
    val onToggleMirror: () -> Unit = {},
    val onShutter: () -> Unit = {},
    val onToggleCaptureMode: () -> Unit = {},
    val onOpenGallery: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val recordAudioEnabled: Boolean = false,
    val autoShowResolutionPicker: Boolean = false,
    val previewFactory: (android.content.Context) -> android.view.View = { TextureView(it) },
)

@Composable
fun ViewerScreen(
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recordAudioEnabled by viewModel.recordAudioEnabled.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val autoShowResolutionPicker by viewModel.autoShowResolutionPicker.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val view = LocalView.current
    DisposableEffect(keepScreenOn, state is CameraUiState.Streaming) {
        view.keepScreenOn = keepScreenOn && state is CameraUiState.Streaming
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize()) {
        ViewerContent(
            state = state,
            callbacks =
                ViewerCallbacks(
                    onPreviewReady = viewModel::onPreviewReady,
                    onPermissionRetry = viewModel::onPermissionRetry,
                    onRetryAfterError = viewModel::onRetryAfterError,
                    onSelectResolution = viewModel::onSelectResolution,
                    onRotate = viewModel::onRotate,
                    onToggleMirror = viewModel::onToggleMirror,
                    onShutter = viewModel::onShutter,
                    onToggleCaptureMode = viewModel::onToggleCaptureMode,
                    onOpenGallery = onOpenGallery,
                    onOpenSettings = onOpenSettings,
                    recordAudioEnabled = recordAudioEnabled,
                    autoShowResolutionPicker = autoShowResolutionPicker,
                    previewFactory = viewModel.previewFactory,
                ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun ViewerContent(
    state: CameraUiState,
    callbacks: ViewerCallbacks,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        // Single call site: the preview surface must survive the Connecting -> Streaming
        // transition unchanged, otherwise Compose disposes/recreates the AndroidView and
        // controller.open() gets invoked twice against a dead view.
        if (state is CameraUiState.Connecting || state is CameraUiState.Streaming) {
            PreviewSurface(callbacks)
        }
        when (state) {
            CameraUiState.NoDevice ->
                CenteredMessage(
                    icon = { Icon(Icons.Filled.Usb, contentDescription = null, Modifier.size(64.dp)) },
                    title = stringResource(R.string.no_device_title),
                    body = stringResource(R.string.no_device_body),
                )
            is CameraUiState.PermissionNeeded ->
                CenteredMessage(
                    title = stringResource(R.string.permission_needed_title),
                    body = stringResource(R.string.permission_needed_body, state.deviceName),
                    action = {
                        Button(onClick = callbacks.onPermissionRetry) {
                            Text(stringResource(R.string.grant_permission))
                        }
                    },
                )
            is CameraUiState.Connecting ->
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.connecting_to, state.deviceName),
                        Modifier.padding(top = 16.dp),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            is CameraUiState.Streaming -> StreamingContent(state, callbacks)
            is CameraUiState.Error ->
                CenteredMessage(
                    title = state.error.message(),
                    body = "",
                    action = {
                        Button(onClick = callbacks.onRetryAfterError) {
                            Text(stringResource(R.string.retry))
                        }
                    },
                )
        }
    }
}

@Composable
private fun CameraError.message(): String =
    when (this) {
        CameraError.NoUsbHostSupport -> stringResource(R.string.error_no_usb_host)
        CameraError.PermissionDenied -> stringResource(R.string.permission_needed_title)
        is CameraError.OpenFailed -> stringResource(R.string.error_open_failed)
        CameraError.UnsupportedDevice -> stringResource(R.string.error_unsupported_device)
        is CameraError.RecordingFailed -> stringResource(R.string.error_recording_failed, reason)
    }

@Composable
private fun CenteredMessage(
    title: String,
    body: String,
    icon: @Composable () -> Unit = {},
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        if (body.isNotEmpty()) {
            Text(
                body,
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.padding(top = 24.dp)) { action() }
    }
}

@Composable
private fun PreviewSurface(callbacks: ViewerCallbacks) {
    AndroidView(
        factory = { context ->
            callbacks.previewFactory(context).also(callbacks.onPreviewReady)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// Renders the full overlay (resolution picker, rotate/mirror, shutter, mode toggle,
// recording timer) as one Compose function so the layout stays readable top-to-bottom;
// splitting it into sub-composables would scatter closely related layout code.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun StreamingContent(
    state: CameraUiState.Streaming,
    callbacks: ViewerCallbacks,
) {
    // PreviewSurface is rendered once by the caller (ViewerContent) so its identity survives
    // the Connecting -> Streaming transition; this composable only draws the control overlays.
    val context = LocalContext.current

    val audioPermission =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (granted) callbacks.onShutter() }

    // Re-entered after the storage grant (or immediately, if storage wasn't needed) so a
    // storage grant falls through to the audio check instead of bypassing it.
    fun proceedAfterStorage() {
        val needsAudio =
            state.captureMode == CaptureMode.VIDEO &&
                !state.isRecording &&
                callbacks.recordAudioEnabled &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        if (needsAudio) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            callbacks.onShutter()
        }
    }

    val storagePermission =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (granted) proceedAfterStorage() }

    val shutter = {
        val needsStorage =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
        if (needsStorage) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            proceedAfterStorage()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var resMenu by remember { mutableStateOf(callbacks.autoShowResolutionPicker) }
                val pickResolutionDescription = stringResource(R.string.cd_pick_resolution)
                Box {
                    AssistChip(
                        onClick = { resMenu = true },
                        label = { Text(state.resolution.toString()) },
                        modifier =
                            Modifier.semantics {
                                contentDescription = pickResolutionDescription
                            },
                    )
                    DropdownMenu(expanded = resMenu, onDismissRequest = { resMenu = false }) {
                        state.availableResolutions.forEach { res ->
                            DropdownMenuItem(
                                text = { Text(res.toString()) },
                                onClick = {
                                    resMenu = false
                                    callbacks.onSelectResolution(res)
                                },
                            )
                        }
                    }
                }
                Row {
                    IconButton(onClick = callbacks.onRotate) {
                        Icon(
                            Icons.Filled.Rotate90DegreesCw,
                            contentDescription = stringResource(R.string.cd_rotate),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = callbacks.onToggleMirror) {
                        Icon(
                            Icons.Filled.Flip,
                            contentDescription = stringResource(R.string.cd_mirror),
                            tint = if (state.mirrored) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                    IconButton(onClick = callbacks.onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.cd_open_settings),
                            tint = Color.White,
                        )
                    }
                }
            }

            // Bottom bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp, start = 32.dp, end = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = callbacks.onOpenGallery) {
                    if (state.lastCaptureUri != null) {
                        AsyncImage(
                            model = state.lastCaptureUri,
                            contentDescription = stringResource(R.string.cd_open_gallery),
                            modifier =
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape),
                        )
                    } else {
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = stringResource(R.string.cd_open_gallery),
                            tint = Color.White,
                        )
                    }
                }
                FilledIconButton(
                    onClick = shutter,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                ) {
                    if (state.captureMode == CaptureMode.VIDEO) {
                        Icon(
                            Icons.Filled.FiberManualRecord,
                            contentDescription =
                                stringResource(
                                    if (state.isRecording) R.string.cd_record_stop else R.string.cd_record_start,
                                ),
                            tint = Color(0xFFFF5252),
                        )
                    } else {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.cd_shutter))
                    }
                }
                IconButton(onClick = callbacks.onToggleCaptureMode) {
                    Icon(
                        if (state.captureMode == CaptureMode.PHOTO) Icons.Filled.Videocam else Icons.Filled.PhotoCamera,
                        contentDescription = stringResource(R.string.cd_toggle_mode),
                        tint = Color.White,
                    )
                }
            }
        }

        if (state.isRecording) {
            AssistChip(
                onClick = {},
                label = { Text(formatElapsed(state.recordingSeconds)) },
                leadingIcon = {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color(0xFFFF5252))
                },
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .systemBarsPadding()
                        .padding(top = 8.dp),
            )
        }
    }
}

private const val SECONDS_PER_MINUTE = 60

internal fun formatElapsed(seconds: Long): String =
    "%02d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
