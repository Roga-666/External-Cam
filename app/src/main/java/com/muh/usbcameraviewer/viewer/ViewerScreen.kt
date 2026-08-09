// ViewerCallbacks is intentionally kept in this file alongside the ViewerScreen it configures,
// rather than split into its own file, since the two are never used independently.
// TooManyFunctions: the viewer is one cohesive screen; its private sub-composables belong here.
@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.muh.usbcameraviewer.viewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.muh.usbcameraviewer.R
import com.muh.usbcameraviewer.camera.CameraError
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.CaptureMode
import com.muh.usbcameraviewer.camera.Resolution

data class ViewerCallbacks(
    val onPreviewReady: (Any) -> Unit = {},
    val onPreviewDisposed: () -> Unit = {},
    val onPermissionRetry: () -> Unit = {},
    val onRetryAfterError: () -> Unit = {},
    val onSelectResolution: (Resolution) -> Unit = {},
    val onRotate: () -> Unit = {},
    val onToggleMirror: () -> Unit = {},
    val onBrightness: (Int) -> Unit = {},
    val onContrast: (Int) -> Unit = {},
    val onZoomChanged: (Float, Float, Float) -> Unit = { _, _, _ -> },
    val onShutter: () -> Unit = {},
    val onToggleCaptureMode: () -> Unit = {},
    val onOpenGallery: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val autoShowResolutionPicker: Boolean = false,
    val recordAudioEnabled: Boolean = false,
    val previewFactory: (android.content.Context) -> android.view.View = { TextureView(it) },
)

@Suppress("LongMethod") // wires several permission launchers + lifecycle effects into one screen
@Composable
fun ViewerScreen(
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val autoShowResolutionPicker by viewModel.autoShowResolutionPicker.collectAsStateWithLifecycle()
    val recordAudioEnabled by viewModel.recordAudioEnabled.collectAsStateWithLifecycle()
    val switchingResolution by viewModel.switchingResolution.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }

    // Capture feedback: a brief white flash + haptic buzz, so a photo (especially one taken with
    // the hardware button) is clearly acknowledged.
    val captureFlash = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        viewModel.captureFlash.collect {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            captureFlash.snapTo(FLASH_PEAK_ALPHA)
            captureFlash.animateTo(0f, tween(FLASH_FADE_MS))
        }
    }

    // Android gates USB Video Class access behind the CAMERA runtime permission.
    // Request it up front so a device attach can complete its USB-permission grant.
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onCameraPermissionResult(granted)
        }
    LaunchedEffect(Unit) {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    val view = LocalView.current
    DisposableEffect(keepScreenOn, state is CameraUiState.Streaming) {
        view.keepScreenOn = keepScreenOn && state is CameraUiState.Streaming
        onDispose { view.keepScreenOn = false }
    }

    // Keep the camera alive across transient backgrounding; only re-open if it had been demoted.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                    Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        ViewerContent(
            state = state,
            callbacks =
                ViewerCallbacks(
                    onPreviewReady = viewModel::onPreviewReady,
                    onPreviewDisposed = viewModel::onPreviewDisposed,
                    onPermissionRetry = viewModel::onPermissionRetry,
                    onRetryAfterError = viewModel::onRetryAfterError,
                    onSelectResolution = viewModel::onSelectResolution,
                    onRotate = viewModel::onRotate,
                    onToggleMirror = viewModel::onToggleMirror,
                    onBrightness = viewModel::onBrightness,
                    onContrast = viewModel::onContrast,
                    onZoomChanged = viewModel::onZoomChanged,
                    onShutter = viewModel::onShutter,
                    onToggleCaptureMode = viewModel::onToggleCaptureMode,
                    onOpenGallery = onOpenGallery,
                    onOpenSettings = onOpenSettings,
                    autoShowResolutionPicker = autoShowResolutionPicker,
                    recordAudioEnabled = recordAudioEnabled,
                    previewFactory = viewModel.previewFactory,
                ),
        )
        if (captureFlash.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = captureFlash.value)),
            )
        }
        if (switchingResolution) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        // Float above the bottom camera controls (shutter row) rather than over them.
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(bottom = 108.dp),
        )
    }
}

private const val FLASH_PEAK_ALPHA = 0.75f
private const val FLASH_FADE_MS = 320
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2f
private const val ZOOM_STEP = 0.5f

/** Resolve a one-shot ViewModel message to its localized text. */
private fun ViewerMessage.resolve(context: android.content.Context): String =
    when (this) {
        ViewerMessage.LowStorage -> context.getString(R.string.msg_low_storage)
        ViewerMessage.CameraPermissionDenied -> context.getString(R.string.msg_camera_permission)
        is ViewerMessage.PhotoFailed ->
            context.getString(R.string.msg_photo_failed, reason ?: context.getString(R.string.unknown_error))
        is ViewerMessage.RecordingFailed ->
            context.getString(R.string.error_recording_failed, reason ?: context.getString(R.string.unknown_error))
        ViewerMessage.RecordingSaved -> context.getString(R.string.msg_recording_saved)
        is ViewerMessage.ResolutionUnsupported ->
            context.getString(R.string.msg_resolution_unsupported, resolution.toString())
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
        // controller.open() gets invoked twice against a dead view. It always fills the box (a
        // stable surface size — resizing it on a resolution change blanks the native stream); the
        // controller letterboxes the picture with a TextureView transform instead.
        if (state is CameraUiState.Connecting || state is CameraUiState.Streaming) {
            ZoomablePreview(streaming = state is CameraUiState.Streaming, callbacks = callbacks)
        }
        when (state) {
            CameraUiState.NoDevice ->
                CenteredMessage(
                    icon = {
                        Icon(
                            Icons.Filled.Usb,
                            contentDescription = null,
                            Modifier.size(64.dp),
                            // Explicit: default LocalContentColor rendered black-on-black here.
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    },
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
            is CameraUiState.WaitingPermission -> ConnectingSpinner(state.deviceName)
            is CameraUiState.Connecting -> ConnectingSpinner(state.deviceName)
            is CameraUiState.Streaming -> StreamingContent(state, callbacks)
            is CameraUiState.Error ->
                CenteredMessage(
                    title = state.error.message(),
                    body = "",
                    // NoUsbHostSupport is unrecoverable; every other error can be retried.
                    action = {
                        if (state.error != CameraError.NoUsbHostSupport) {
                            Button(onClick = callbacks.onRetryAfterError) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    },
                )
        }
    }
}

@Composable
private fun BoxScope.ConnectingSpinner(deviceName: String) {
    Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.connecting_to, deviceName),
            Modifier.padding(top = 16.dp),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun CameraError.message(): String =
    when (this) {
        CameraError.NoUsbHostSupport -> stringResource(R.string.error_no_usb_host)
        CameraError.PermissionDenied -> stringResource(R.string.permission_needed_title)
        is CameraError.OpenFailed -> stringResource(R.string.error_open_failed)
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

/**
 * Pinch-zoom + pan around the preview; captures crop to the visible region via onZoomChanged.
 * Double-tap toggles 1×/2×; the +/− buttons give TalkBack (and one-handed) users the same
 * control as the pinch gesture. Resets when the stream ends.
 */
@Composable
private fun ZoomablePreview(
    streaming: Boolean,
    callbacks: ViewerCallbacks,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    fun applyZoom(
        newZoom: Float,
        newOffset: Offset,
    ) {
        zoom = newZoom.coerceIn(1f, MAX_ZOOM)
        val maxX = (zoom - 1f) * containerSize.width / 2f
        val maxY = (zoom - 1f) * containerSize.height / 2f
        offset = Offset(newOffset.x.coerceIn(-maxX, maxX), newOffset.y.coerceIn(-maxY, maxY))
        callbacks.onZoomChanged(zoom, offset.x, offset.y)
    }

    LaunchedEffect(streaming) {
        if (!streaming) applyZoom(1f, Offset.Zero)
    }
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    applyZoom(
                        zoom * gestureZoom,
                        Offset(offset.x * gestureZoom + pan.x, offset.y * gestureZoom + pan.y),
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        applyZoom(if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM, Offset.Zero)
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            PreviewSurface(callbacks)
        }
        if (streaming) {
            ZoomControls(
                zoom = zoom,
                onStep = { step -> applyZoom(zoom + step, offset) },
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun ZoomControls(
    zoom: Float,
    onStep: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (zoom > 1f) {
            Text("%.1f×".format(zoom), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        IconButton(onClick = { onStep(ZOOM_STEP) }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_zoom_in), tint = Color.White)
        }
        IconButton(onClick = { onStep(-ZOOM_STEP) }) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.cd_zoom_out), tint = Color.White)
        }
    }
}

@Composable
private fun PreviewSurface(callbacks: ViewerCallbacks) {
    // onDispose fires when the surface leaves composition (navigating to the gallery), so the
    // ViewModel can close the native camera and demote to Connecting for a clean re-open on return.
    DisposableEffect(Unit) {
        onDispose { callbacks.onPreviewDisposed() }
    }
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

    // Chained runtime-permission gate for the shutter: storage (pre-Q only) -> mic (when
    // starting an audio video recording) -> fire the shutter.
    val audioPermission =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (granted) callbacks.onShutter() }

    fun proceedAfterStorage() {
        val startingAudioVideo =
            state.captureMode == CaptureMode.VIDEO && !state.isRecording && callbacks.recordAudioEnabled
        val needsAudio =
            startingAudioVideo &&
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

    // Pre-Q needs WRITE_EXTERNAL_STORAGE to publish captures; Q+ uses scoped storage.
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
    var adjustPanel by remember { mutableStateOf(false) }
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
                        // Mid-record the stream restart would freeze seconds of the file.
                        enabled = !state.isRecording,
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
                    IconButton(onClick = { adjustPanel = !adjustPanel }) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.cd_adjust_image),
                            tint = if (adjustPanel) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
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

        if (adjustPanel) {
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(top = 56.dp, end = 8.dp)
                    .width(260.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.brightness),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                val brightness = state.brightnessPercent ?: DEFAULT_CONTROL_PERCENT
                Slider(
                    value = brightness.toFloat(),
                    onValueChange = { callbacks.onBrightness(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.semantics { stateDescription = "$brightness %" },
                )
                Text(
                    stringResource(R.string.contrast),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                val contrast = state.contrastPercent ?: DEFAULT_CONTROL_PERCENT
                Slider(
                    value = contrast.toFloat(),
                    onValueChange = { callbacks.onContrast(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.semantics { stateDescription = "$contrast %" },
                )
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
private const val DEFAULT_CONTROL_PERCENT = 50

internal fun formatElapsed(seconds: Long): String =
    "%02d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
