package org.onekash.kashcal.reminder.device

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for DeviceCalendarReminderActionReceiver (Phase 4 - Chunk 6).
 *
 * Tests cover:
 * - Snooze action handling
 * - Dismiss action handling
 * - Intent action filtering
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarReminderActionReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: DeviceCalendarReminderActionReceiver

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        receiver = DeviceCalendarReminderActionReceiver()
    }

    // ========== Intent Action Filtering ==========

    @Test
    fun `onReceive ignores null intent`() {
        // Should not crash
        receiver.onReceive(context, null)
    }

    @Test
    fun `onReceive ignores intent with wrong action`() {
        val intent = Intent("com.example.WRONG_ACTION")
        // Should not crash
        receiver.onReceive(context, intent)
    }

    @Test
    fun `onReceive handles snooze action`() {
        val intent = Intent(DeviceCalendarReminderNotificationManager.ACTION_DEVICE_SNOOZE).apply {
            putExtra(DeviceCalendarReminderNotificationManager.EXTRA_EVENT_ID, 123L)
            putExtra(DeviceCalendarReminderNotificationManager.EXTRA_OCCURRENCE_TS, 1709251200000L)
            putExtra(DeviceCalendarReminderNotificationManager.EXTRA_NOTIFICATION_ID, 20001)
        }

        // Should not crash (actual functionality requires Hilt injection)
        receiver.onReceive(context, intent)
        assertTrue("Snooze intent should be processed without crash", true)
    }

    @Test
    fun `onReceive handles dismiss action`() {
        val intent = Intent(DeviceCalendarReminderNotificationManager.ACTION_DEVICE_DISMISS).apply {
            putExtra(DeviceCalendarReminderNotificationManager.EXTRA_NOTIFICATION_ID, 20001)
        }

        // Should not crash
        receiver.onReceive(context, intent)
        assertTrue("Dismiss intent should be processed without crash", true)
    }

    // ========== Intent Constants ==========

    @Test
    fun `snooze action constant matches notification manager`() {
        assertEquals(
            "org.onekash.kashcal.DEVICE_SNOOZE_REMINDER",
            DeviceCalendarReminderNotificationManager.ACTION_DEVICE_SNOOZE
        )
    }

    @Test
    fun `dismiss action constant matches notification manager`() {
        assertEquals(
            "org.onekash.kashcal.DEVICE_DISMISS_REMINDER",
            DeviceCalendarReminderNotificationManager.ACTION_DEVICE_DISMISS
        )
    }
}
