package com.muh.usbcameraviewer.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.muh.usbcameraviewer.camera.Resolution
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher + Job())

    private fun repo(): SettingsRepository {
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, "test.preferences_pb") },
        )
        return SettingsRepository(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults returned when store empty`() = runTest(dispatcher) {
        val settings = repo().settings.first()
        assertEquals(AppSettings(), settings)
    }

    @Test
    fun `set and read back all fields`() = runTest(dispatcher) {
        val repo = repo()
        repo.setResolutionPolicy(ResolutionPolicy.ALWAYS_ASK)
        repo.setLastResolution(Resolution(1920, 1080))
        repo.setVideoQuality(VideoQuality.HIGH)
        repo.setRecordAudio(true)
        repo.setFilenamePrefix("scope")
        repo.setKeepScreenOn(false)

        val s = repo.settings.first()
        assertEquals(ResolutionPolicy.ALWAYS_ASK, s.resolutionPolicy)
        assertEquals(Resolution(1920, 1080), s.lastResolution)
        assertEquals(VideoQuality.HIGH, s.videoQuality)
        assertEquals(true, s.recordAudio)
        assertEquals("scope", s.filenamePrefix)
        assertEquals(false, s.keepScreenOn)
    }

    @Test
    fun `blank prefix falls back to default`() = runTest(dispatcher) {
        val repo = repo()
        repo.setFilenamePrefix("   ")
        assertEquals("UCV", repo.settings.first().filenamePrefix)
    }
}
