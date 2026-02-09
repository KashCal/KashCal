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
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler

/**
 * Unit tests for TimezoneChangeHandler.
 *
 * Tests the timezone/time change logic extracted from TimezoneChangeReceiver.
 * No Hilt, no Android framework, no reflection — just a plain class with mocks.
 */
class TimezoneChangeHandlerTest {

    private lateinit var widgetUpdateManager: WidgetUpdateManager
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var handler: TimezoneChangeHandler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0

        widgetUpdateManager = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        handler = TimezoneChangeHandler(widgetUpdateManager, reminderScheduler)
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

        // rescheduleAllPending should NOT be called if widget update throws
        coVerify(exactly = 0) { reminderScheduler.rescheduleAllPending() }
    }
}
