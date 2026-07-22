package com.muh.usbcameraviewer.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muh.usbcameraviewer.camera.CameraController
import com.muh.usbcameraviewer.camera.CameraError
import com.muh.usbcameraviewer.camera.CameraEvent
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.Resolution
import com.muh.usbcameraviewer.settings.ResolutionPolicy
import com.muh.usbcameraviewer.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val controller: CameraController,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<CameraUiState>(CameraUiState.NoDevice)
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

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
            CameraEvent.Detached -> _state.value = CameraUiState.NoDevice
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
        _state.update { s ->
            if (s !is CameraUiState.Streaming) return
            val degrees = (s.rotationDegrees + 90) % 360
            controller.setRotation(degrees)
            s.copy(rotationDegrees = degrees)
        }
    }

    fun onToggleMirror() {
        _state.update { s ->
            if (s !is CameraUiState.Streaming) return
            val mirrored = !s.mirrored
            controller.setMirror(mirrored)
            s.copy(mirrored = mirrored)
        }
    }

    override fun onCleared() {
        controller.close()
        controller.stopMonitoring()
    }
}
