package org.onekash.kashcal.reminder.receiver

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
 * Unit tests for BootRecoveryHandler.
 *
 * Tests the reminder recovery logic extracted from BootCompletedReceiver.
 * No Hilt, no Android framework, no reflection — just a plain class with mocks.
 */
class BootRecoveryHandlerTest {

    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var handler: BootRecoveryHandler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0

        reminderScheduler = mockk(relaxed = true)
        handler = BootRecoveryHandler(reminderScheduler)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `rescheduleReminders calls rescheduleAllPending`() = runTest {
        handler.rescheduleReminders()

        coVerify { reminderScheduler.rescheduleAllPending() }
    }

    @Test
    fun `rescheduleReminders calls cleanupOldReminders`() = runTest {
        handler.rescheduleReminders()

        coVerify { reminderScheduler.cleanupOldReminders() }
    }

    @Test
    fun `rescheduleReminders calls rescheduleAllPending before cleanup`() = runTest {
        handler.rescheduleReminders()

        coVerifyOrder {
            reminderScheduler.rescheduleAllPending()
            reminderScheduler.cleanupOldReminders()
        }
    }

    @Test
    fun `rescheduleReminders propagates exception from rescheduleAllPending`() = runTest {
        coEvery { reminderScheduler.rescheduleAllPending() } throws RuntimeException("DB error")

        try {
            handler.rescheduleReminders()
            assert(false) { "Expected exception" }
        } catch (e: RuntimeException) {
            assert(e.message == "DB error")
        }

        // cleanupOldReminders should NOT be called if rescheduleAllPending throws
        coVerify(exactly = 0) { reminderScheduler.cleanupOldReminders() }
    }
}
