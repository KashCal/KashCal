package org.onekash.kashcal.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.util.Log
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Tests [AlarmArming.setAllowWhileIdle]: exact or inexact by permission, one inexact attempt
 * after a SecurityException unless the caller opts out, and no retry after a refusal at the
 * alarm limit (the platform limit is described on [AlarmArming]), which is logged once without
 * a stack trace. Every refusal returns false instead of throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AlarmArmingTest {

    private val tag = "AlarmArmingTest"
    private val label = "Test alarm"
    private val triggerTime = 1_900_000_000_000L
    private val alarmLimitRefusal =
        IllegalStateException("Maximum limit of concurrent alarms 500 reached")

    private lateinit var alarmManager: AlarmManager
    private val pendingIntent: PendingIntent = mockk()

    @Before
    fun setup() {
        ShadowLog.clear()
        alarmManager = mockk()
        every { alarmManager.canScheduleExactAlarms() } returns true
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } just runs
        every { alarmManager.setAndAllowWhileIdle(any(), any(), any()) } just runs
    }

    private fun arm(inexactFallback: Boolean = true): Boolean =
        AlarmArming.setAllowWhileIdle(
            alarmManager = alarmManager,
            triggerTime = triggerTime,
            pendingIntent = pendingIntent,
            tag = tag,
            label = label,
            inexactFallback = inexactFallback
        )

    private fun errorLogs() = ShadowLog.getLogsForTag(tag).filter { it.type == Log.ERROR }

    @Test
    fun `sets an exact alarm when exact alarms are allowed`() {
        assertTrue(arm())

        verify(exactly = 1) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `sets an inexact alarm when exact alarms are not allowed`() {
        every { alarmManager.canScheduleExactAlarms() } returns false

        assertTrue(arm())

        verify(exactly = 1) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `falls back to inexact when the exact alarm is denied`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")

        assertTrue(arm())

        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), triggerTime, any()) }
    }

    @Test
    fun `retries inexact once when the inexact-only alarm is denied`() {
        every { alarmManager.canScheduleExactAlarms() } returns false
        every { alarmManager.setAndAllowWhileIdle(any(), any(), any()) } throws
            SecurityException("revoked") andThen Unit

        assertTrue(arm())

        verify(exactly = 2) { alarmManager.setAndAllowWhileIdle(any(), triggerTime, any()) }
    }

    @Test
    fun `reports not armed when both exact and inexact are denied`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")
        every { alarmManager.setAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")

        assertFalse(arm())
    }

    @Test
    fun `exact alarm refused at the alarm limit reports not armed without retrying inexact`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        assertFalse(arm())

        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), triggerTime, any()) }
        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `inexact-only alarm refused at the alarm limit reports not armed`() {
        every { alarmManager.canScheduleExactAlarms() } returns false
        every { alarmManager.setAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        assertFalse(arm())

        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), triggerTime, any()) }
    }

    @Test
    fun `inexact fallback refused at the alarm limit reports not armed`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")
        every { alarmManager.setAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        assertFalse(arm())
    }

    @Test
    fun `denied exact alarm is not retried when the caller opts out of the inexact fallback`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")

        assertFalse(arm(inexactFallback = false))

        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `refusal at the alarm limit is logged once as not armed without a stack trace`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        arm()

        val errors = errorLogs()
        assertEquals(1, errors.size)
        assertEquals("$label at $triggerTime not armed: ${alarmLimitRefusal.message}", errors[0].msg)
        assertNull(errors[0].throwable)
    }
}
