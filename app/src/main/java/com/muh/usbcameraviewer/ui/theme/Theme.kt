package com.muh.usbcameraviewer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF7FD0FF),
        onPrimary = Color(0xFF00344C),
        primaryContainer = Color(0xFF004C6C),
        onPrimaryContainer = Color(0xFFC4E7FF),
        secondary = Color(0xFFB5C9D7),
        onSecondary = Color(0xFF20333D),
        background = Color(0xFF0B0F12),
        onBackground = Color(0xFFE1E6EA),
        surface = Color(0xFF11161A),
        onSurface = Color(0xFFE1E6EA),
        surfaceVariant = Color(0xFF1A2228),
        onSurfaceVariant = Color(0xFFBFC8CE),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )

@Composable
fun UcvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
