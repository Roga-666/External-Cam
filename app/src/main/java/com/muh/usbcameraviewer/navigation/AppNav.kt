// Routes is intentionally kept in this file alongside the AppNav graph that uses it, rather
// than split into its own file, since the two are never used independently.
@file:Suppress("MatchingDeclarationName")

package com.muh.usbcameraviewer.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.muh.usbcameraviewer.gallery.GalleryScreen
import com.muh.usbcameraviewer.settings.SettingsScreen
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
        composable(Routes.GALLERY) { GalleryScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
