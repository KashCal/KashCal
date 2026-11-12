package org.onekash.kashcal.data.db.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.search.SearchQuery
import java.util.UUID

/**
 * Tests the app-calendar search that [EventSearchSql.nextAndLast] builds and
 * [EventsDao.searchMatches] and [EventsDao.observeSearchMatches] run: which events match (every
 * word anywhere in the title, location, description, a guest's display name or email, case
 * folded, with LIKE wildcards taken literally), which are left out (PENDING_DELETE, synthetic
 * masters, past the row limit), the order by event start, and the next, last and in-range
 * occurrence ids each match carries, including for a changed occurrence. Also covers the
 * synthetic-master exclusion in [EventsDao.suggestTitlesByPrefix].
 */
class EventsDaoSearchTest : BaseDaoTest() {

    private val accountsDao by lazy { database.accountsDao() }
    private val calendarsDao by lazy { database.calendarsDao() }
    private val eventsDao by lazy { database.eventsDao() }
    private val occurrencesDao by lazy { database.occurrencesDao() }
    private val attendeesDao by lazy { database.attendeesDao() }

    private var testCalendarId: Long = 0

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_709_650_800_000L // 2024-03-05T15:00:00Z
    private val today = 20240305

    private suspend fun search(text: String, range: LongRange? = null) =
        eventsDao.searchMatches(EventSearchSql.nextAndLast(SearchQuery.of(text), now, today, range))

    @Before
    override fun setup() {
        super.setup()
        runTest {
            val accountId = accountsDao.insert(
                Account(provider = AccountProvider.LOCAL, email = "local")
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

        val results = search("Meeting").map { it.event }

        assertEquals(1, results.size)
        assertEquals("Team Meeting", results[0].title)
    }

    @Test
    fun `search finds event by partial word with prefix`() = runTest {
        eventsDao.insert(createEvent(title = "Meeting with Team"))

        val results = search("Meet").map { it.event }

        assertEquals(1, results.size)
        assertEquals("Meeting with Team", results[0].title)
    }

    @Test
    fun `search finds event by a part in the middle of a word`() = runTest {
        eventsDao.insert(createEvent(title = "Dentist"))
        eventsDao.insert(createEvent(title = "Lunch"))

        val results = search("tist").map { it.event }

        assertEquals(1, results.size)
        assertEquals("Dentist", results[0].title)
    }

    @Test
    fun `search finds event by location`() = runTest {
        eventsDao.insert(createEvent(title = "Event", location = "Conference Room A"))

        val results = search("Conference").map { it.event }

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

        val results = search("milestones").map { it.event }

        assertEquals(1, results.size)
        assertTrue(results[0].description?.contains("milestones") == true)
    }

    @Test
    fun `search finds event by a guest display name`() = runTest {
        val id = eventsDao.insert(createEvent(title = "Checkup"))
        attendeesDao.insertAll(
            listOf(
                Attendee(
                    eventId = id,
                    address = "mailto:amara@example.test",
                    displayName = "Dr. Amara Lee"
                )
            )
        )
        eventsDao.insert(createEvent(title = "Lunch"))

        val results = search("lee").map { it.event }

        assertEquals(1, results.size)
        assertEquals("Checkup", results[0].title)
    }

    @Test
    fun `search finds event by a guest email but not by the mailto prefix`() = runTest {
        val id = eventsDao.insert(createEvent(title = "Checkup"))
        attendeesDao.insertAll(
            listOf(Attendee(eventId = id, address = "mailto:amara@example.test"))
        )

        assertEquals(listOf(id), search("amara@example.test").map { it.event.id })
        assertTrue(search("mailto").isEmpty())
    }

    // ========== Multi-Word Search Tests ==========

    @Test
    fun `search with multiple words finds matching event`() = runTest {
        eventsDao.insert(createEvent(title = "Team Weekly Standup"))
        eventsDao.insert(createEvent(title = "Project Review"))

        // Matches only events with both words.
        val results = search("Team Standup").map { it.event }

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

        val byTitle = search("Meeting")
        val byLocation = search("Office")
        val byDescription = search("Budget")

        assertEquals(1, byTitle.size)
        assertEquals(1, byLocation.size)
        assertEquals(1, byDescription.size)
    }

    // ========== LIKE wildcards are literal ==========

    @Test
    fun `search takes a percent sign literally`() = runTest {
        eventsDao.insert(createEvent(title = "100% done"))
        eventsDao.insert(createEvent(title = "Lunch"))

        val results = search("%").map { it.event }

        assertEquals(1, results.size)
        assertEquals("100% done", results[0].title)
    }

    @Test
    fun `search takes the escape character literally`() = runTest {
        eventsDao.insert(createEvent(title = "Room #4"))
        eventsDao.insert(createEvent(title = "Room 4"))

        val results = search("#4").map { it.event }

        assertEquals(1, results.size)
        assertEquals("Room #4", results[0].title)
    }

    @Test
    fun `search takes an underscore literally`() = runTest {
        eventsDao.insert(createEvent(title = "plan_b"))
        eventsDao.insert(createEvent(title = "planxb"))

        val results = search("n_b").map { it.event }

        assertEquals(1, results.size)
        assertEquals("plan_b", results[0].title)
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

        val results = search("Meeting").map { it.event }

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

        val results = search("Meeting").map { it.event }

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

        val results = search("Meeting").map { it.event }

        assertEquals(1, results.size)
    }

    // ========== Order Tests ==========

    @Test
    fun `matches with a next row come first by start, then the rest newest first`() = runTest {
        suspend fun add(title: String, start: Long) {
            val id = eventsDao.insert(createEvent(title = title, startTs = start))
            occurrencesDao.insert(
                Occurrence(eventId = id, calendarId = testCalendarId, startTs = start, endTs = start + hour, startDay = 20240305, endDay = 20240305)
            )
        }
        add("Meeting C", now + 2 * hour)
        add("Meeting A", now - 3 * day)
        add("Meeting B", now - 2 * hour)
        add("Meeting D", now + 3 * day)

        val results = search("Meeting").map { it.event }

        assertEquals(listOf("Meeting C", "Meeting D", "Meeting B", "Meeting A"), results.map { it.title })
    }

    // ========== Edge Cases ==========

    @Test
    fun `search returns empty list for no matches`() = runTest {
        eventsDao.insert(createEvent(title = "Team Meeting"))

        val results = search("NonexistentWord")

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

        val results = search("Simple")

        assertEquals(1, results.size)
    }

    @Test
    fun `search respects limit of 1000 results`() = runTest {
        repeat(1010) { i ->
            eventsDao.insert(createEvent(title = "Meeting $i"))
        }

        val results = search("Meeting")

        assertEquals(1000, results.size)
    }

    @Test
    fun `search is case insensitive`() = runTest {
        eventsDao.insert(createEvent(title = "MEETING"))
        eventsDao.insert(createEvent(title = "meeting"))
        eventsDao.insert(createEvent(title = "Meeting"))

        val results = search("meeting")

        assertEquals(3, results.size)
    }

    // ========== Multiple Events Tests ==========

    @Test
    fun `search finds multiple matching events`() = runTest {
        eventsDao.insert(createEvent(title = "Meeting with Alice"))
        eventsDao.insert(createEvent(title = "Meeting with Bob"))
        eventsDao.insert(createEvent(title = "Lunch"))

        val results = search("Meeting")

        assertEquals(2, results.size)
    }

    @Test
    fun `search finds events across different calendars`() = runTest {
        val account2Id = accountsDao.insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
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

        val results = search("Meeting")

        assertEquals(2, results.size)
    }

    // ========== Range and next occurrence ==========

    private val jan5 = 1736035200000L  // Jan 5, 2025 00:00 UTC
    private val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC
    private val jan7 = 1736208000000L  // Jan 7, 2025 00:00 UTC
    private val jan8 = 1736294400000L  // Jan 8, 2025 00:00 UTC

    /**
     * Inserts an event with one occurrence spanning [startTs]..[endTs] and returns the
     * occurrence's id.
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

        return occurrencesDao.insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = startTs,
                endTs = endTs,
                startDay = Occurrence.toDayFormat(startTs, false),
                endDay = Occurrence.toDayFormat(endTs, false),
                isCancelled = isCancelled
            )
        )
    }

    @Test
    fun `a range search dates an event by its occurrence in the range`() = runTest {
        val occurrence = createEventWithOccurrence("Meeting on Jan 6", jan6)

        val match = search("Meeting", jan5..jan7).single()

        assertEquals("Meeting on Jan 6", match.event.title)
        assertEquals(occurrence, match.rangeOccurrenceId)
    }

    @Test
    fun `a range search still returns a match outside the range, with no range row`() = runTest {
        createEventWithOccurrence("Meeting on Jan 10", jan7 + 3 * day)

        val match = search("Meeting", jan5..jan6).single()

        assertNull(match.rangeOccurrenceId)
    }

    @Test
    fun `a range search combines word matching with range rows`() = runTest {
        val inRange = createEventWithOccurrence("Meeting on Jan 6", jan6)
        createEventWithOccurrence("Lunch on Jan 6", jan6)
        createEventWithOccurrence("Meeting on Jan 10", jan7 + 3 * day)

        val byTitle = search("Meeting", jan5..jan7).associateBy { it.event.title }

        assertEquals(setOf("Meeting on Jan 6", "Meeting on Jan 10"), byTitle.keys)
        assertEquals(inRange, byTitle.getValue("Meeting on Jan 6").rangeOccurrenceId)
        assertNull(byTitle.getValue("Meeting on Jan 10").rangeOccurrenceId)
    }

    @Test
    fun `a cancelled occurrence is never the range row`() = runTest {
        val active = createEventWithOccurrence("Active Meeting", jan6, isCancelled = false)
        createEventWithOccurrence("Cancelled Meeting", jan6, isCancelled = true)

        val byTitle = search("Meeting", jan5..jan7).associateBy { it.event.title }

        assertEquals(active, byTitle.getValue("Active Meeting").rangeOccurrenceId)
        assertNull(byTitle.getValue("Cancelled Meeting").rangeOccurrenceId)
    }

    @Test
    fun `a range search excludes PENDING_DELETE events`() = runTest {
        createEventWithOccurrence("Active Meeting", jan6, syncStatus = SyncStatus.SYNCED)
        createEventWithOccurrence("Deleted Meeting", jan6, syncStatus = SyncStatus.PENDING_DELETE)

        val results = search("Meeting", jan5..jan7).map { it.event }

        assertEquals(listOf("Active Meeting"), results.map { it.title })
    }

    @Test
    fun `an occurrence spanning the range boundary is the range row`() = runTest {
        // A multi-day event, Jan 6-8, overlapping the Jan 5-7 range.
        val occurrence = createEventWithOccurrence("Multi-day Meeting", jan6, endTs = jan8)

        val match = search("Meeting", jan5..jan7).single()

        assertEquals(occurrence, match.rangeOccurrenceId)
    }

    @Test
    fun `a series with several occurrences in the range is one match dated by the first`() = runTest {
        val eventId = eventsDao.insert(
            createEvent(title = "Weekly Meeting", startTs = jan6).copy(rrule = "FREQ=DAILY")
        )
        val first = occurrencesDao.insert(
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

        val match = search("Weekly", jan5..jan8).single()

        assertEquals("Weekly Meeting", match.event.title)
        assertEquals(first, match.rangeOccurrenceId)
    }

    @Test
    fun `an event with no occurrence row has no range row`() = runTest {
        eventsDao.insert(createEvent(title = "Orphan Meeting", startTs = jan6))

        val match = search("Orphan", jan5..jan7).single()

        assertNull(match.rangeOccurrenceId)
        assertFalse(match.hasOccurrences)
    }

    @Test
    fun `an event whose occurrences all ended has a last row and no next row`() = runTest {
        val past = createEventWithOccurrence("Past Meeting", now - day)
        val future = createEventWithOccurrence("Future Meeting", now + day)

        val byTitle = search("Meeting").associateBy { it.event.title }

        assertNull(byTitle.getValue("Past Meeting").nextOccurrenceId)
        assertEquals(past, byTitle.getValue("Past Meeting").lastOccurrenceId)
        assertEquals(future, byTitle.getValue("Future Meeting").nextOccurrenceId)
        assertNull(byTitle.getValue("Future Meeting").lastOccurrenceId)
    }

    @Test
    fun `a one-off whose only occurrence is cancelled has no next or last row`() = runTest {
        createEventWithOccurrence("Cancelled Meeting", now + day, isCancelled = true)

        val match = search("Cancelled").single()

        assertNull(match.nextOccurrenceId)
        assertNull(match.lastOccurrenceId)
        assertTrue(match.hasOccurrences)
    }

    // ========== Synthetic master leak prevention (#227) ==========

    // Synthetic masters (#227), placeholders that ICS subscriptions and the CalDAV pull create
    // for exceptions without a master, carry `X-KASHCAL-SYNTHETIC-MASTER` in extra_properties.
    // They are foreign-key targets for those exceptions, not real events, and must never
    // surface in search or in `suggestTitlesByPrefix`.
    //
    // The filter tests the sentinel, not status='CANCELLED': CalDAV and iCloud servers and the
    // device CalendarProvider all keep legitimate cancelled events (RFC 5545 §3.8.1.11), and
    // those stay searchable.
    @Test
    fun `search excludes synthetic master but keeps genuine cancelled events`() = runTest {
        // A synthetic master.
        eventsDao.insert(createEvent(title = "Synthetic Placeholder").copy(
            status = "CANCELLED",
            extraProperties = mapOf("X-KASHCAL-SYNTHETIC-MASTER" to "true")
        ))
        // A legitimate cancelled event.
        eventsDao.insert(createEvent(title = "Real Cancelled Meeting").copy(
            status = "CANCELLED"
        ))
        // An active event.
        eventsDao.insert(createEvent(title = "Active Meeting"))

        val titles = (search("Meeting") + search("Placeholder")).map { it.event.title }.toSet()

        assertTrue(
            "search must NOT return synthetic placeholder",
            "Synthetic Placeholder" !in titles
        )
        assertTrue(
            "search MUST keep genuine cancelled events (CalDAV/iCloud/device)",
            "Real Cancelled Meeting" in titles
        )
        assertTrue("search returns active events", "Active Meeting" in titles)
    }

    @Test
    fun `suggestTitlesByPrefix excludes synthetic master but keeps genuine cancelled events`() = runTest {
        val sinceMs = now - 30L * 24 * 3600 * 1000
        val untilMs = now + 30L * 24 * 3600 * 1000

        // Two legitimate cancelled events with the same title, meeting minFreq=2.
        eventsDao.insert(createEvent(title = "Real Cancelled", startTs = now - 1000).copy(
            status = "CANCELLED"
        ))
        eventsDao.insert(createEvent(title = "Real Cancelled", startTs = now - 500).copy(
            status = "CANCELLED"
        ))
        // Two synthetic masters with the same title, which would meet minFreq=2.
        eventsDao.insert(createEvent(title = "Synthetic Placeholder", startTs = now - 800).copy(
            status = "CANCELLED",
            extraProperties = mapOf("X-KASHCAL-SYNTHETIC-MASTER" to "true")
        ))
        eventsDao.insert(createEvent(title = "Synthetic Placeholder", startTs = now - 600).copy(
            status = "CANCELLED",
            extraProperties = mapOf("X-KASHCAL-SYNTHETIC-MASTER" to "true")
        ))

        val suggestions = eventsDao.suggestTitlesByPrefix(
            prefix = "",
            sinceMs = sinceMs,
            untilMs = untilMs,
            minFreq = 2,
            limit = 10
        )
        val titles = suggestions.map { it.title }.toSet()

        assertTrue("synthetic excluded", "Synthetic Placeholder" !in titles)
        assertTrue("genuine cancelled kept", "Real Cancelled" in titles)
    }

    // ========== Next and last occurrence (observeSearchMatches) ==========

    private suspend fun nextAndLast(text: String, range: LongRange? = null) =
        eventsDao.observeSearchMatches(EventSearchSql.nextAndLast(SearchQuery.of(text), now, today, range)).first()

    private suspend fun row(eventId: Long, start: Long, end: Long = start + hour, exceptionId: Long? = null,
                            cancelled: Boolean = false, startDay: Int = 20240101, endDay: Int = startDay): Long =
        occurrencesDao.insert(
            Occurrence(
                eventId = eventId, calendarId = testCalendarId, startTs = start, endTs = end,
                startDay = startDay, endDay = endDay, isCancelled = cancelled, exceptionEventId = exceptionId,
            )
        )

    private suspend fun series(title: String, start: Long): Long =
        eventsDao.insert(createEvent(title = title, startTs = start).copy(rrule = "FREQ=WEEKLY"))

    @Test
    fun `next is the earliest row not ended and last the latest ended`() = runTest {
        val id = series("Standup", now - 21 * day)
        row(id, now - 21 * day)
        val lastId = row(id, now - 14 * day)
        val nextId = row(id, now + 7 * day)
        row(id, now + 14 * day)

        val match = nextAndLast("standup").single()

        assertEquals(nextId, match.nextOccurrenceId)
        assertEquals(lastId, match.lastOccurrenceId)
        assertTrue(match.hasOccurrences)
        assertNull(match.rangeOccurrenceId)
    }

    @Test
    fun `a timed row in progress is next, not last`() = runTest {
        val id = eventsDao.insert(createEvent(title = "Call", startTs = now - hour))
        val inProgress = row(id, now - hour, end = now + hour)

        val match = nextAndLast("call").single()

        assertEquals(inProgress, match.nextOccurrenceId)
        assertNull(match.lastOccurrenceId)
    }

    @Test
    fun `an all-day row today is next until the day ends`() = runTest {
        val midnight = now - 15 * hour
        val id = eventsDao.insert(createEvent(title = "Holiday", startTs = midnight).copy(isAllDay = true, endTs = midnight + day - 1))
        // The row ended by the clock, but its end day is today.
        val todayRow = row(id, midnight, end = now - hour, startDay = today, endDay = today)
        val yesterday = row(id, midnight - day, end = midnight - 1, startDay = 20240304, endDay = 20240304)

        val match = nextAndLast("holiday").single()

        assertEquals(todayRow, match.nextOccurrenceId)
        assertEquals(yesterday, match.lastOccurrenceId)
    }

    @Test
    fun `cancelled rows are never next or last`() = runTest {
        val id = series("Yoga", now - 7 * day)
        row(id, now - 7 * day, cancelled = true)
        row(id, now + 7 * day, cancelled = true)

        val match = nextAndLast("yoga").single()

        assertNull(match.nextOccurrenceId)
        assertNull(match.lastOccurrenceId)
        assertTrue("rows exist, so it is not dated by its own start", match.hasOccurrences)
    }

    @Test
    fun `a changed occurrence is dated by the master row linked to it and the master skips that row`() = runTest {
        val master = series("Review", now - 14 * day)
        val exception = eventsDao.insert(
            createEvent(title = "Review moved", startTs = now - 6 * day).copy(originalEventId = master, originalInstanceTime = now - 7 * day)
        )
        val masterLast = row(master, now - 14 * day)
        val linked = row(master, now - 6 * day, exceptionId = exception)

        val byId = nextAndLast("review").associateBy { it.event.id }

        assertEquals(masterLast, byId.getValue(master).lastOccurrenceId)
        assertEquals(linked, byId.getValue(exception).lastOccurrenceId)
        assertNull(byId.getValue(exception).nextOccurrenceId)
        assertTrue(byId.getValue(exception).hasOccurrences)
    }

    @Test
    fun `a renamed occurrence is found when only its title matches`() = runTest {
        val master = series("Weekly sync", now - 14 * day)
        val exception = eventsDao.insert(
            createEvent(title = "Dermatology", startTs = now - 6 * day).copy(originalEventId = master, originalInstanceTime = now - 7 * day)
        )
        val linked = row(master, now - 6 * day, exceptionId = exception)
        row(master, now - 14 * day)

        val match = nextAndLast("dermatology").single()

        assertEquals(exception, match.event.id)
        assertEquals(linked, match.lastOccurrenceId)
    }

    @Test
    fun `an event with no rows reports none`() = runTest {
        eventsDao.insert(createEvent(title = "Imported", startTs = now - day))

        val match = nextAndLast("imported").single()

        assertFalse(match.hasOccurrences)
        assertNull(match.nextOccurrenceId)
        assertNull(match.lastOccurrenceId)
    }

    @Test
    fun `the range row is the earliest overlapping the range`() = runTest {
        val id = series("Gym", now - 21 * day)
        row(id, now - 21 * day)
        val first = row(id, now - 14 * day)
        row(id, now - 7 * day)

        val match = nextAndLast("gym", range = (now - 15 * day)..(now - 6 * day)).single()

        assertEquals(first, match.rangeOccurrenceId)
    }

    @Test
    fun `pending deletes and synthetic masters are not matched`() = runTest {
        eventsDao.insert(createEvent(title = "Gone", syncStatus = SyncStatus.PENDING_DELETE))
        eventsDao.insert(
            createEvent(title = "Gone placeholder").copy(extraProperties = mapOf("X-KASHCAL-SYNTHETIC-MASTER" to "true"))
        )

        assertTrue(nextAndLast("gone").isEmpty())
    }

    @Test
    fun `the observed search emits again when an occurrence row lands`() = runTest {
        val id = series("Choir", now - 7 * day)
        val flow = eventsDao.observeSearchMatches(EventSearchSql.nextAndLast(SearchQuery.of("choir"), now, today))
        assertFalse(flow.first().single().hasOccurrences)

        row(id, now - 7 * day)

        assertTrue(flow.first().single().hasOccurrences)
    }

    @Test
    fun `a changed occurrence of a series being deleted is not matched`() = runTest {
        val master = eventsDao.insert(createEvent(title = "Team sync", syncStatus = SyncStatus.PENDING_DELETE).copy(rrule = "FREQ=WEEKLY"))
        // Deleting a synced series removes the master's rows and leaves its changed occurrences
        // SYNCED.
        eventsDao.insert(createEvent(title = "Team sync moved").copy(originalEventId = master, originalInstanceTime = now))

        assertTrue(search("team sync").isEmpty())
    }

    @Test
    fun `the result cap keeps upcoming matches before old ones`() = runTest {
        repeat(1001) { i -> eventsDao.insert(createEvent(title = "Meeting old $i", startTs = now - (i + 1) * day)) }
        val future = eventsDao.insert(createEvent(title = "Meeting next", startTs = now + day))
        occurrencesDao.insert(
            Occurrence(eventId = future, calendarId = testCalendarId, startTs = now + day, endTs = now + day + hour, startDay = 20240306, endDay = 20240306)
        )

        val rows = search("meeting")

        assertEquals(1000, rows.size)
        assertTrue(rows.any { it.event.id == future })
        assertEquals("the newest past ones are kept", "Meeting old 0", rows.first { it.event.id != future }.event.title)
    }
}
