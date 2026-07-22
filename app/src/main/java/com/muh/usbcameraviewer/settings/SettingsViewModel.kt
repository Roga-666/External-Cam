package com.muh.usbcameraviewer.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val repository: SettingsRepository,
    ) : ViewModel() {
        val settings: StateFlow<AppSettings> =
            repository.settings
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppSettings())

        fun setResolutionPolicy(v: ResolutionPolicy) = viewModelScope.launch { repository.setResolutionPolicy(v) }

        fun setVideoQuality(v: VideoQuality) = viewModelScope.launch { repository.setVideoQuality(v) }

        fun setRecordAudio(v: Boolean) = viewModelScope.launch { repository.setRecordAudio(v) }

        fun setFilenamePrefix(v: String) = viewModelScope.launch { repository.setFilenamePrefix(v) }

        fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { repository.setKeepScreenOn(v) }
    }
