package com.muh.usbcameraviewer.gallery

data class MediaItem(
    val uri: String,
    val isVideo: Boolean,
    val displayName: String,
    val dateAddedEpochSeconds: Long,
)
