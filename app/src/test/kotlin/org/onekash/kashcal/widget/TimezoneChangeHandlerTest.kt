package org.onekash.kashcal.widget

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler

/**
 * Tests [TimezoneChangeHandler], the timezone and clock change steps [TimezoneChangeReceiver]
 * delegates to: widgets update with the reason, then Room reminders reschedule, and an exception
 * out of the widget update propagates before the reschedule. The real
 * [WidgetUpdateManager.updateAllWidgets] catches its own failures except cancellation. The
 * device calendar reminder step isn't asserted here. Plain JUnit with mocks; only `Log` is
 * mocked statically.
 */
class TimezoneChangeHandlerTest {

    private lateinit var widgetUpdateManager: WidgetUpdateManager
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler
    private lateinit var handler: TimezoneChangeHandler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        widgetUpdateManager = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        deviceCalendarReminderScheduler = mockk(relaxed = true)
        handler = TimezoneChangeHandler(widgetUpdateManager, reminderScheduler, deviceCalendarReminderScheduler)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `handleChange updates widgets with reason`() = runTest {
        handler.handleChange("timezone_changed")

        coVerify { widgetUpdateManager.updateAllWidgets(reason = "timezone_changed") }
    }

    @Test
    fun `handleChange reschedules reminders`() = runTest {
        handler.handleChange("time_changed")

        coVerify { reminderScheduler.rescheduleAllPending() }
    }

    @Test
    fun `handleChange updates widgets before rescheduling reminders`() = runTest {
        handler.handleChange("timezone_changed")

        coVerifyOrder {
            widgetUpdateManager.updateAllWidgets(reason = "timezone_changed")
            reminderScheduler.rescheduleAllPending()
        }
    }

    @Test
    fun `handleChange propagates exception from widget update`() = runTest {
        coEvery { widgetUpdateManager.updateAllWidgets(reason = any()) } throws RuntimeException("Widget error")

        try {
            handler.handleChange("timezone_changed")
            assert(false) { "Expected exception" }
        } catch (e: RuntimeException) {
            assert(e.message == "Widget error")
        }

        // A throwing widget update stops before rescheduleAllPending.
        coVerify(exactly = 0) { reminderScheduler.rescheduleAllPending() }
    }
}
