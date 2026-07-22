package com.muh.usbcameraviewer.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muh.usbcameraviewer.camera.CameraController
import com.muh.usbcameraviewer.camera.CameraError
import com.muh.usbcameraviewer.camera.CameraEvent
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.CaptureMode
import com.muh.usbcameraviewer.camera.PreviewFactory
import com.muh.usbcameraviewer.camera.Resolution
import com.muh.usbcameraviewer.capture.CaptureStore
import com.muh.usbcameraviewer.capture.Filenames
import com.muh.usbcameraviewer.capture.StorageChecker
import com.muh.usbcameraviewer.settings.ResolutionPolicy
import com.muh.usbcameraviewer.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

private const val ROTATION_STEP_DEGREES = 90
private const val FULL_ROTATION_DEGREES = 360
private const val RECORDING_TICK_MS = 1_000L
private const val RESOLUTION_VERIFY_TRIES = 8
private const val RESOLUTION_VERIFY_STEP_MS = 200L
private const val MIN_RECORDING_BYTES = 200L * 1024 * 1024
private const val LOW_STORAGE_MESSAGE = "Not enough storage to record video."
private const val CAMERA_PERMISSION_DENIED_MESSAGE =
    "Camera permission is required to view the USB camera."

// ViewerViewModel exposes one cohesive viewer API (open/close, lifecycle, capture, rotate/mirror,
// resolution); the lifecycle-across-navigation and low-storage additions push it just past the
// default function threshold without changing that single responsibility.
@Suppress("TooManyFunctions")
@HiltViewModel
class ViewerViewModel
    @Inject
    constructor(
        private val controller: CameraController,
        private val settingsRepository: SettingsRepository,
        private val captureStore: CaptureStore,
        private val storageChecker: StorageChecker,
        @PreviewFactory val previewFactory: @JvmSuppressWildcards (android.content.Context) -> android.view.View,
    ) : ViewModel() {
        private val _state = MutableStateFlow<CameraUiState>(CameraUiState.NoDevice)
        val state: StateFlow<CameraUiState> = _state.asStateFlow()

        private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val messages = _messages.asSharedFlow()

        /** Emitted when a photo is captured, so the UI can flash + buzz (esp. the hardware button). */
        private val _captureFlash = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val captureFlash = _captureFlash.asSharedFlow()

        /** True while a resolution switch is renegotiating the stream, so the UI can show progress. */
        private val _switchingResolution = MutableStateFlow(false)
        val switchingResolution: StateFlow<Boolean> = _switchingResolution.asStateFlow()

        val keepScreenOn: StateFlow<Boolean> =
            settingsRepository.settings
                .map { it.keepScreenOn }
                .stateIn(viewModelScope, SharingStarted.Eagerly, true)

        val autoShowResolutionPicker: StateFlow<Boolean> =
            settingsRepository.settings
                .map { it.resolutionPolicy == ResolutionPolicy.ALWAYS_ASK }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        val recordAudioEnabled: StateFlow<Boolean> =
            settingsRepository.settings
                .map { it.recordAudio }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        private var tickerJob: Job? = null

        // The preview view handed over by the AndroidView factory, retained so the camera can be
        // re-opened on foreground when the surface was NOT disposed (app backgrounded, not navigated).
        private var pendingPreview: Any? = null

        init {
            viewModelScope.launch { controller.events.collect(::onEvent) }
            controller.startMonitoring()
        }

        private fun onEvent(event: CameraEvent) {
            // A failed open or a detach ends any pending resolution switch (the success path is
            // cleared by onSelectResolution once it has verified the new stream is actually live).
            if (event is CameraEvent.OpenFailed || event == CameraEvent.Detached) {
                _switchingResolution.value = false
            }
            when (event) {
                is CameraEvent.Attached -> _state.value = CameraUiState.WaitingPermission(event.deviceName)
                is CameraEvent.PermissionGranted -> _state.value = CameraUiState.Connecting(event.deviceName)
                is CameraEvent.PermissionDenied -> _state.value = CameraUiState.PermissionNeeded(event.deviceName)
                CameraEvent.NoUsbHost -> _state.value = CameraUiState.Error(CameraError.NoUsbHostSupport)
                is CameraEvent.Opened ->
                    _state.value =
                        CameraUiState.Streaming(
                            deviceName = event.deviceName,
                            resolution = event.resolution,
                            availableResolutions = event.availableResolutions,
                        )
                is CameraEvent.OpenFailed ->
                    _state.value =
                        CameraUiState.Error(CameraError.OpenFailed(event.reason))
                CameraEvent.Detached -> {
                    val wasRecording = (_state.value as? CameraUiState.Streaming)?.isRecording == true
                    if (wasRecording) stopRecording()
                    _state.value = CameraUiState.NoDevice
                }
                CameraEvent.ShutterPressed -> onShutter()
            }
        }

        /** Called by the AndroidView factory once the preview view exists. */
        fun onPreviewReady(preview: Any) {
            pendingPreview = preview
            openPreview(preview)
        }

        /**
         * The CAMERA runtime permission result. Android gates USB Video Class access behind
         * CAMERA, so a granted result re-triggers the USB permission request for the pending
         * device; a denied result surfaces a message and leaves the user on the current screen.
         */
        fun onCameraPermissionResult(granted: Boolean) {
            if (granted) {
                controller.requestPermission()
            } else {
                _messages.tryEmit(CAMERA_PERMISSION_DENIED_MESSAGE)
            }
        }

        private fun openPreview(preview: Any) {
            viewModelScope.launch {
                val settings = settingsRepository.settings.first()
                val preferred =
                    settings.lastResolution
                        .takeIf { settings.resolutionPolicy == ResolutionPolicy.REMEMBER_LAST }
                controller.open(preview, preferred)
            }
        }

        /**
         * The preview surface was disposed (navigating away from the viewer, e.g. to the gallery).
         * Finalize any recording, close the native camera, and demote Streaming back to Connecting
         * so re-entering the viewer recreates the surface and cleanly re-opens.
         */
        fun onPreviewDisposed() {
            pendingPreview = null
            closeAndDemote()
        }

        /**
         * App backgrounded (ON_STOP). Deliberately a no-op: keep the USB camera OPEN so the stream
         * survives transient focus loss (the USB-permission dialog, a notification, a quick app
         * switch) instead of tearing down and forcing a reconnect. The camera is still released when
         * the preview surface is actually disposed (navigation) or the device detaches.
         */
        fun onAppBackgrounded() = Unit

        /**
         * App foregrounded (ON_START). When the surface was NOT disposed (composition retained), the
         * factory won't fire again, so re-open against the retained preview only if we had demoted to
         * Connecting. If the surface was disposed and recreated, onPreviewReady handles the re-open.
         */
        fun onAppForegrounded() {
            val preview = pendingPreview
            if (_state.value is CameraUiState.Connecting && preview != null) {
                openPreview(preview)
            }
        }

        private fun closeAndDemote() {
            val s = _state.value
            if (s is CameraUiState.Streaming && s.isRecording) stopRecording()
            controller.close()
            if (s is CameraUiState.Streaming) {
                _state.value = CameraUiState.Connecting(s.deviceName)
            }
        }

        fun onPermissionRetry() = controller.requestPermission()

        fun onRetryAfterError() {
            controller.close()
            _state.value = CameraUiState.NoDevice
            controller.requestPermission()
        }

        fun onSelectResolution(resolution: Resolution) {
            // The native stop/setSize/start renegotiates the USB stream and can take a second; run
            // it off the main thread so the UI never freezes. Some cameras advertise a mode they
            // cannot actually stream (e.g. 1080p) and deliver a black frame — after switching, verify
            // the preview is live and, if not, revert to the previous resolution with a message.
            val previous = (_state.value as? CameraUiState.Streaming)?.resolution ?: return
            if (previous == resolution) return
            _switchingResolution.value = true
            viewModelScope.launch {
                try {
                    controller.setResolution(resolution)
                    settingsRepository.setLastResolution(resolution)
                    // Poll for the first frame: a working stream clears the spinner in a few hundred
                    // ms; a mode the camera can't deliver stays black until the deadline, then reverts.
                    var live = false
                    var tries = 0
                    while (tries < RESOLUTION_VERIFY_TRIES && !live) {
                        delay(RESOLUTION_VERIFY_STEP_MS)
                        live = controller.isPreviewLive()
                        tries++
                    }
                    if (!live) {
                        _messages.tryEmit(
                            "${resolution.width}×${resolution.height} isn't supported by this camera",
                        )
                        controller.setResolution(previous)
                        settingsRepository.setLastResolution(previous)
                    }
                } finally {
                    _switchingResolution.value = false
                }
            }
        }

        fun onRotate() {
            val s = _state.value as? CameraUiState.Streaming ?: return
            val degrees = (s.rotationDegrees + ROTATION_STEP_DEGREES) % FULL_ROTATION_DEGREES
            controller.setRotation(degrees)
            _state.value = s.copy(rotationDegrees = degrees)
        }

        fun onToggleMirror() {
            val s = _state.value as? CameraUiState.Streaming ?: return
            val mirrored = !s.mirrored
            controller.setMirror(mirrored)
            _state.value = s.copy(mirrored = mirrored)
        }

        fun onToggleCaptureMode() {
            _state.update { s ->
                if (s !is CameraUiState.Streaming || s.isRecording) return
                s.copy(captureMode = if (s.captureMode == CaptureMode.PHOTO) CaptureMode.VIDEO else CaptureMode.PHOTO)
            }
        }

        fun onShutter() {
            val s = _state.value as? CameraUiState.Streaming ?: return
            when {
                s.captureMode == CaptureMode.PHOTO -> capturePhoto()
                s.isRecording -> stopRecording()
                else -> startRecording()
            }
        }

        private fun capturePhoto() {
            viewModelScope.launch {
                val prefix = settingsRepository.settings.first().filenamePrefix
                val name = Filenames.photo(prefix, Instant.now())
                controller.capturePhoto(captureStore.tempFile(name))
                    .mapCatching { captureStore.publishPhoto(it, name) }
                    .onSuccess { uri ->
                        _captureFlash.tryEmit(Unit)
                        _state.update { s ->
                            if (s is CameraUiState.Streaming) s.copy(lastCaptureUri = uri) else s
                        }
                    }
                    .onFailure { _messages.tryEmit("Photo failed: ${it.message}") }
            }
        }

        private fun startRecording() {
            if (storageChecker.availableBytes() < MIN_RECORDING_BYTES) {
                _messages.tryEmit(LOW_STORAGE_MESSAGE)
                return
            }
            viewModelScope.launch {
                val settings = settingsRepository.settings.first()
                val name = Filenames.video(settings.filenamePrefix, Instant.now())
                controller.startRecording(
                    captureStore.tempFile(name),
                    withAudio = settings.recordAudio,
                    bitRate = settings.videoQuality.bitRate,
                ).onSuccess {
                    _state.update { s ->
                        if (s is CameraUiState.Streaming) s.copy(isRecording = true, recordingSeconds = 0) else s
                    }
                    tickerJob?.cancel()
                    tickerJob =
                        viewModelScope.launch {
                            while (true) {
                                delay(RECORDING_TICK_MS)
                                _state.update { s ->
                                    if (s is CameraUiState.Streaming && s.isRecording) {
                                        s.copy(recordingSeconds = s.recordingSeconds + 1)
                                    } else {
                                        s
                                    }
                                }
                            }
                        }
                }.onFailure { _messages.tryEmit("Recording failed: ${it.message}") }
            }
        }

        private fun stopRecording() {
            tickerJob?.cancel()
            tickerJob = null
            viewModelScope.launch {
                controller.stopRecording()
                    .mapCatching { file -> captureStore.publishVideo(file, file.name) }
                    .onSuccess { uri ->
                        _state.update { s ->
                            if (s is CameraUiState.Streaming) {
                                s.copy(isRecording = false, recordingSeconds = 0, lastCaptureUri = uri)
                            } else {
                                s
                            }
                        }
                    }
                    .onFailure {
                        _messages.tryEmit("Recording failed: ${it.message}")
                        _state.update { s ->
                            if (s is CameraUiState.Streaming) s.copy(isRecording = false, recordingSeconds = 0) else s
                        }
                    }
            }
        }

        override fun onCleared() {
            controller.close()
            controller.stopMonitoring()
        }
    }
