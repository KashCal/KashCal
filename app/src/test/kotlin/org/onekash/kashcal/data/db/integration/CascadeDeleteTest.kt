package org.onekash.kashcal.data.db.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * Integration tests for cascade delete behavior.
 *
 * Tests the FK CASCADE constraints:
 * - Delete Account → Calendars deleted
 * - Delete Calendar → Events deleted
 * - Delete Event → Occurrences deleted
 * - Delete Master Event → Exception Events deleted
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CascadeDeleteTest {

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

    // ========== Account → Calendar Cascade ==========

    @Test
    fun `delete account cascades to calendars`() = runTest {
        // Setup
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@example.com")
        )
        val cal1Id = calendarsDao.insert(createCalendar(accountId, "Cal 1"))
        val cal2Id = calendarsDao.insert(createCalendar(accountId, "Cal 2"))

        // Act
        accountsDao.deleteById(accountId)

        // Assert
        assertNull(calendarsDao.getById(cal1Id))
        assertNull(calendarsDao.getById(cal2Id))
        assertEquals(0, calendarsDao.getAllOnce().size)
    }

    // ========== Calendar → Event Cascade ==========

    @Test
    fun `delete calendar cascades to events`() = runTest {
        // Setup
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@example.com")
        )
        val calendarId = calendarsDao.insert(createCalendar(accountId))
        val event1Id = eventsDao.insert(createEvent(calendarId, "Event 1"))
        val event2Id = eventsDao.insert(createEvent(calendarId, "Event 2"))

        // Act
        calendarsDao.deleteById(calendarId)

        // Assert
        assertNull(eventsDao.getById(event1Id))
        assertNull(eventsDao.getById(event2Id))
    }

    // ========== Event → Occurrence Cascade ==========

    @Test
    fun `delete event cascades to occurrences`() = runTest {
        // Setup
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@example.com")
        )
        val calendarId = calendarsDao.insert(createCalendar(accountId))
        val eventId = eventsDao.insert(createEvent(calendarId, rrule = "FREQ=DAILY"))

        // Add occurrences
        val now = System.currentTimeMillis()
        occurrencesDao.insertAll(listOf(
            createOccurrence(eventId, calendarId, now),
            createOccurrence(eventId, calendarId, now + 86400000),
            createOccurrence(eventId, calendarId, now + 172800000)
        ))
        assertEquals(3, occurrencesDao.getForEvent(eventId).size)

        // Act
        eventsDao.deleteById(eventId)

        // Assert
        assertEquals(0, occurrencesDao.getForEvent(eventId).size)
    }

    // ========== Master Event → Exception Event Cascade ==========

    @Test
    fun `delete master event cascades to exception events`() = runTest {
        // Setup
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@example.com")
        )
        val calendarId = calendarsDao.insert(createCalendar(accountId))

        // Create master recurring event
        val masterEventId = eventsDao.insert(
            createEvent(calendarId, "Master Event", rrule = "FREQ=WEEKLY")
        )

        // Create exception events
        val exception1Id = eventsDao.insert(
            createEvent(calendarId, "Exception 1").copy(
                originalEventId = masterEventId,
                originalInstanceTime = System.currentTimeMillis()
            )
        )
        val exception2Id = eventsDao.insert(
            createEvent(calendarId, "Exception 2").copy(
                originalEventId = masterEventId,
                originalInstanceTime = System.currentTimeMillis() + 604800000
            )
        )

        // Verify setup
        assertEquals(2, eventsDao.getExceptionsForMaster(masterEventId).size)

        // Act - delete master
        eventsDao.deleteById(masterEventId)

        // Assert - exceptions should be deleted
        assertNull(eventsDao.getById(exception1Id))
        assertNull(eventsDao.getById(exception2Id))
        assertEquals(0, eventsDao.getExceptionsForMaster(masterEventId).size)
    }

    // ========== Full Cascade Chain ==========

    @Test
    fun `delete account cascades through entire chain`() = runTest {
        // Setup full hierarchy
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@example.com")
        )
        val calendarId = calendarsDao.insert(createCalendar(accountId))
        val masterEventId = eventsDao.insert(
            createEvent(calendarId, "Master", rrule = "FREQ=DAILY")
        )
        val exceptionEventId = eventsDao.insert(
            createEvent(calendarId, "Exception").copy(
                originalEventId = masterEventId,
                originalInstanceTime = System.currentTimeMillis()
            )
        )

        val now = System.currentTimeMillis()
        occurrencesDao.insertAll(listOf(
            createOccurrence(masterEventId, calendarId, now),
            createOccurrence(masterEventId, calendarId, now + 86400000)
        ))

        // Verify setup
        assertEquals(1, calendarsDao.getAllOnce().size)
        assertEquals(2, eventsDao.getByCalendarIdInRange(calendarId, 0, Long.MAX_VALUE).size)
        assertEquals(2, occurrencesDao.getTotalCount())

        // Act - delete account (top of chain)
        accountsDao.deleteById(accountId)

        // Assert - everything should be deleted
        assertEquals(0, calendarsDao.getAllOnce().size)
        assertNull(eventsDao.getById(masterEventId))
        assertNull(eventsDao.getById(exceptionEventId))
        assertEquals(0, occurrencesDao.getTotalCount())
    }

    // ========== Occurrence Exception Link SET_NULL ==========

    @Test
    fun `delete exception event sets occurrence link to null`() = runTest {
        // Setup
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@example.com")
        )
        val calendarId = calendarsDao.insert(createCalendar(accountId))
        val masterEventId = eventsDao.insert(
            createEvent(calendarId, "Master", rrule = "FREQ=DAILY")
        )

        val occurrenceTime = System.currentTimeMillis()

        // Create exception for this occurrence
        val exceptionEventId = eventsDao.insert(
            createEvent(calendarId, "Modified Occurrence").copy(
                originalEventId = masterEventId,
                originalInstanceTime = occurrenceTime
            )
        )

        // Create occurrence and link to exception
        val occId = occurrencesDao.insert(
            createOccurrence(masterEventId, calendarId, occurrenceTime)
        )
        occurrencesDao.linkException(masterEventId, occurrenceTime, exceptionEventId)

        // Verify link
        val linkedOcc = occurrencesDao.getForEvent(masterEventId).first()
        assertEquals(exceptionEventId, linkedOcc.exceptionEventId)

        // Act - delete exception (but not master)
        eventsDao.deleteById(exceptionEventId)

        // Assert - occurrence still exists but link is null (SET_NULL)
        val occAfterDelete = occurrencesDao.getForEvent(masterEventId).first()
        assertNull(occAfterDelete.exceptionEventId)
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
