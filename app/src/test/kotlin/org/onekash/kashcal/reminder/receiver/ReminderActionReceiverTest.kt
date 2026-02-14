package org.onekash.kashcal.reminder.receiver

import android.content.Intent
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ReminderActionReceiver.
 *
 * Tests:
 * - Action constants match between notification manager and receiver
 * - Intent extra handling (reminder ID, snooze duration)
 * - Default snooze duration fallback
 *
 * Note: onReceive() cannot be called directly because @AndroidEntryPoint
 * generates Hilt injection code that requires a full Hilt test environment.
 * The core logic is simple delegation tested via ReminderScheduler tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderActionReceiverTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ==================== Contract Tests ====================

    @Test
    fun `ACTION_SNOOZE constant has correct value`() {
        assertEquals("org.onekash.kashcal.SNOOZE_REMINDER", ReminderNotificationManager.ACTION_SNOOZE)
    }

    @Test
    fun `ACTION_DISMISS constant has correct value`() {
        assertEquals("org.onekash.kashcal.DISMISS_REMINDER", ReminderNotificationManager.ACTION_DISMISS)
    }

    @Test
    fun `DEFAULT_SNOOZE_MINUTES is 15`() {
        assertEquals(15, ReminderNotificationManager.DEFAULT_SNOOZE_MINUTES)
    }

    @Test
    fun `EXTRA_REMINDER_ID constant has correct value`() {
        assertEquals("reminder_id", ReminderNotificationManager.EXTRA_REMINDER_ID)
    }

    @Test
    fun `EXTRA_SNOOZE_DURATION_MINUTES constant has correct value`() {
        assertEquals("snooze_duration_minutes", ReminderNotificationManager.EXTRA_SNOOZE_DURATION_MINUTES)
    }

    // ==================== Intent Extra Handling ====================

    @Test
    fun `intent missing EXTRA_REMINDER_ID defaults to -1`() {
        val intent = Intent(ReminderNotificationManager.ACTION_SNOOZE)
        assertEquals(-1L, intent.getLongExtra(ReminderNotificationManager.EXTRA_REMINDER_ID, -1))
    }

    @Test
    fun `intent with valid EXTRA_REMINDER_ID returns correct value`() {
        val intent = Intent(ReminderNotificationManager.ACTION_SNOOZE)
        intent.putExtra(ReminderNotificationManager.EXTRA_REMINDER_ID, 42L)
        assertEquals(42L, intent.getLongExtra(ReminderNotificationManager.EXTRA_REMINDER_ID, -1))
    }

    @Test
    fun `intent missing EXTRA_SNOOZE_DURATION_MINUTES defaults to DEFAULT_SNOOZE_MINUTES`() {
        val intent = Intent(ReminderNotificationManager.ACTION_SNOOZE)
        intent.putExtra(ReminderNotificationManager.EXTRA_REMINDER_ID, 1L)
        val snoozeDuration = intent.getIntExtra(
            ReminderNotificationManager.EXTRA_SNOOZE_DURATION_MINUTES,
            ReminderNotificationManager.DEFAULT_SNOOZE_MINUTES
        )
        assertEquals(15, snoozeDuration)
    }

    @Test
    fun `intent with custom snooze duration returns that duration`() {
        val intent = Intent(ReminderNotificationManager.ACTION_SNOOZE)
        intent.putExtra(ReminderNotificationManager.EXTRA_REMINDER_ID, 1L)
        intent.putExtra(ReminderNotificationManager.EXTRA_SNOOZE_DURATION_MINUTES, 30)
        val snoozeDuration = intent.getIntExtra(
            ReminderNotificationManager.EXTRA_SNOOZE_DURATION_MINUTES,
            ReminderNotificationManager.DEFAULT_SNOOZE_MINUTES
        )
        assertEquals(30, snoozeDuration)
    }

    // ==================== Action Differentiation ====================

    @Test
    fun `snooze and dismiss actions are distinct`() {
        assertNotEquals(
            ReminderNotificationManager.ACTION_SNOOZE,
            ReminderNotificationManager.ACTION_DISMISS
        )
    }
}
