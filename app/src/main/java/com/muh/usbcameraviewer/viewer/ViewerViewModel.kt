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
import com.muh.usbcameraviewer.settings.ResolutionPolicy
import com.muh.usbcameraviewer.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
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

@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val controller: CameraController,
    private val settingsRepository: SettingsRepository,
    private val captureStore: CaptureStore,
    @PreviewFactory val previewFactory: @JvmSuppressWildcards (android.content.Context) -> android.view.View,
) : ViewModel() {

    private val _state = MutableStateFlow<CameraUiState>(CameraUiState.NoDevice)
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val recordAudioEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.recordAudio }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var tickerJob: Job? = null

    init {
        viewModelScope.launch { controller.events.collect(::onEvent) }
        controller.startMonitoring()
    }

    private fun onEvent(event: CameraEvent) {
        when (event) {
            is CameraEvent.Attached -> _state.value = CameraUiState.Connecting(event.deviceName)
            is CameraEvent.PermissionGranted -> _state.value = CameraUiState.Connecting(event.deviceName)
            is CameraEvent.PermissionDenied -> _state.value = CameraUiState.PermissionNeeded(event.deviceName)
            is CameraEvent.Opened -> _state.value = CameraUiState.Streaming(
                deviceName = event.deviceName,
                resolution = event.resolution,
                availableResolutions = event.availableResolutions,
            )
            is CameraEvent.OpenFailed -> _state.value =
                CameraUiState.Error(CameraError.OpenFailed(event.reason))
            CameraEvent.Detached -> {
                val wasRecording = (_state.value as? CameraUiState.Streaming)?.isRecording == true
                if (wasRecording) stopRecording()
                _state.value = CameraUiState.NoDevice
            }
        }
    }

    /** Called by the AndroidView factory once the preview view exists. */
    fun onPreviewReady(preview: Any) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val preferred = settings.lastResolution
                .takeIf { settings.resolutionPolicy == ResolutionPolicy.REMEMBER_LAST }
            controller.open(preview, preferred)
        }
    }

    fun onPermissionRetry() = controller.requestPermission()

    fun onRetryAfterError() {
        controller.close()
        _state.value = CameraUiState.NoDevice
        controller.requestPermission()
    }

    fun onSelectResolution(resolution: Resolution) {
        controller.setResolution(resolution)
        viewModelScope.launch { settingsRepository.setLastResolution(resolution) }
    }

    fun onRotate() {
        val s = _state.value as? CameraUiState.Streaming ?: return
        val degrees = (s.rotationDegrees + 90) % 360
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
                    _state.update { s ->
                        if (s is CameraUiState.Streaming) s.copy(lastCaptureUri = uri) else s
                    }
                }
                .onFailure { _messages.tryEmit("Photo failed: ${it.message}") }
        }
    }

    private fun startRecording() {
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
                tickerJob = viewModelScope.launch {
                    while (true) {
                        delay(1_000)
                        _state.update { s ->
                            if (s is CameraUiState.Streaming && s.isRecording)
                                s.copy(recordingSeconds = s.recordingSeconds + 1)
                            else s
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
                        if (s is CameraUiState.Streaming)
                            s.copy(isRecording = false, recordingSeconds = 0, lastCaptureUri = uri)
                        else s
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
