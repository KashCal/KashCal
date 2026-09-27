package org.onekash.kashcal.reminder.receiver

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.widget.WidgetUpdateManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that boot and app-update recovery completes when the platform refuses the widget's
 * midnight alarm because the app is at its 500 pending-alarm limit.
 *
 * The refusal comes from a mocked [AlarmManager] under the real [WidgetUpdateManager].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BootRecoveryAlarmCapTest {

    private lateinit var alarmManager: AlarmManager
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var deviceScheduler: DeviceCalendarReminderScheduler
    private lateinit var handler: BootRecoveryHandler

    @Before
    fun setup() {
        alarmManager = mockk()
        every { alarmManager.canScheduleExactAlarms() } returns true
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws
            IllegalStateException("Maximum limit of concurrent alarms 500 reached")
        val context = spyk(ApplicationProvider.getApplicationContext<Context>())
        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager

        reminderScheduler = mockk()
        coJustRun { reminderScheduler.rescheduleAllPending() }
        coJustRun { reminderScheduler.cleanupOldReminders() }
        deviceScheduler = mockk()
        coJustRun { deviceScheduler.scheduleNextReminder() }

        handler = BootRecoveryHandler(reminderScheduler, deviceScheduler, WidgetUpdateManager(context))
    }

    @Test
    fun `recovery completes when the midnight widget alarm is refused at the alarm limit`() = runTest {
        handler.rescheduleReminders()

        coVerify(exactly = 1) { reminderScheduler.rescheduleAllPending() }
        coVerify(exactly = 1) { reminderScheduler.cleanupOldReminders() }
        coVerify(exactly = 1) { deviceScheduler.scheduleNextReminder() }
        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
    }
}
