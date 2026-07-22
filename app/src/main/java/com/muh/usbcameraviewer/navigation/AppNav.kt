package com.muh.usbcameraviewer.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.muh.usbcameraviewer.viewer.ViewerScreen

object Routes {
    const val VIEWER = "viewer"
    const val GALLERY = "gallery"
    const val SETTINGS = "settings"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.VIEWER) {
        composable(Routes.VIEWER) {
            ViewerScreen(
                onOpenGallery = { nav.navigate(Routes.GALLERY) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.GALLERY) { Text("Gallery — Task 9") }
        composable(Routes.SETTINGS) { Text("Settings — Task 10") }
    }
}
