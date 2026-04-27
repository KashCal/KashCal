package org.onekash.kashcal.widget

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Unit tests for WidgetUpdateManager scheduling methods.
 *
 * - schedulePeriodicUpdates() enqueues periodic WorkManager work (unchanged).
 * - scheduleMidnightUpdate() schedules an exact AlarmManager alarm
 *   pointing at MidnightWidgetUpdateReceiver, with setAndAllowWhileIdle
 *   fallback when exact alarms aren't permitted. AlarmManager is used
 *   (instead of WorkManager) because Doze defers JobScheduler/WorkManager
 *   entirely; setExactAndAllowWhileIdle fires through Doze.
 * - cancelAllUpdates() cancels both the periodic work and the midnight alarm.
 *
 * Retry and updateAllWidgets() are covered in WidgetUpdateManagerRetryTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WidgetUpdateManagerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var alarmManager: AlarmManager
    private lateinit var shadowAlarmManager: ShadowAlarmManager
    private lateinit var manager: WidgetUpdateManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        context = RuntimeEnvironment.getApplication()

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)

        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = shadowOf(alarmManager)

        manager = WidgetUpdateManager(context)
    }

    @After
    fun tearDown() {
        // Clear ShadowAlarmManager state — it's shared across tests in the same JVM
        // and our scheduled midnight alarms would otherwise leak into other suites.
        manager.cancelAllUpdates()
        unmockkAll()
    }

    // ==================== schedulePeriodicUpdates Tests ====================

    @Test
    fun `schedulePeriodicUpdates enqueues periodic work`() {
        manager.schedulePeriodicUpdates()

        val workInfos = workManager.getWorkInfosForUniqueWork("widget_periodic_update").get()
        assertTrue("Periodic work should be enqueued", workInfos.isNotEmpty())
        assertTrue(
            "Periodic work should be active, was: ${workInfos[0].state}",
            workInfos[0].state == WorkInfo.State.ENQUEUED || workInfos[0].state == WorkInfo.State.RUNNING
        )
    }

    @Test
    fun `schedulePeriodicUpdates uses KEEP policy for idempotency`() {
        manager.schedulePeriodicUpdates()
        manager.schedulePeriodicUpdates()

        val workInfos = workManager.getWorkInfosForUniqueWork("widget_periodic_update").get()
        assertEquals("Should have exactly one periodic work", 1, workInfos.size)
    }

    // ==================== scheduleMidnightUpdate Tests ====================

    @Test
    fun `scheduleMidnightUpdate schedules exact alarm pointing at MidnightWidgetUpdateReceiver`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        manager.scheduleMidnightUpdate()

        val scheduled = shadowAlarmManager.nextScheduledAlarm
        assertNotNull("Midnight alarm should be scheduled", scheduled)
        assertEquals(AlarmManager.RTC_WAKEUP, scheduled!!.type)

        val savedIntent = shadowOf(scheduled.operation).savedIntent
        assertNotNull("PendingIntent should carry an Intent", savedIntent)
        assertEquals(
            "PendingIntent must target MidnightWidgetUpdateReceiver",
            MidnightWidgetUpdateReceiver::class.java.name,
            savedIntent.component?.className
        )
    }

    @Test
    fun `scheduleMidnightUpdate trigger time is next local midnight`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        manager.scheduleMidnightUpdate()

        val scheduled = shadowAlarmManager.nextScheduledAlarm
        assertNotNull(scheduled)
        val expected = LocalDate.now()
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertTrue(
            "Trigger time ${scheduled!!.triggerAtTime} should be next local midnight ($expected) within 1s",
            abs(scheduled.triggerAtTime - expected) < 1000L
        )
    }

    @Test
    fun `scheduleMidnightUpdate replaces existing alarm`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        manager.scheduleMidnightUpdate()
        manager.scheduleMidnightUpdate()

        // Same request code + FLAG_UPDATE_CURRENT should yield a single scheduled alarm
        assertEquals(
            "Rescheduling should replace, not stack",
            1,
            shadowAlarmManager.scheduledAlarms.size
        )
    }

    @Test
    fun `scheduleMidnightUpdate falls back to inexact when exact alarm permission denied`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        manager.scheduleMidnightUpdate()

        val scheduled = shadowAlarmManager.nextScheduledAlarm
        assertNotNull("Fallback alarm should be scheduled", scheduled)
        val savedIntent = shadowOf(scheduled!!.operation).savedIntent
        assertEquals(
            MidnightWidgetUpdateReceiver::class.java.name,
            savedIntent.component?.className
        )
    }

    // ==================== cancelAllUpdates Tests ====================

    @Test
    fun `cancelAllUpdates cancels all widget work and alarms`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        manager.schedulePeriodicUpdates()
        manager.scheduleMidnightUpdate()

        manager.cancelAllUpdates()

        val periodicWork = workManager.getWorkInfosForUniqueWork("widget_periodic_update").get()
        assertTrue(
            "Periodic work should be cancelled",
            periodicWork.isEmpty() || periodicWork[0].state == WorkInfo.State.CANCELLED
        )
        assertNull("Midnight alarm should be cancelled", shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `cancelAllUpdates is idempotent`() {
        manager.cancelAllUpdates()
        manager.cancelAllUpdates()

        val periodicWork = workManager.getWorkInfosForUniqueWork("widget_periodic_update").get()
        assertTrue(
            "No periodic work should exist",
            periodicWork.isEmpty() || periodicWork[0].state == WorkInfo.State.CANCELLED
        )
        assertNull("No midnight alarm should exist", shadowAlarmManager.nextScheduledAlarm)
    }
}
