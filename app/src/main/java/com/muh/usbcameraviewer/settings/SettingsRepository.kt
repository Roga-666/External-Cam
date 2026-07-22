package com.muh.usbcameraviewer.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.muh.usbcameraviewer.camera.Resolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        private object Keys {
            val resolutionPolicy = stringPreferencesKey("resolution_policy")
            val lastResolutionW = intPreferencesKey("last_resolution_w")
            val lastResolutionH = intPreferencesKey("last_resolution_h")
            val videoQuality = stringPreferencesKey("video_quality")
            val recordAudio = booleanPreferencesKey("record_audio")
            val filenamePrefix = stringPreferencesKey("filename_prefix")
            val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        }

        val settings: Flow<AppSettings> =
            dataStore.data.map { p ->
                val defaults = AppSettings()
                AppSettings(
                    resolutionPolicy =
                        p[Keys.resolutionPolicy]
                            ?.let { runCatching { ResolutionPolicy.valueOf(it) }.getOrNull() }
                            ?: defaults.resolutionPolicy,
                    lastResolution =
                        p[Keys.lastResolutionW]?.let { w ->
                            p[Keys.lastResolutionH]?.let { h -> Resolution(w, h) }
                        },
                    videoQuality =
                        p[Keys.videoQuality]
                            ?.let { runCatching { VideoQuality.valueOf(it) }.getOrNull() }
                            ?: defaults.videoQuality,
                    recordAudio = p[Keys.recordAudio] ?: defaults.recordAudio,
                    filenamePrefix =
                        p[Keys.filenamePrefix]?.takeIf { it.isNotBlank() }
                            ?: defaults.filenamePrefix,
                    keepScreenOn = p[Keys.keepScreenOn] ?: defaults.keepScreenOn,
                )
            }

        suspend fun setResolutionPolicy(value: ResolutionPolicy) =
            dataStore.edit { it[Keys.resolutionPolicy] = value.name }

        suspend fun setLastResolution(value: Resolution) =
            dataStore.edit {
                it[Keys.lastResolutionW] = value.width
                it[Keys.lastResolutionH] = value.height
            }

        suspend fun setVideoQuality(value: VideoQuality) = dataStore.edit { it[Keys.videoQuality] = value.name }

        suspend fun setRecordAudio(value: Boolean) = dataStore.edit { it[Keys.recordAudio] = value }

        suspend fun setFilenamePrefix(value: String) =
            dataStore.edit { it[Keys.filenamePrefix] = sanitizePrefix(value) }

        suspend fun setKeepScreenOn(value: Boolean) = dataStore.edit { it[Keys.keepScreenOn] = value }

        private companion object {
            private val slashes = Regex("""[/\\]""")
            private val whitespaceRuns = Regex("""\s+""")
            private val leadingDots = Regex("""^\.+""")

            // Filename prefixes become path segments, so strip anything that could escape the
            // target directory or hide the file: path separators, whitespace runs (collapsed),
            // and leading dots. A fully sanitized-away prefix reads back as the default (blank
            // fallback in `settings`).
            fun sanitizePrefix(value: String): String =
                value
                    .replace(slashes, "")
                    .replace(whitespaceRuns, " ")
                    .trim()
                    .replace(leadingDots, "")
                    .trim()
        }
    }
