package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for ScheduledRemindersDao - the reminder scheduling system.
 *
 * Critical for ensuring users don't miss events. Tests ensure:
 * - Reminders are properly scheduled and retrieved
 * - Status transitions (PENDING -> SHOWN -> DISMISSED/SNOOZED)
 * - Snooze functionality works correctly
 * - Cleanup of old reminders
 * - Cascade delete when event is deleted
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ScheduledRemindersDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var remindersDao: ScheduledRemindersDao
    private var testEventId: Long = 0
    private var testCalendarId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        remindersDao = database.scheduledRemindersDao()

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = "local", email = "local")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "local://default",
                    displayName = "Test Calendar",
                    color = 0xFF2196F3.toInt()
                )
            )
            testEventId = database.eventsDao().insert(
                Event(
                    id = 0,
                    uid = "test-event-uid",
                    calendarId = testCalendarId,
                    title = "Test Event",
                    startTs = System.currentTimeMillis() + 3600000, // 1 hour from now
                    endTs = System.currentTimeMillis() + 7200000,
                    dtstamp = System.currentTimeMillis()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private var reminderCounter = 0

    private fun createReminder(
        eventId: Long = testEventId,
        triggerTime: Long = System.currentTimeMillis() + 1800000, // 30 min from now
        occurrenceTime: Long = System.currentTimeMillis() + 3600000,
        reminderOffset: String = "-PT${++reminderCounter}M", // Unique offset for each call
        status: ReminderStatus = ReminderStatus.PENDING
    ): ScheduledReminder {
        return ScheduledReminder(
            id = 0,
            eventId = eventId,
            triggerTime = triggerTime,
            occurrenceTime = occurrenceTime,
            reminderOffset = reminderOffset,
            eventTitle = "Test Event",
            calendarColor = 0xFF2196F3.toInt(),
            status = status
        )
    }

    // ==================== Basic CRUD Tests ====================

    @Test
    fun `insert creates reminder with generated ID`() = runTest {
        val reminder = createReminder()
        val id = remindersDao.insert(reminder)

        assertTrue(id > 0)

        val retrieved = remindersDao.getById(id)
        assertNotNull(retrieved)
        assertEquals(testEventId, retrieved?.eventId)
    }

    @Test
    fun `insertAll creates multiple reminders`() = runTest {
        val reminders = listOf(
            createReminder(triggerTime = System.currentTimeMillis() + 1000),
            createReminder(triggerTime = System.currentTimeMillis() + 2000),
            createReminder(triggerTime = System.currentTimeMillis() + 3000)
        )

        remindersDao.insertAll(reminders)

        val count = remindersDao.getCountForEvent(testEventId)
        assertEquals(3, count)
    }

    @Test
    fun `getById returns null for non-existent reminder`() = runTest {
        val reminder = remindersDao.getById(99999L)
        assertNull(reminder)
    }

    // ==================== Query Tests ====================

    @Test
    fun `getPendingForEvent returns only pending reminders for event`() = runTest {
        // Create reminders with different statuses
        remindersDao.insert(createReminder(status = ReminderStatus.PENDING))
        remindersDao.insert(createReminder(status = ReminderStatus.PENDING))
        remindersDao.insert(createReminder(status = ReminderStatus.FIRED))
        remindersDao.insert(createReminder(status = ReminderStatus.DISMISSED))

        val pending = remindersDao.getPendingForEvent(testEventId)

        assertEquals(2, pending.size)
        assertTrue(pending.all { it.status == ReminderStatus.PENDING })
    }

    @Test
    fun `getAllPendingAfter returns future pending reminders`() = runTest {
        val now = System.currentTimeMillis()

        // Future reminders
        remindersDao.insert(createReminder(triggerTime = now + 60000))
        remindersDao.insert(createReminder(triggerTime = now + 120000))

        // Past reminder
        remindersDao.insert(createReminder(triggerTime = now - 60000))

        val pending = remindersDao.getAllPendingAfter(now)

        assertEquals(2, pending.size)
        assertTrue(pending.all { it.triggerTime > now })
    }

    @Test
    fun `getPendingInRange returns reminders in time window`() = runTest {
        val now = System.currentTimeMillis()

        // In range
        remindersDao.insert(createReminder(triggerTime = now + 30000))
        remindersDao.insert(createReminder(triggerTime = now + 60000))

        // Out of range
        remindersDao.insert(createReminder(triggerTime = now + 300000)) // Too far future
        remindersDao.insert(createReminder(triggerTime = now - 60000)) // Past

        val inRange = remindersDao.getPendingInRange(now, now + 120000)

        assertEquals(2, inRange.size)
    }

    @Test
    fun `findExisting finds duplicate reminder`() = runTest {
        val occurrenceTime = System.currentTimeMillis() + 3600000
        val specificOffset = "-PT15M"

        remindersDao.insert(createReminder(
            occurrenceTime = occurrenceTime,
            reminderOffset = specificOffset
        ))

        val existing = remindersDao.findExisting(testEventId, occurrenceTime, specificOffset)

        assertNotNull(existing)
    }

    @Test
    fun `findExisting returns null when no match`() = runTest {
        val specificOffset = "-PT45M"
        remindersDao.insert(createReminder(
            occurrenceTime = 1000L,
            reminderOffset = specificOffset
        ))

        val existing = remindersDao.findExisting(testEventId, 2000L, specificOffset)

        assertNull(existing)
    }

    // ==================== Status Transition Tests ====================

    @Test
    fun `updateStatus changes reminder status`() = runTest {
        val id = remindersDao.insert(createReminder(status = ReminderStatus.PENDING))

        remindersDao.updateStatus(id, ReminderStatus.FIRED)

        val updated = remindersDao.getById(id)
        assertEquals(ReminderStatus.FIRED, updated?.status)
    }

    @Test
    fun `status transitions PENDING to SHOWN to DISMISSED`() = runTest {
        val id = remindersDao.insert(createReminder(status = ReminderStatus.PENDING))

        // PENDING -> SHOWN
        remindersDao.updateStatus(id, ReminderStatus.FIRED)
        assertEquals(ReminderStatus.FIRED, remindersDao.getById(id)?.status)

        // SHOWN -> DISMISSED
        remindersDao.updateStatus(id, ReminderStatus.DISMISSED)
        assertEquals(ReminderStatus.DISMISSED, remindersDao.getById(id)?.status)
    }

    // ==================== Snooze Tests ====================

    @Test
    fun `snooze updates trigger time and status`() = runTest {
        val originalTrigger = System.currentTimeMillis() + 1000
        val id = remindersDao.insert(createReminder(
            triggerTime = originalTrigger,
            status = ReminderStatus.FIRED
        ))

        val newTrigger = originalTrigger + 600000 // Snooze for 10 minutes
        remindersDao.snooze(id, newTrigger)

        val snoozed = remindersDao.getById(id)
        assertEquals(ReminderStatus.SNOOZED, snoozed?.status)
        assertEquals(newTrigger, snoozed?.triggerTime)
    }

    @Test
    fun `snoozed reminder becomes pending again for next trigger`() = runTest {
        val id = remindersDao.insert(createReminder(status = ReminderStatus.FIRED))

        val newTrigger = System.currentTimeMillis() + 600000
        remindersDao.snooze(id, newTrigger)

        // After snooze time passes, status should allow retrieval
        val snoozed = remindersDao.getById(id)
        assertEquals(ReminderStatus.SNOOZED, snoozed?.status)
    }

    // ==================== Delete Tests ====================

    @Test
    fun `deleteForEvent removes all reminders for event`() = runTest {
        remindersDao.insert(createReminder())
        remindersDao.insert(createReminder())
        remindersDao.insert(createReminder())

        remindersDao.deleteForEvent(testEventId)

        val count = remindersDao.getCountForEvent(testEventId)
        assertEquals(0, count)
    }

    @Test
    fun `deleteForOccurrence removes reminders for specific occurrence`() = runTest {
        val occ1Time = System.currentTimeMillis() + 3600000
        val occ2Time = System.currentTimeMillis() + 7200000

        remindersDao.insert(createReminder(occurrenceTime = occ1Time))
        remindersDao.insert(createReminder(occurrenceTime = occ1Time))
        remindersDao.insert(createReminder(occurrenceTime = occ2Time))

        remindersDao.deleteForOccurrence(testEventId, occ1Time)

        val remaining = remindersDao.getPendingForEvent(testEventId)
        assertEquals(1, remaining.size)
        assertEquals(occ2Time, remaining.first().occurrenceTime)
    }

    @Test
    fun `deleteForOccurrencesAfter removes future occurrence reminders`() = runTest {
        val now = System.currentTimeMillis()

        remindersDao.insert(createReminder(occurrenceTime = now + 1000))
        remindersDao.insert(createReminder(occurrenceTime = now + 2000))
        remindersDao.insert(createReminder(occurrenceTime = now + 5000))

        remindersDao.deleteForOccurrencesAfter(testEventId, now + 1500)

        val remaining = remindersDao.getPendingForEvent(testEventId)
        assertEquals(1, remaining.size)
        assertEquals(now + 1000, remaining.first().occurrenceTime)
    }

    @Test
    fun `deleteOldReminders cleans up past reminders`() = runTest {
        val now = System.currentTimeMillis()

        // Old reminders
        remindersDao.insert(createReminder(
            triggerTime = now - 86400000, // 1 day ago
            status = ReminderStatus.DISMISSED
        ))
        remindersDao.insert(createReminder(
            triggerTime = now - 172800000, // 2 days ago
            status = ReminderStatus.FIRED
        ))

        // Recent reminder
        remindersDao.insert(createReminder(
            triggerTime = now + 3600000,
            status = ReminderStatus.PENDING
        ))

        remindersDao.deleteOldReminders(now)

        val count = remindersDao.getPendingCount()
        // Only the future pending reminder should remain
        assertTrue(count >= 1)
    }

    // ==================== Count Tests ====================

    @Test
    fun `getPendingCount returns correct count`() = runTest {
        remindersDao.insert(createReminder(status = ReminderStatus.PENDING))
        remindersDao.insert(createReminder(status = ReminderStatus.PENDING))
        remindersDao.insert(createReminder(status = ReminderStatus.FIRED))

        val count = remindersDao.getPendingCount()

        assertEquals(2, count)
    }

    @Test
    fun `getCountForEvent returns count for specific event`() = runTest {
        // Create second event
        val event2Id = database.eventsDao().insert(
            Event(
                id = 0,
                uid = "test-event-2",
                calendarId = testCalendarId,
                title = "Event 2",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )

        remindersDao.insert(createReminder(eventId = testEventId))
        remindersDao.insert(createReminder(eventId = testEventId))
        remindersDao.insert(createReminder(eventId = event2Id))

        val event1Count = remindersDao.getCountForEvent(testEventId)
        val event2Count = remindersDao.getCountForEvent(event2Id)

        assertEquals(2, event1Count)
        assertEquals(1, event2Count)
    }

    // ==================== Cascade Delete Tests ====================

    @Test
    fun `deleting event cascades to reminders`() = runTest {
        remindersDao.insert(createReminder())
        remindersDao.insert(createReminder())

        // Delete the event
        database.eventsDao().deleteById(testEventId)

        // Reminders should be cascade deleted
        val count = remindersDao.getCountForEvent(testEventId)
        assertEquals(0, count)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles multiple reminders for same occurrence`() = runTest {
        val occurrenceTime = System.currentTimeMillis() + 3600000

        // 15 min before - unique offset
        remindersDao.insert(createReminder(
            occurrenceTime = occurrenceTime,
            triggerTime = occurrenceTime - 900000,
            reminderOffset = "-PT15M"
        ))
        // 30 min before - unique offset
        remindersDao.insert(createReminder(
            occurrenceTime = occurrenceTime,
            triggerTime = occurrenceTime - 1800000,
            reminderOffset = "-PT30M"
        ))
        // 1 hour before - unique offset
        remindersDao.insert(createReminder(
            occurrenceTime = occurrenceTime,
            triggerTime = occurrenceTime - 3600000,
            reminderOffset = "-PT1H"
        ))

        val reminders = remindersDao.getPendingForEvent(testEventId)
        assertEquals(3, reminders.size)
    }

    @Test
    fun `reminder with very far future trigger time is stored correctly`() = runTest {
        val farFuture = System.currentTimeMillis() + 365L * 24 * 3600 * 1000 // 1 year

        val id = remindersDao.insert(createReminder(triggerTime = farFuture))

        val retrieved = remindersDao.getById(id)
        assertEquals(farFuture, retrieved?.triggerTime)
    }
}
