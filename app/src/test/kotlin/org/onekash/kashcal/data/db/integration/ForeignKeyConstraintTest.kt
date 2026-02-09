package org.onekash.kashcal.data.db.integration

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for FOREIGN KEY constraint behavior with real Room DB.
 *
 * Reproduces the FK constraint failure (code 787) reported in issue #55:
 * Nextcloud/SOGo sync fails with "FOREIGN KEY constraint failed".
 *
 * Tests verify which FK relationships can trigger code 787 and what
 * the exact error looks like, using a real in-memory Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ForeignKeyConstraintTest {

    private lateinit var database: KashCalDatabase
    private val accountsDao by lazy { database.accountsDao() }
    private val calendarsDao by lazy { database.calendarsDao() }
    private val eventsDao by lazy { database.eventsDao() }
    private val occurrencesDao by lazy { database.occurrencesDao() }

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Event.calendarId FK (events.calendar_id → calendars.id) ==========

    @Test
    fun `insert event with non-existent calendarId throws FK constraint error`() = runTest {
        // Scenario: Calendar deleted between sync phases, or event references wrong calendar
        try {
            eventsDao.insert(createEvent(calendarId = 999L, title = "Orphan Event"))
            fail("Expected SQLiteConstraintException for non-existent calendar_id")
        } catch (e: SQLiteConstraintException) {
            // Verify this is the FK error users are seeing
            assertTrue(
                "Expected FOREIGN KEY message but got: ${e.message}",
                e.message?.contains("FOREIGN KEY") == true ||
                    e.message?.contains("foreign key") == true ||
                    e.message?.contains("787") == true
            )
        }
    }

    @Test
    fun `upsert event with non-existent calendarId throws FK constraint error`() = runTest {
        // Same as above but using upsert (which is what PullStrategy uses)
        try {
            eventsDao.upsert(createEvent(calendarId = 999L, title = "Orphan Event"))
            fail("Expected SQLiteConstraintException for non-existent calendar_id on upsert")
        } catch (e: SQLiteConstraintException) {
            assertTrue(
                "Expected FK error but got: ${e.message}",
                e.message?.contains("FOREIGN KEY") == true ||
                    e.message?.contains("foreign key") == true ||
                    e.message?.contains("787") == true
            )
        }
    }

    // ========== Event.originalEventId FK (events.original_event_id → events.id) ==========

    @Test
    fun `insert exception event with non-existent originalEventId throws FK constraint error`() = runTest {
        // Scenario: Exception event references master that doesn't exist in DB
        // This is the most likely cause for Nextcloud/SOGo FK errors
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.CALDAV, email = "test@nextcloud.example.com")
        )
        val calendarId = calendarsDao.insert(createCalendar(accountId))

        try {
            eventsDao.insert(
                createEvent(calendarId = calendarId, title = "Orphan Exception").copy(
                    originalEventId = 999L, // Non-existent master
                    originalInstanceTime = System.currentTimeMillis()
                )
            )
            fail("Expected SQLiteConstraintException for non-existent original_event_id")
        } catch (e: SQLiteConstraintException) {
            assertTrue(
                "Expected FK error for original_event_id but got: ${e.message}",
                e.message?.contains("FOREIGN KEY") == true ||
                    e.message?.contains("foreign key") == true ||
                    e.message?.contains("787") == true
            )
        }
    }

    @Test
    fun `exception event insert succeeds when master exists`() = runTest {
        // Control test: exception insert works when master is in DB
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.CALDAV, email = "test@nextcloud.example.com")
        )
        val calendarId = calendarsDao.insert(createCalendar(accountId))
        val masterEventId = eventsDao.insert(
            createEvent(calendarId = calendarId, title = "Master Event", rrule = "FREQ=WEEKLY")
        )

        // This should NOT throw
        val exceptionId = eventsDao.insert(
            createEvent(calendarId = calendarId, title = "Modified Occurrence").copy(
                originalEventId = masterEventId,
                originalInstanceTime = System.currentTimeMillis()
            )
        )

        assertTrue("Exception event should have been inserted", exceptionId > 0)
    }

    // ========== Occurrence.eventId FK (occurrences.event_id → events.id) ==========

    @Test
    fun `insert occurrence with non-existent eventId throws FK constraint error`() = runTest {
        try {
            occurrencesDao.insert(
                createOccurrence(eventId = 999L, calendarId = 1L, startTs = System.currentTimeMillis())
            )
            fail("Expected SQLiteConstraintException for non-existent event_id in occurrence")
        } catch (e: SQLiteConstraintException) {
            assertTrue(
                "Expected FK error for occurrence.event_id but got: ${e.message}",
                e.message?.contains("FOREIGN KEY") == true ||
                    e.message?.contains("foreign key") == true ||
                    e.message?.contains("787") == true
            )
        }
    }

    // ========== Occurrence.exceptionEventId FK (occurrences.exception_event_id → events.id) ==========

    @Test
    fun `link occurrence to non-existent exception event throws FK constraint error`() = runTest {
        // Scenario: OccurrenceGenerator tries to link occurrence to deleted exception
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.CALDAV, email = "test@sogo.example.com")
        )
        val calendarId = calendarsDao.insert(createCalendar(accountId))
        val masterEventId = eventsDao.insert(
            createEvent(calendarId = calendarId, title = "Master", rrule = "FREQ=WEEKLY")
        )
        val occurrenceTime = System.currentTimeMillis()
        occurrencesDao.insert(
            createOccurrence(masterEventId, calendarId, occurrenceTime)
        )

        try {
            // Try to link occurrence to non-existent exception event
            occurrencesDao.linkException(masterEventId, occurrenceTime, 999L)
            fail("Expected SQLiteConstraintException for non-existent exception_event_id")
        } catch (e: SQLiteConstraintException) {
            assertTrue(
                "Expected FK error for occurrence.exception_event_id but got: ${e.message}",
                e.message?.contains("FOREIGN KEY") == true ||
                    e.message?.contains("foreign key") == true ||
                    e.message?.contains("787") == true
            )
        }
    }

    // ========== Sync Scenario Simulation ==========

    @Test
    fun `simulate sync - master deleted between master insert and exception insert`() = runTest {
        // Simulates race condition during sync:
        // 1. Master event inserted successfully
        // 2. Master event deleted (e.g., by concurrent calendar delete)
        // 3. Exception event tries to reference deleted master → FK violation
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.CALDAV, email = "test@nextcloud.example.com")
        )
        val calendarId = calendarsDao.insert(createCalendar(accountId))

        // Step 1: Insert master (simulates PullStrategy second pass)
        val masterEventId = eventsDao.insert(
            createEvent(calendarId = calendarId, title = "Weekly Standup", rrule = "FREQ=WEEKLY")
        )

        // Step 2: Delete master (simulates concurrent operation)
        eventsDao.deleteById(masterEventId)

        // Step 3: Try to insert exception referencing deleted master
        try {
            eventsDao.insert(
                createEvent(calendarId = calendarId, title = "Modified Standup").copy(
                    originalEventId = masterEventId, // Points to deleted master!
                    originalInstanceTime = System.currentTimeMillis()
                )
            )
            fail("Expected SQLiteConstraintException - master was deleted")
        } catch (e: SQLiteConstraintException) {
            assertTrue(
                "Expected FK error but got: ${e.message}",
                e.message?.contains("FOREIGN KEY") == true ||
                    e.message?.contains("foreign key") == true ||
                    e.message?.contains("787") == true
            )
        }
    }

    @Test
    fun `simulate sync - calendar deleted during event processing`() = runTest {
        // Simulates: User deletes calendar account while sync is pulling events
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.CALDAV, email = "test@sogo.example.com")
        )
        val calendarId = calendarsDao.insert(createCalendar(accountId))

        // First event inserts fine
        val event1Id = eventsDao.insert(
            createEvent(calendarId = calendarId, title = "Event 1")
        )
        assertTrue("First event should insert OK", event1Id > 0)

        // Calendar gets deleted (user removed account during sync)
        calendarsDao.deleteById(calendarId)

        // Next event tries to use same calendar_id → FK violation
        try {
            eventsDao.insert(
                createEvent(calendarId = calendarId, title = "Event 2")
            )
            fail("Expected SQLiteConstraintException - calendar was deleted")
        } catch (e: SQLiteConstraintException) {
            assertTrue(
                "Expected FK error for deleted calendar but got: ${e.message}",
                e.message?.contains("FOREIGN KEY") == true ||
                    e.message?.contains("foreign key") == true ||
                    e.message?.contains("787") == true
            )
        }
    }

    @Test
    fun `simulate sync - partial success before FK error`() = runTest {
        // Simulates @mdonz's report: "10 pulled, 1 issue"
        // Multiple events succeed, then one FK error stops the rest
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.CALDAV, email = "test@nextcloud.example.com")
        )
        val calendarId = calendarsDao.insert(createCalendar(accountId))

        // Insert 3 events successfully (simulates the "10 pulled" part)
        val successIds = mutableListOf<Long>()
        for (i in 1..3) {
            val eventId = eventsDao.insert(
                createEvent(calendarId = calendarId, title = "Event $i")
            )
            successIds.add(eventId)
        }
        assertEquals("All 3 events should be inserted", 3, successIds.size)

        // 4th event fails with FK error (simulates the "1 issue")
        var fkErrorCaught = false
        try {
            eventsDao.insert(
                createEvent(calendarId = calendarId, title = "Problem Event").copy(
                    originalEventId = 99999L // Non-existent master
                )
            )
        } catch (e: SQLiteConstraintException) {
            fkErrorCaught = true
        }
        assertTrue("FK error should have been caught", fkErrorCaught)

        // 5th event would succeed but is never attempted (sync aborted)
        // Verify the 3 successful events are still in DB (not rolled back)
        for (id in successIds) {
            val event = eventsDao.getById(id)
            assertTrue("Event $id should still exist after FK error on different event", event != null)
        }
    }

    @Test
    fun `FK error message format matches user report`() = runTest {
        // Verify the exact error message format to match what @mdonz and @h1nnak reported:
        // "FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)"
        try {
            eventsDao.insert(createEvent(calendarId = 999L, title = "Test"))
            fail("Expected SQLiteConstraintException")
        } catch (e: SQLiteConstraintException) {
            // Log the actual message for diagnostic purposes
            val message = e.message ?: ""
            println("Actual FK error message: $message")
            // The message should contain "FOREIGN KEY" at minimum
            assertTrue(
                "Error message should reference FOREIGN KEY, actual: $message",
                message.contains("FOREIGN KEY", ignoreCase = true) ||
                    message.contains("constraint", ignoreCase = true)
            )
        }
    }

    // ========== Helper Functions ==========

    private fun createCalendar(accountId: Long, name: String = "Test Calendar") = Calendar(
        accountId = accountId,
        caldavUrl = "https://caldav.example.com/${System.nanoTime()}/",
        displayName = name,
        color = 0xFF0000FF.toInt()
    )

    private fun createEvent(
        calendarId: Long,
        title: String = "Test Event",
        rrule: String? = null
    ) = Event(
        uid = "test-uid-${System.nanoTime()}@example.com",
        calendarId = calendarId,
        title = title,
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis() + 3600000,
        dtstamp = System.currentTimeMillis(),
        rrule = rrule,
        syncStatus = SyncStatus.SYNCED
    )

    private fun createOccurrence(eventId: Long, calendarId: Long, startTs: Long) = Occurrence(
        eventId = eventId,
        calendarId = calendarId,
        startTs = startTs,
        endTs = startTs + 3600000,
        startDay = Occurrence.toDayFormat(startTs),
        endDay = Occurrence.toDayFormat(startTs)
    )
}
