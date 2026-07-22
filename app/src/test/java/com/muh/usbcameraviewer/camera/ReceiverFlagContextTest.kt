package com.muh.usbcameraviewer.camera

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReceiverFlagContextTest {
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var flaggedCalls = 0
        var lastFlags: Int? = null

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            flags: Int,
        ): Intent? {
            flaggedCalls++
            lastFlags = flags
            return null
        }
    }

    // The flag-less two-arg registerReceiver call is exactly the behavior under test.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `two-arg registerReceiver is upgraded with NOT_EXPORTED flag`() {
        val recording = RecordingContext(ApplicationProvider.getApplicationContext())
        val wrapped = ReceiverFlagContext(recording)

        wrapped.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) = Unit
            },
            IntentFilter("com.example.CUSTOM_ACTION"),
        )

        assertEquals(1, recording.flaggedCalls)
        assertEquals(Context.RECEIVER_NOT_EXPORTED, recording.lastFlags)
    }
}
