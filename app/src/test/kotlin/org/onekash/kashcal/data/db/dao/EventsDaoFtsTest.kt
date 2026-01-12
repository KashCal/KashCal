package org.onekash.kashcal.data.db.dao

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import java.util.UUID

/**
 * Integration tests for EventsDao FTS4 full-text search.
 *
 * Tests the FTS search() method which uses FTS4 MATCH queries
 * instead of LIKE for improved performance.
 *
 * FTS query syntax:
 * - "meeting" - matches word "meeting"
 * - "meet*" - matches words starting with "meet"
 * - "team meeting" - matches events with both words
 */
class EventsDaoFtsTest : BaseDaoTest() {

    private val accountsDao by lazy { database.accountsDao() }
    private val calendarsDao by lazy { database.calendarsDao() }
    private val eventsDao by lazy { database.eventsDao() }
    private val occurrencesDao by lazy { database.occurrencesDao() }

    private var testCalendarId: Long = 0

    @Before
    override fun setup() {
        super.setup()
        runTest {
            val accountId = accountsDao.insert(
                Account(provider = "local", email = "local")
            )
            testCalendarId = calendarsDao.insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "local://calendar",
                    displayName = "Test Calendar",
                    color = 0xFF0000FF.toInt()
                )
            )
        }
    }

    private fun createEvent(
        title: String = "Test Event",
        location: String? = null,
        description: String? = null,
        startTs: Long = System.currentTimeMillis(),
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = Event(
        uid = UUID.randomUUID().toString(),
        calendarId = testCalendarId,
        title = title,
        location = location,
        description = description,
        startTs = startTs,
        endTs = startTs + 3600000, // 1 hour
        dtstamp = System.currentTimeMillis(),
        syncStatus = syncStatus
    )

    // ========== Basic Search Tests ==========

    @Test
    fun `search finds event by title word`() = runTest {
        eventsDao.insert(createEvent(title = "Team Meeting"))

        val results = eventsDao.search("Meeting*")

        assertEquals(1, results.size)
        assertEquals("Team Meeting", results[0].title)
    }

    @Test
    fun `search finds event by partial word with prefix`() = runTest {
        eventsDao.insert(createEvent(title = "Meeting with Team"))

        val results = eventsDao.search("Meet*")

        assertEquals(1, results.size)
        assertEquals("Meeting with Team", results[0].title)
    }

    @Test
    fun `search finds event by location`() = runTest {
        eventsDao.insert(createEvent(title = "Event", location = "Conference Room A"))

        val results = eventsDao.search("Conference*")

        assertEquals(1, results.size)
        assertEquals("Conference Room A", results[0].location)
    }

    @Test
    fun `search finds event by description`() = runTest {
        eventsDao.insert(
            createEvent(
                title = "Event",
                description = "Discuss project milestones"
            )
        )

        val results = eventsDao.search("milestones*")

        assertEquals(1, results.size)
        assertTrue(results[0].description?.contains("milestones") == true)
    }

    // ========== Multi-Word Search Tests ==========

    @Test
    fun `search with multiple words finds matching event`() = runTest {
        eventsDao.insert(createEvent(title = "Team Weekly Standup"))
        eventsDao.insert(createEvent(title = "Project Review"))

        // FTS searches for events with BOTH words
        val results = eventsDao.search("Team* Standup*")

        assertEquals(1, results.size)
        assertEquals("Team Weekly Standup", results[0].title)
    }

    @Test
    fun `search matches across title location and description`() = runTest {
        eventsDao.insert(
            createEvent(
                title = "Meeting",
                location = "Office",
                description = "Budget review"
            )
        )

        // Search should find event by any indexed field
        val byTitle = eventsDao.search("Meeting*")
        val byLocation = eventsDao.search("Office*")
        val byDescription = eventsDao.search("Budget*")

        assertEquals(1, byTitle.size)
        assertEquals(1, byLocation.size)
        assertEquals(1, byDescription.size)
    }

    // ========== Filter Tests ==========

    @Test
    fun `search excludes PENDING_DELETE events`() = runTest {
        eventsDao.insert(createEvent(title = "Active Meeting"))
        eventsDao.insert(
            createEvent(
                title = "Deleted Meeting",
                syncStatus = SyncStatus.PENDING_DELETE
            )
        )

        val results = eventsDao.search("Meeting*")

        assertEquals(1, results.size)
        assertEquals("Active Meeting", results[0].title)
    }

    @Test
    fun `search includes PENDING_CREATE events`() = runTest {
        eventsDao.insert(
            createEvent(
                title = "New Meeting",
                syncStatus = SyncStatus.PENDING_CREATE
            )
        )

        val results = eventsDao.search("Meeting*")

        assertEquals(1, results.size)
        assertEquals("New Meeting", results[0].title)
    }

    @Test
    fun `search includes PENDING_UPDATE events`() = runTest {
        eventsDao.insert(
            createEvent(
                title = "Updated Meeting",
                syncStatus = SyncStatus.PENDING_UPDATE
            )
        )

        val results = eventsDao.search("Meeting*")

        assertEquals(1, results.size)
    }

    // ========== Order Tests ==========

    @Test
    fun `search returns results ordered by start_ts ascending`() = runTest {
        val now = System.currentTimeMillis()

        eventsDao.insert(createEvent(title = "Meeting C", startTs = now + 7200000)) // +2h
        eventsDao.insert(createEvent(title = "Meeting A", startTs = now))           // now
        eventsDao.insert(createEvent(title = "Meeting B", startTs = now + 3600000)) // +1h

        val results = eventsDao.search("Meeting*")

        assertEquals(3, results.size)
        assertEquals("Meeting A", results[0].title)
        assertEquals("Meeting B", results[1].title)
        assertEquals("Meeting C", results[2].title)
    }

    // ========== Edge Cases ==========

    @Test
    fun `search returns empty list for no matches`() = runTest {
        eventsDao.insert(createEvent(title = "Team Meeting"))

        val results = eventsDao.search("NonexistentWord*")

        assertTrue(results.isEmpty())
    }

    @Test
    fun `search handles null location and description`() = runTest {
        eventsDao.insert(
            createEvent(
                title = "Simple Event",
                location = null,
                description = null
            )
        )

        val results = eventsDao.search("Simple*")

        assertEquals(1, results.size)
    }

    @Test
    fun `search respects limit of 100 results`() = runTest {
        // Insert 110 events
        repeat(110) { i ->
            eventsDao.insert(createEvent(title = "Meeting $i"))
        }

        val results = eventsDao.search("Meeting*")

        assertEquals(100, results.size)
    }

    @Test
    fun `search is case insensitive`() = runTest {
        eventsDao.insert(createEvent(title = "MEETING"))
        eventsDao.insert(createEvent(title = "meeting"))
        eventsDao.insert(createEvent(title = "Meeting"))

        val results = eventsDao.search("meeting*")

        assertEquals(3, results.size)
    }

    // ========== Multiple Events Tests ==========

    @Test
    fun `search finds multiple matching events`() = runTest {
        eventsDao.insert(createEvent(title = "Meeting with Alice"))
        eventsDao.insert(createEvent(title = "Meeting with Bob"))
        eventsDao.insert(createEvent(title = "Lunch"))

        val results = eventsDao.search("Meeting*")

        assertEquals(2, results.size)
    }

    @Test
    fun `search finds events across different calendars`() = runTest {
        // Create second calendar
        val account2Id = accountsDao.insert(
            Account(provider = "icloud", email = "test@icloud.com")
        )
        val calendar2Id = calendarsDao.insert(
            Calendar(
                accountId = account2Id,
                caldavUrl = "https://caldav.icloud.com/cal/",
                displayName = "Work",
                color = 0xFFFF0000.toInt()
            )
        )

        eventsDao.insert(createEvent(title = "Personal Meeting"))
        eventsDao.insert(
            Event(
                uid = UUID.randomUUID().toString(),
                calendarId = calendar2Id,
                title = "Work Meeting",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )

        val results = eventsDao.search("Meeting*")

        assertEquals(2, results.size)
    }

    // ========== searchInRange Tests ==========

    /**
     * Helper to create event with occurrence for date range tests.
     */
    private suspend fun createEventWithOccurrence(
        title: String,
        startTs: Long,
        endTs: Long = startTs + 3600000,
        isCancelled: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ): Long {
        val eventId = eventsDao.insert(
            createEvent(
                title = title,
                startTs = startTs,
                syncStatus = syncStatus
            ).copy(endTs = endTs)
        )

        val startDay = Occurrence.toDayFormat(startTs, false)
        val endDay = Occurrence.toDayFormat(endTs, false)

        occurrencesDao.insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = startTs,
                endTs = endTs,
                startDay = startDay,
                endDay = endDay,
                isCancelled = isCancelled
            )
        )

        return eventId
    }

    @Test
    fun `searchInRange finds event within date range`() = runTest {
        val jan5 = 1736035200000L  // Jan 5, 2025 00:00 UTC
        val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC
        val jan7 = 1736208000000L  // Jan 7, 2025 00:00 UTC

        createEventWithOccurrence("Meeting on Jan 6", jan6)

        val results = eventsDao.searchInRange("Meeting*", jan5, jan7)

        assertEquals(1, results.size)
        assertEquals("Meeting on Jan 6", results[0].title)
    }

    @Test
    fun `searchInRange excludes event outside date range`() = runTest {
        val jan5 = 1736035200000L  // Jan 5, 2025 00:00 UTC
        val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC
        val jan10 = 1736467200000L // Jan 10, 2025 00:00 UTC

        createEventWithOccurrence("Meeting on Jan 10", jan10)

        val results = eventsDao.searchInRange("Meeting*", jan5, jan6)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchInRange combines FTS and date filtering`() = runTest {
        val jan5 = 1736035200000L  // Jan 5, 2025 00:00 UTC
        val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC
        val jan7 = 1736208000000L  // Jan 7, 2025 00:00 UTC

        createEventWithOccurrence("Meeting on Jan 6", jan6)
        createEventWithOccurrence("Lunch on Jan 6", jan6)
        createEventWithOccurrence("Meeting on Jan 10", jan7 + 86400000 * 3) // Jan 10

        val results = eventsDao.searchInRange("Meeting*", jan5, jan7)

        assertEquals(1, results.size)
        assertEquals("Meeting on Jan 6", results[0].title)
    }

    @Test
    fun `searchInRange excludes cancelled occurrences`() = runTest {
        val jan5 = 1736035200000L  // Jan 5, 2025 00:00 UTC
        val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC
        val jan7 = 1736208000000L  // Jan 7, 2025 00:00 UTC

        createEventWithOccurrence("Active Meeting", jan6, isCancelled = false)
        createEventWithOccurrence("Cancelled Meeting", jan6, isCancelled = true)

        val results = eventsDao.searchInRange("Meeting*", jan5, jan7)

        assertEquals(1, results.size)
        assertEquals("Active Meeting", results[0].title)
    }

    @Test
    fun `searchInRange excludes PENDING_DELETE events`() = runTest {
        val jan5 = 1736035200000L  // Jan 5, 2025 00:00 UTC
        val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC
        val jan7 = 1736208000000L  // Jan 7, 2025 00:00 UTC

        createEventWithOccurrence("Active Meeting", jan6, syncStatus = SyncStatus.SYNCED)
        createEventWithOccurrence("Deleted Meeting", jan6, syncStatus = SyncStatus.PENDING_DELETE)

        val results = eventsDao.searchInRange("Meeting*", jan5, jan7)

        assertEquals(1, results.size)
        assertEquals("Active Meeting", results[0].title)
    }

    @Test
    fun `searchInRange finds event that spans the range boundary`() = runTest {
        val jan5 = 1736035200000L  // Jan 5, 2025 00:00 UTC
        val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC
        val jan7 = 1736208000000L  // Jan 7, 2025 00:00 UTC
        val jan8 = 1736294400000L  // Jan 8, 2025 00:00 UTC

        // Multi-day event: Jan 6-8 (overlaps with Jan 5-7 range)
        createEventWithOccurrence("Multi-day Meeting", jan6, endTs = jan8)

        val results = eventsDao.searchInRange("Meeting*", jan5, jan7)

        assertEquals(1, results.size)
    }

    @Test
    fun `searchInRange returns events ordered by start_ts`() = runTest {
        val jan5 = 1736035200000L  // Jan 5, 2025 00:00 UTC
        val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC
        val jan7 = 1736208000000L  // Jan 7, 2025 00:00 UTC
        val jan8 = 1736294400000L  // Jan 8, 2025 00:00 UTC

        createEventWithOccurrence("Meeting C", jan7)
        createEventWithOccurrence("Meeting A", jan5 + 3600000) // Jan 5 01:00
        createEventWithOccurrence("Meeting B", jan6)

        val results = eventsDao.searchInRange("Meeting*", jan5, jan8)

        assertEquals(3, results.size)
        assertEquals("Meeting A", results[0].title)
        assertEquals("Meeting B", results[1].title)
        assertEquals("Meeting C", results[2].title)
    }

    @Test
    fun `searchInRange returns distinct events for recurring event with multiple occurrences`() = runTest {
        val jan5 = 1736035200000L  // Jan 5, 2025 00:00 UTC
        val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC
        val jan7 = 1736208000000L  // Jan 7, 2025 00:00 UTC
        val jan8 = 1736294400000L  // Jan 8, 2025 00:00 UTC

        // Create recurring event with multiple occurrences in range
        val eventId = eventsDao.insert(
            createEvent(title = "Weekly Meeting", startTs = jan6)
        )

        // Two occurrences: Jan 6 and Jan 7
        occurrencesDao.insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = jan6,
                endTs = jan6 + 3600000,
                startDay = Occurrence.toDayFormat(jan6, false),
                endDay = Occurrence.toDayFormat(jan6, false)
            )
        )
        occurrencesDao.insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = jan7,
                endTs = jan7 + 3600000,
                startDay = Occurrence.toDayFormat(jan7, false),
                endDay = Occurrence.toDayFormat(jan7, false)
            )
        )

        val results = eventsDao.searchInRange("Weekly*", jan5, jan8)

        // Should return event only ONCE despite having 2 occurrences
        assertEquals(1, results.size)
        assertEquals("Weekly Meeting", results[0].title)
    }

    @Test
    fun `searchInRange returns empty for event with no matching occurrences`() = runTest {
        val jan5 = 1736035200000L  // Jan 5, 2025 00:00 UTC
        val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC
        val jan7 = 1736208000000L  // Jan 7, 2025 00:00 UTC

        // Event exists but no occurrence created (edge case)
        eventsDao.insert(createEvent(title = "Orphan Meeting", startTs = jan6))

        val results = eventsDao.searchInRange("Orphan*", jan5, jan7)

        assertTrue(results.isEmpty())
    }

    // ========== searchWithOccurrence Tests ("All" filter) ==========

    @Test
    fun `searchWithOccurrence returns event with startTs as nextOccurrenceTs`() = runTest {
        val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC

        eventsDao.insert(createEvent(title = "Test Meeting", startTs = jan6))

        val results = eventsDao.searchWithOccurrence("Meeting*")

        assertEquals(1, results.size)
        assertEquals(jan6, results[0].nextOccurrenceTs)
        assertEquals("Test Meeting", results[0].event.title)
    }

    @Test
    fun `searchWithOccurrence returns multiple events ordered by startTs`() = runTest {
        val jan5 = 1736035200000L
        val jan6 = 1736121600000L
        val jan7 = 1736208000000L

        eventsDao.insert(createEvent(title = "Meeting C", startTs = jan7))
        eventsDao.insert(createEvent(title = "Meeting A", startTs = jan5))
        eventsDao.insert(createEvent(title = "Meeting B", startTs = jan6))

        val results = eventsDao.searchWithOccurrence("Meeting*")

        assertEquals(3, results.size)
        assertEquals("Meeting A", results[0].event.title)
        assertEquals("Meeting B", results[1].event.title)
        assertEquals("Meeting C", results[2].event.title)
    }

    // ========== searchFutureWithOccurrence Tests (excludes past) ==========

    @Test
    fun `searchFutureWithOccurrence excludes events with all past occurrences`() = runTest {
        val pastTime = System.currentTimeMillis() - 86400000L  // Yesterday
        val futureTime = System.currentTimeMillis() + 86400000L  // Tomorrow

        createEventWithOccurrence("Past Meeting", pastTime, endTs = pastTime + 3600000)
        createEventWithOccurrence("Future Meeting", futureTime, endTs = futureTime + 3600000)

        val results = eventsDao.searchFutureWithOccurrence("Meeting*", System.currentTimeMillis())

        assertEquals(1, results.size)
        assertEquals("Future Meeting", results[0].event.title)
    }

    @Test
    fun `searchFutureWithOccurrence returns correct nextOccurrenceTs for recurring event`() = runTest {
        val now = System.currentTimeMillis()
        val pastOcc = now - 86400000L      // Yesterday
        val futureOcc1 = now + 86400000L   // Tomorrow
        val futureOcc2 = now + 2 * 86400000L  // Day after tomorrow

        // Create recurring event
        val eventId = eventsDao.insert(
            createEvent(title = "Recurring Meeting", startTs = pastOcc)
                .copy(rrule = "FREQ=DAILY")
        )

        // Past occurrence (should be ignored)
        occurrencesDao.insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = pastOcc,
                endTs = pastOcc + 3600000,
                startDay = Occurrence.toDayFormat(pastOcc, false),
                endDay = Occurrence.toDayFormat(pastOcc, false)
            )
        )
        // Future occurrence 1 (should be selected as MIN)
        occurrencesDao.insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = futureOcc1,
                endTs = futureOcc1 + 3600000,
                startDay = Occurrence.toDayFormat(futureOcc1, false),
                endDay = Occurrence.toDayFormat(futureOcc1, false)
            )
        )
        // Future occurrence 2
        occurrencesDao.insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = futureOcc2,
                endTs = futureOcc2 + 3600000,
                startDay = Occurrence.toDayFormat(futureOcc2, false),
                endDay = Occurrence.toDayFormat(futureOcc2, false)
            )
        )

        val results = eventsDao.searchFutureWithOccurrence("Recurring*", now)

        assertEquals(1, results.size)
        // Should return first FUTURE occurrence, not past one
        assertEquals(futureOcc1, results[0].nextOccurrenceTs)
    }

    @Test
    fun `searchFutureWithOccurrence excludes cancelled occurrences`() = runTest {
        val now = System.currentTimeMillis()
        val futureTime = now + 86400000L

        // Create event with cancelled occurrence
        val eventId = eventsDao.insert(createEvent(title = "Cancelled Meeting", startTs = futureTime))

        occurrencesDao.insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = futureTime,
                endTs = futureTime + 3600000,
                startDay = Occurrence.toDayFormat(futureTime, false),
                endDay = Occurrence.toDayFormat(futureTime, false),
                isCancelled = true
            )
        )

        val results = eventsDao.searchFutureWithOccurrence("Cancelled*", now)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchFutureWithOccurrence orders by nextOccurrenceTs ascending`() = runTest {
        val now = System.currentTimeMillis()
        val tomorrow = now + 86400000L
        val nextWeek = now + 7 * 86400000L

        createEventWithOccurrence("Later Meeting", nextWeek, endTs = nextWeek + 3600000)
        createEventWithOccurrence("Soon Meeting", tomorrow, endTs = tomorrow + 3600000)

        val results = eventsDao.searchFutureWithOccurrence("Meeting*", now)

        assertEquals(2, results.size)
        assertEquals("Soon Meeting", results[0].event.title)
        assertEquals("Later Meeting", results[1].event.title)
    }

    // ========== searchInRangeWithOccurrence Tests (date-filtered) ==========

    @Test
    fun `searchInRangeWithOccurrence returns nextOccurrenceTs within range`() = runTest {
        val jan5 = 1736035200000L
        val jan6 = 1736121600000L
        val jan7 = 1736208000000L
        val jan8 = 1736294400000L

        // Create recurring event with occurrences on Jan 6 and Jan 7
        val eventId = eventsDao.insert(
            createEvent(title = "Weekly Meeting", startTs = jan6)
                .copy(rrule = "FREQ=DAILY")
        )

        occurrencesDao.insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = jan6,
                endTs = jan6 + 3600000,
                startDay = Occurrence.toDayFormat(jan6, false),
                endDay = Occurrence.toDayFormat(jan6, false)
            )
        )
        occurrencesDao.insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = jan7,
                endTs = jan7 + 3600000,
                startDay = Occurrence.toDayFormat(jan7, false),
                endDay = Occurrence.toDayFormat(jan7, false)
            )
        )

        // Query Jan 6-8 range - should return Jan 6 occurrence (MIN within range)
        val results = eventsDao.searchInRangeWithOccurrence("Weekly*", jan5, jan8)

        assertEquals(1, results.size)
        assertEquals(jan6, results[0].nextOccurrenceTs)
    }

    @Test
    fun `searchInRangeWithOccurrence excludes event with no occurrence in range`() = runTest {
        val jan5 = 1736035200000L
        val jan6 = 1736121600000L
        val jan7 = 1736208000000L
        val jan10 = 1736467200000L

        // Event with occurrence on Jan 10 (outside Jan 5-7 range)
        createEventWithOccurrence("Meeting on Jan 10", jan10, endTs = jan10 + 3600000)

        val results = eventsDao.searchInRangeWithOccurrence("Meeting*", jan5, jan7)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchInRangeWithOccurrence returns null nextOccurrenceTs for non-recurring with no occurrence in range`() = runTest {
        val jan5 = 1736035200000L
        val jan6 = 1736121600000L
        val jan7 = 1736208000000L

        // Create event with occurrence in range
        createEventWithOccurrence("Meeting", jan6, endTs = jan6 + 3600000)

        val results = eventsDao.searchInRangeWithOccurrence("Meeting*", jan5, jan7)

        assertEquals(1, results.size)
        assertEquals(jan6, results[0].nextOccurrenceTs)
    }

    @Test
    fun `searchInRangeWithOccurrence orders by nextOccurrenceTs within range`() = runTest {
        val jan5 = 1736035200000L
        val jan6 = 1736121600000L
        val jan7 = 1736208000000L
        val jan8 = 1736294400000L

        createEventWithOccurrence("Meeting C", jan7, endTs = jan7 + 3600000)
        createEventWithOccurrence("Meeting A", jan5 + 3600000, endTs = jan5 + 7200000) // Jan 5 01:00
        createEventWithOccurrence("Meeting B", jan6, endTs = jan6 + 3600000)

        val results = eventsDao.searchInRangeWithOccurrence("Meeting*", jan5, jan8)

        assertEquals(3, results.size)
        assertEquals("Meeting A", results[0].event.title)
        assertEquals("Meeting B", results[1].event.title)
        assertEquals("Meeting C", results[2].event.title)
    }

    @Test
    fun `searchInRangeWithOccurrence excludes cancelled occurrences within range`() = runTest {
        val jan5 = 1736035200000L
        val jan6 = 1736121600000L
        val jan7 = 1736208000000L

        createEventWithOccurrence("Active Meeting", jan6, endTs = jan6 + 3600000, isCancelled = false)
        createEventWithOccurrence("Cancelled Meeting", jan6, endTs = jan6 + 3600000, isCancelled = true)

        val results = eventsDao.searchInRangeWithOccurrence("Meeting*", jan5, jan7)

        assertEquals(1, results.size)
        assertEquals("Active Meeting", results[0].event.title)
    }
}
