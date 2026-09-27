package org.onekash.kashcal.reminder.receiver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.DAY
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.MINUTE
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.onekash.kashcal.reminder.worker.ReminderRefreshWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that a fired reminder refills the seven-day window inside the receiver, not through a
 * background job the system can defer or a later request can replace.
 *
 * Runs the real receiver handler and scheduler over an in-memory database, with a mocked
 * notification manager, and reads the armed alarms back from Robolectric's AlarmManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderAlarmRefillTest {

    private lateinit var context: Context
    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var notificationManager: ReminderNotificationManager
    private val now = System.currentTimeMillis()

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        fixture = ReminderAlarmFixture(context)
        fixture.seedCalendar()

        notificationManager = mockk()
        coEvery { notificationManager.buildNotification(any()) } returns mockk()
        every { notificationManager.postNotification(any(), any()) } returns 1
        every { notificationManager.cancelNotification(any()) } just runs
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    /** Runs the handler off the test clock, as the receiver does, so its timeouts are real. */
    private suspend fun fire(reminderId: Long) = withContext(Dispatchers.IO) {
        ReminderAlarmReceiver().handleAlarm(fixture.scheduler, notificationManager, reminderId)
    }

    @Test
    fun `a fired reminder arms the next reminders inside the window before the receiver finishes`() = runTest {
        val firedStart = now + 10 * MINUTE
        val fired = fixture.insertEvent("Standup", firedStart, listOf("-PT15M"))
        val firedId = fixture.insertReminderRow(fired, firedStart, "-PT15M", now - 5 * MINUTE)

        // In the window with no row yet
        val nextStart = now + 2 * DAY
        fixture.insertEvent("Planning", nextStart, listOf("-PT15M"))
        // Has a row but no alarm, as a reboot leaves a row that was beyond the window
        val laterStart = now + 3 * DAY
        val later = fixture.insertEvent("Review", laterStart, listOf("-PT15M"))
        val laterId = fixture.insertReminderRow(later, laterStart, "-PT15M", laterStart - 15 * MINUTE)
        // Not due within seven days
        fixture.insertEvent("Offsite", now + 10 * DAY, listOf("-PT15M"))

        fire(firedId)

        val armed = fixture.armedReminders()
        assertEquals(
            setOf(nextStart - 15 * MINUTE, laterStart - 15 * MINUTE),
            armed.values.toSet()
        )
        assertEquals(laterStart - 15 * MINUTE, armed[laterId])
        assertTrue("the fired reminder is not armed again", firedId !in armed)
        assertEquals(ReminderStatus.FIRED, fixture.reminderRow(firedId)?.status)
    }

    @Test
    fun `the refill is not handed to a background job`() = runTest {
        val firedStart = now + 10 * MINUTE
        val fired = fixture.insertEvent("Standup", firedStart, listOf("-PT15M"))
        val firedId = fixture.insertReminderRow(fired, firedStart, "-PT15M", now - 5 * MINUTE)
        fixture.insertEvent("Planning", now + 2 * DAY, listOf("-PT15M"))

        fire(firedId)

        assertEquals(1, fixture.armedReminders().size)
        val workManager = WorkManager.getInstance(context)
        assertTrue(workManager.getWorkInfosByTag(ReminderRefreshWorker.TAG_REMINDER_REFRESH).get().isEmpty())
        assertTrue(workManager.getWorkInfosForUniqueWork(ReminderRefreshWorker.WORK_NAME).get().isEmpty())
    }
}
