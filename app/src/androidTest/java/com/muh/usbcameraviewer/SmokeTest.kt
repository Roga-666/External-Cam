package com.muh.usbcameraviewer

import android.Manifest
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal instrumented smoke test: the app launches on-device and
 * [MainActivity] reaches the RESUMED state without crashing.
 *
 * CAMERA is pre-granted: on first launch the viewer immediately requests it,
 * and the system permission dialog would otherwise hold the activity below
 * RESUMED.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun mainActivityReachesResumedState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
