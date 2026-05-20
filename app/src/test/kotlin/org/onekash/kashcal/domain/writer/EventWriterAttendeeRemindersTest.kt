package org.onekash.kashcal.domain.writer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [EventWriter.saveAttendeeReminders] — the local-only reminder
 * write path used by the read-only attendee form. Distinguishing
 * properties:
 *
 * - Updates only `Event.reminders` and `Event.alarmCount` columns.
 * - Does NOT queue a PendingOperation (no server PUT).
 * - Does NOT change sync status (event remains SYNCED).
 * - Per-attendee VALARM editing per RFC 5545 §3.6.6.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventWriterAttendeeRemindersTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private var testCalendarId: Long = 0
    private var existingEventId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val occurrenceGenerator = OccurrenceGenerator(
            database,
            database.occurrencesDao(),
            database.eventsDao(),
            TestDataStoreFactory.createDefault()
        )
        eventWriter = EventWriter(database, occurrenceGenerator)

        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = "self@example.test")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://server/cal/",
                displayName = "Test",
                color = -1
            )
        )

        // Pre-existing event with one reminder, SYNCED state.
        existingEventId = database.eventsDao().insert(
            Event(
                uid = "existing-uid",
                calendarId = testCalendarId,
                title = "Quarterly review",
                startTs = 1_700_000_000_000L,
                endTs = 1_700_003_600_000L,
                dtstamp = 1_700_000_000_000L,
                organizerEmail = "boss@example.test",
                reminders = listOf("-PT15M"),
                alarmCount = 1,
                syncStatus = SyncStatus.SYNCED
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `saveAttendeeReminders updates the reminders column`() = runTest {
        eventWriter.saveAttendeeReminders(existingEventId, listOf("-PT30M", "-PT1H"))

        val updated = database.eventsDao().getById(existingEventId)
        assertEquals(listOf("-PT30M", "-PT1H"), updated?.reminders)
    }

    @Test
    fun `saveAttendeeReminders keeps alarmCount in sync with reminders size`() = runTest {
        eventWriter.saveAttendeeReminders(existingEventId, listOf("-PT15M", "-PT30M", "-PT1H"))

        val updated = database.eventsDao().getById(existingEventId)
        assertEquals(3, updated?.alarmCount)
    }

    @Test
    fun `saveAttendeeReminders does not change sync status`() = runTest {
        eventWriter.saveAttendeeReminders(existingEventId, listOf("-PT30M"))

        val updated = database.eventsDao().getById(existingEventId)
        // Critical: a server PUT triggered by a sync_status flip would
        // overwrite the organizer's event. Local-only path keeps SYNCED.
        assertEquals(SyncStatus.SYNCED, updated?.syncStatus)
    }

    @Test
    fun `saveAttendeeReminders does NOT queue a PendingOperation`() = runTest {
        eventWriter.saveAttendeeReminders(existingEventId, listOf("-PT30M"))

        val ops = database.pendingOperationsDao().getForEvent(existingEventId)
        assertTrue(
            "Local-only reminder save must not queue a server PUT — got ops: $ops",
            ops.isEmpty()
        )
    }

    @Test
    fun `saveAttendeeReminders preserves all other fields`() = runTest {
        val before = database.eventsDao().getById(existingEventId)!!
        eventWriter.saveAttendeeReminders(existingEventId, listOf("-PT5M"))
        val after = database.eventsDao().getById(existingEventId)!!

        assertEquals(before.uid, after.uid)
        assertEquals(before.title, after.title)
        assertEquals(before.startTs, after.startTs)
        assertEquals(before.organizerEmail, after.organizerEmail)
    }

    @Test
    fun `saveAttendeeReminders accepts empty list (clear all)`() = runTest {
        eventWriter.saveAttendeeReminders(existingEventId, emptyList())

        val updated = database.eventsDao().getById(existingEventId)
        // The DAO converter normalizes empty lists; either null or [] is acceptable
        // semantically. Check via alarmCount which is unambiguous.
        assertEquals(0, updated?.alarmCount)
        // And reminders is either null or empty:
        val r = updated?.reminders
        assertTrue("Reminders should be empty or null, got: $r", r.isNullOrEmpty())
    }
}
