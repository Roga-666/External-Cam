package com.muh.usbcameraviewer.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface GalleryUiState {
    data object Loading : GalleryUiState

    data object Empty : GalleryUiState

    data class Content(val items: List<MediaItem>) : GalleryUiState
}

@HiltViewModel
class GalleryViewModel
    @Inject
    constructor(
        private val repository: MediaRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow<GalleryUiState>(GalleryUiState.Loading)
        val state: StateFlow<GalleryUiState> = _state.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                val items = repository.loadItems()
                _state.value =
                    if (items.isEmpty()) {
                        GalleryUiState.Empty
                    } else {
                        GalleryUiState.Content(items)
                    }
            }
        }

        fun delete(item: MediaItem) {
            viewModelScope.launch {
                repository.delete(item)
                refresh()
            }
        }
    }
