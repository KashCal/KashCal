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
import org.onekash.kashcal.domain.model.AccountProvider
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
                Account(provider = AccountProvider.LOCAL, email = "local")
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

    @Test
    fun `findExisting matches the exact occurrence, not a neighbouring one`() = runTest {
        // Duplicate prevention keys on a single occurrence. If this predicate ever
        // relaxed to an inequality, a reminder already set on a neighbouring
        // occurrence would look like a duplicate of this one and scheduling would be
        // skipped, so the user would silently lose the reminder. Rows on both sides,
        // so neither direction of comparison can pass.
        val queriedOccurrence = 5_000_000L
        val sharedOffset = "-PT15M"
        remindersDao.insert(createReminder(
            occurrenceTime = queriedOccurrence - 3_600_000,
            reminderOffset = sharedOffset
        ))
        remindersDao.insert(createReminder(
            occurrenceTime = queriedOccurrence + 3_600_000,
            reminderOffset = sharedOffset
        ))

        val existing = remindersDao.findExisting(testEventId, queriedOccurrence, sharedOffset)

        assertNull("Neither the earlier nor the later occurrence is a duplicate", existing)
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

    // ==================== Sibling Lookup Tests ====================
    //
    // When one reminder for an occurrence fires, it needs the ids of the *other*
    // reminders on that same occurrence so it can clear their notifications and
    // leave only one on screen. These pin the exact shape of that lookup: the
    // occurrence must match on both event and time, and status must not be
    // filtered (see below).

    /** Fixed occurrence timestamp: createReminder's default is call-time, so two defaulted rows can land 1 ms apart and silently not be siblings. */
    private val siblingOccurrence = 1_800_000_000_000L

    @Test
    fun `sibling lookup returns an already-fired reminder on the same occurrence`() = runTest {
        // The whole point: the fired reminder is the one whose notification is on
        // screen, so filtering it out would defeat the lookup entirely.
        val firedId = remindersDao.insert(
            createReminder(
                occurrenceTime = siblingOccurrence,
                reminderOffset = "-PT1H",
                status = ReminderStatus.FIRED
            )
        )
        val firingId = remindersDao.insert(
            createReminder(
                occurrenceTime = siblingOccurrence,
                reminderOffset = "-PT15M",
                status = ReminderStatus.PENDING
            )
        )

        val siblings = remindersDao.getSiblingIdsForOccurrence(
            eventId = testEventId,
            occurrenceTime = siblingOccurrence,
            excludeId = firingId
        )

        assertEquals(listOf(firedId), siblings)
    }

    @Test
    fun `sibling lookup ignores status entirely`() = runTest {
        // Every status must come back, so no status filter can creep in.
        val pendingId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT1H", status = ReminderStatus.PENDING)
        )
        val snoozedId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT45M", status = ReminderStatus.SNOOZED)
        )
        val firedId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT30M", status = ReminderStatus.FIRED)
        )
        val dismissedId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT20M", status = ReminderStatus.DISMISSED)
        )
        val firingId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT15M", status = ReminderStatus.PENDING)
        )

        val siblings = remindersDao.getSiblingIdsForOccurrence(testEventId, siblingOccurrence, firingId)

        assertEquals(
            setOf(pendingId, snoozedId, firedId, dismissedId),
            siblings.toSet()
        )
    }

    @Test
    fun `sibling lookup excludes other occurrences on both sides`() = runTest {
        // Earlier AND later, so neither a >= nor a <= comparison can pass.
        val earlierId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence - 3_600_000, reminderOffset = "-PT15M")
        )
        val laterId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence + 3_600_000, reminderOffset = "-PT15M")
        )
        val siblingId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT1H")
        )
        val firingId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT15M")
        )

        val siblings = remindersDao.getSiblingIdsForOccurrence(testEventId, siblingOccurrence, firingId)

        // Exact match, so the neighbouring occurrences are excluded by assertion
        // rather than by a follow-up check that could never fail.
        assertEquals(
            "Only the same occurrence's reminder is a sibling, not $earlierId or $laterId",
            listOf(siblingId),
            siblings
        )
    }

    @Test
    fun `sibling lookup excludes a different event at the same instant`() = runTest {
        val event2Id = database.eventsDao().insert(
            Event(
                id = 0,
                uid = "test-event-sibling-2",
                calendarId = testCalendarId,
                title = "Other Event",
                startTs = siblingOccurrence,
                endTs = siblingOccurrence + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )
        val otherEventId = remindersDao.insert(
            createReminder(eventId = event2Id, occurrenceTime = siblingOccurrence, reminderOffset = "-PT15M")
        )
        val siblingId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT1H")
        )
        val firingId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT15M")
        )

        val siblings = remindersDao.getSiblingIdsForOccurrence(testEventId, siblingOccurrence, firingId)

        assertEquals(
            "Only the same event's reminder is a sibling, not $otherEventId",
            listOf(siblingId),
            siblings
        )
    }

    @Test
    fun `sibling lookup never returns the firing reminder itself`() = runTest {
        val firingId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT15M")
        )
        remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT1H")
        )

        val siblings = remindersDao.getSiblingIdsForOccurrence(testEventId, siblingOccurrence, firingId)

        assertTrue("Firing reminder must never cancel its own notification", firingId !in siblings)
    }

    @Test
    fun `sibling lookup returns empty for a lone reminder`() = runTest {
        val onlyId = remindersDao.insert(
            createReminder(occurrenceTime = siblingOccurrence, reminderOffset = "-PT15M")
        )

        val siblings = remindersDao.getSiblingIdsForOccurrence(testEventId, siblingOccurrence, onlyId)

        assertTrue("A single reminder has no siblings", siblings.isEmpty())
    }
}
