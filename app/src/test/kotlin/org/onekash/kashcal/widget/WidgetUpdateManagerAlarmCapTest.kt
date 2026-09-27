package org.onekash.kashcal.widget

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Tests that [WidgetUpdateManager.scheduleMidnightUpdate] survives the platform refusing the
 * midnight refresh alarm.
 *
 * AlarmManager caps each app at 500 pending alarms and throws IllegalStateException for every
 * further set call, even one that only replaces the app's existing midnight alarm. App startup
 * sets this alarm on every launch, so an escaping refusal would stop the app from opening. The
 * refused alarm is skipped and logged as not armed, without an inexact retry after an exact
 * refusal (a SecurityException on the exact call still falls back to inexact), and the periodic
 * widget update keeps the widgets refreshing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WidgetUpdateManagerAlarmCapTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var manager: WidgetUpdateManager
    private val alarmLimitRefusal =
        IllegalStateException("Maximum limit of concurrent alarms 500 reached")

    @Before
    fun setup() {
        ShadowLog.clear()
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            appContext,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
                .build()
        )

        alarmManager = mockk()
        every { alarmManager.canScheduleExactAlarms() } returns true
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal
        every { alarmManager.setAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        context = spyk(appContext)
        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        manager = WidgetUpdateManager(context)
    }

    @Test
    fun `exact midnight alarm refused at the alarm limit is skipped without retrying inexact`() {
        manager.scheduleMidnightUpdate()

        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `inexact midnight alarm refused at the alarm limit is skipped`() {
        every { alarmManager.canScheduleExactAlarms() } returns false

        manager.scheduleMidnightUpdate()

        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `inexact fallback after a denied exact alarm refused at the alarm limit is skipped`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")

        manager.scheduleMidnightUpdate()

        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `refused midnight alarm is logged as not armed`() {
        manager.scheduleMidnightUpdate()

        val notArmed = ShadowLog.getLogsForTag("WidgetUpdateManager")
            .filter { it.type == Log.ERROR && it.msg.contains("not armed") }
        assertEquals(1, notArmed.size)
        assertTrue(notArmed[0].msg.contains(alarmLimitRefusal.message!!))
    }

    @Test
    fun `startup scheduling survives the alarm limit and keeps the periodic widget update`() {
        manager.scheduleUpdates()

        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        val periodic = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("widget_periodic_update").get()
        assertEquals("Periodic widget update should still be enqueued", 1, periodic.size)
    }
}
