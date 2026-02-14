package org.onekash.kashcal.sync.strategy

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.session.SyncSessionStore

/**
 * Tests for the exception event duplicate bug.
 *
 * Bug: When exception is created on iPhone, KashCal sometimes interprets
 * the whole entry as new, causing duplicate events.
 *
 * Run with: ./gradlew testDebugUnitTest --tests "*ExceptionDuplicateBugTest*"
 */
class ExceptionDuplicateBugTest {

    private lateinit var pullStrategy: PullStrategy
    private lateinit var parser: ICalParser

    private lateinit var database: KashCalDatabase
    private lateinit var client: CalDavClient
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var eventsDao: EventsDao
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var dataStore: KashCalDataStore
    private lateinit var syncSessionStore: SyncSessionStore

    private val quirks = ICloudQuirks()

    // Track upsert calls to detect duplicates
    private val upsertedEvents = mutableListOf<Event>()

    @Before
    fun setup() {
        parser = ICalParser()

        database = mockk(relaxed = true)
        client = mockk(relaxed = true)
        calendarRepository = mockk(relaxed = true)
        eventsDao = mockk(relaxed = true)
        occurrenceGenerator = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        syncSessionStore = mockk(relaxed = true)

        // Mock database.runInTransaction
        coEvery {
            database.runInTransaction(any<suspend () -> Any>())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = firstArg<suspend () -> Any>()
            block()
        }

        // Track upsert calls
        // @Upsert returns:
        // - Row ID (positive) for INSERT
        // - -1L for UPDATE (when entity already exists by primary key)
        coEvery { eventsDao.upsert(capture(upsertedEvents)) } answers {
            val event = upsertedEvents.last()
            if (event.id > 0) {
                // Event has existing ID - this is an update, return -1
                -1L
            } else {
                // New event - assign sequential ID
                upsertedEvents.size.toLong()
            }
        }

        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = quirks,
            dataStore = dataStore,
            syncSessionStore = syncSessionStore
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
        upsertedEvents.clear()
    }

    // ========== Timestamp Consistency Tests ==========

    @Test
    fun `RECURRENCE-ID timestamp is consistent across parses`() {
        // This verifies that parsing the same ICS twice produces identical timestamps
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:timestamp-test@kashcal.test
            DTSTAMP:20250125T100000Z
            DTSTART:20250120T100000Z
            DTEND:20250120T110000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly Meeting
            END:VEVENT
            BEGIN:VEVENT
            UID:timestamp-test@kashcal.test
            DTSTAMP:20250125T100000Z
            RECURRENCE-ID:20250127T100000Z
            DTSTART:20250127T140000Z
            DTEND:20250127T150000Z
            SUMMARY:Moved to afternoon
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Parse twice
        val events1 = parser.parseAllEvents(ics).getOrNull()!!
        val events2 = parser.parseAllEvents(ics).getOrNull()!!

        val exception1 = events1.find { it.recurrenceId != null }!!
        val exception2 = events2.find { it.recurrenceId != null }!!

        // Timestamps should be identical
        assertEquals(
            "RECURRENCE-ID timestamp should be consistent",
            exception1.recurrenceId!!.timestamp,
            exception2.recurrenceId!!.timestamp
        )

        // Log the actual value for debugging
        println("RECURRENCE-ID timestamp: ${exception1.recurrenceId!!.timestamp}")
        println("Expected: 20250127T100000Z = Mon Jan 27 2025 10:00 UTC")
    }

    @Test
    fun `originalInstanceTime is consistent when mapped to entity`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:entity-test@kashcal.test
            DTSTAMP:20250125T100000Z
            RECURRENCE-ID:20250127T100000Z
            DTSTART:20250127T140000Z
            DTEND:20250127T150000Z
            SUMMARY:Exception Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val exception = events.first()

        // Map to entity twice
        val entity1 = ICalEventMapper.toEntity(exception, ics, 1L, "/cal/event.ics", "etag1")
        val entity2 = ICalEventMapper.toEntity(exception, ics, 1L, "/cal/event.ics", "etag2")

        assertEquals(
            "originalInstanceTime should be consistent",
            entity1.originalInstanceTime,
            entity2.originalInstanceTime
        )

        println("originalInstanceTime: ${entity1.originalInstanceTime}")
    }

    // ========== Duplicate Scenario Tests ==========

    @Test
    fun `second sync of exception event should update not create duplicate`() = runTest {
        // Setup: First sync already created master (id=100) and exception (id=101)
        val calendar = createCalendar()
        val eventUrl = "${calendar.caldavUrl}recurring.ics"

        val existingMaster = Event(
            id = 100L,
            calendarId = calendar.id,
            uid = "recurring-uid",
            title = "Weekly Meeting",
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            startTs = parseDate("2025-01-20 10:00"),
            endTs = parseDate("2025-01-20 11:00"),
            dtstamp = parseDate("2025-01-25 10:00"),
            caldavUrl = eventUrl,
            etag = "etag-v1",
            syncStatus = SyncStatus.SYNCED
        )

        val existingException = Event(
            id = 101L,
            calendarId = calendar.id,
            uid = "recurring-uid",
            title = "Moved to afternoon",
            startTs = parseDate("2025-01-27 14:00"),
            endTs = parseDate("2025-01-27 15:00"),
            dtstamp = parseDate("2025-01-25 10:00"),
            originalEventId = 100L,
            originalInstanceTime = parseDate("2025-01-27 10:00"),
            caldavUrl = eventUrl,
            etag = "etag-v1",
            syncStatus = SyncStatus.SYNCED
        )

        // Server returns same ICS with updated etag (exception was modified on iPhone)
        val icsWithException = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud//EN
            BEGIN:VEVENT
            UID:recurring-uid
            DTSTAMP:20250125T100000Z
            DTSTART:20250120T100000Z
            DTEND:20250120T110000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly Meeting
            END:VEVENT
            BEGIN:VEVENT
            UID:recurring-uid
            DTSTAMP:20250126T120000Z
            RECURRENCE-ID:20250127T100000Z
            DTSTART:20250127T150000Z
            DTEND:20250127T160000Z
            SUMMARY:Moved to 3pm (updated on iPhone)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Setup mocks
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair("recurring.ics", "etag-v2")))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "recurring.ics",
                    url = eventUrl,
                    etag = "etag-v2", // New etag
                    icalData = icsWithException
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        // Master lookup should succeed
        coEvery { eventsDao.getMasterByUidAndCalendar("recurring-uid", calendar.id) } returns existingMaster
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingMaster
        coEvery { eventsDao.getByUid("recurring-uid") } returns listOf(existingMaster, existingException)

        // Exception lookup - THIS IS THE KEY
        // Should find existing exception by UID + instance time (RFC 5545 compliant)
        val capturedTimestamps = mutableListOf<Long>()
        coEvery {
            eventsDao.getExceptionByUidAndInstanceTime("recurring-uid", calendar.id, capture(capturedTimestamps))
        } answers {
            val ts = capturedTimestamps.lastOrNull()
            println("getExceptionByUidAndInstanceTime called with uid=recurring-uid, timestamp=$ts")
            println("Expected timestamp: ${parseDate("2025-01-27 10:00")}")
            existingException
        }

        // Execute pull
        val result = pullStrategy.pull(calendar, forceFullSync = true, client = client)

        // Verify: Should update existing exception, NOT create duplicate
        assertTrue("Pull should succeed", result is PullResult.Success)

        // Count how many exception events were upserted
        val exceptionUpserts = upsertedEvents.filter { it.originalEventId != null }

        println("=== Upserted Events ===")
        upsertedEvents.forEach { event ->
            println("- ${event.title}")
            println("  id: ${event.id}")
            println("  originalEventId: ${event.originalEventId}")
            println("  originalInstanceTime: ${event.originalInstanceTime}")
        }

        // There should be exactly 1 exception upsert (update), not 2 (duplicate)
        assertEquals(
            "Should upsert exactly 1 exception event (update, not duplicate)",
            1,
            exceptionUpserts.size
        )

        // The upserted exception should preserve the existing ID
        val upsertedException = exceptionUpserts.first()
        assertEquals("Should preserve existing exception ID", 101L, upsertedException.id)
    }

    @Test
    fun `UID-based lookup finds exception when masterEventId changes`() = runTest {
        // This test verifies the RFC 5545 compliant lookup:
        // When master event is recreated with a new ID, UID + originalInstanceTime still finds the existing exception

        val calendar = createCalendar()
        val eventUrl = "${calendar.caldavUrl}recurring.ics"

        // Old master (was deleted/recreated with new ID)
        val oldMasterId = 100L

        // Existing exception linked to OLD master ID
        // Per RFC 5545, exception shares UID with master and has originalInstanceTime
        val existingException = Event(
            id = 101L,
            calendarId = calendar.id,
            uid = "recurring-uid", // Same UID as master (RFC 5545)
            importId = "recurring-uid:RECID:20250127T100000Z",
            title = "Moved to afternoon",
            startTs = parseDate("2025-01-27 14:00"),
            endTs = parseDate("2025-01-27 15:00"),
            dtstamp = parseDate("2025-01-25 10:00"),
            originalEventId = oldMasterId, // Points to OLD master (stale ID)
            originalInstanceTime = parseDate("2025-01-27 10:00"), // Stable identifier
            caldavUrl = eventUrl,
            etag = "etag-v1",
            syncStatus = SyncStatus.SYNCED
        )

        val icsWithException = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud//EN
            BEGIN:VEVENT
            UID:recurring-uid
            DTSTAMP:20250125T100000Z
            DTSTART:20250120T100000Z
            DTEND:20250120T110000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly Meeting
            END:VEVENT
            BEGIN:VEVENT
            UID:recurring-uid
            DTSTAMP:20250126T120000Z
            RECURRENCE-ID:20250127T100000Z
            DTSTART:20250127T150000Z
            DTEND:20250127T160000Z
            SUMMARY:Moved to 3pm
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Setup mocks
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair("recurring.ics", "etag-v2")))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "recurring.ics",
                    url = eventUrl,
                    etag = "etag-v2",
                    icalData = icsWithException
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        // Master lookup returns null (master wasn't in DB)
        coEvery { eventsDao.getMasterByUidAndCalendar("recurring-uid", calendar.id) } returns null
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.getByUid("recurring-uid") } returns listOf(existingException)

        // UID-based lookup succeeds! Uses stable identifiers (UID + originalInstanceTime)
        // This works even when master ID changes because it doesn't depend on local DB IDs
        coEvery {
            eventsDao.getExceptionByUidAndInstanceTime(
                uid = "recurring-uid",
                calendarId = calendar.id,
                originalInstanceTime = any()
            )
        } returns existingException

        // Execute pull
        val result = pullStrategy.pull(calendar, forceFullSync = true, client = client)

        println("=== Upserted Events (With importId Fallback) ===")
        upsertedEvents.forEach { event ->
            println("- ${event.title}")
            println("  id: ${event.id}")
            println("  originalEventId: ${event.originalEventId}")
        }

        assertTrue("Pull should succeed", result is PullResult.Success)

        // With UID-based lookup, the exception should be UPDATED (id=101), not created as duplicate
        // This works because UID + originalInstanceTime are server-stable identifiers
        val exceptionUpserts = upsertedEvents.filter { it.originalEventId != null }
        assertEquals("Should have exactly 1 exception upsert", 1, exceptionUpserts.size)
        assertEquals("Should preserve existing exception ID via UID lookup", 101L, exceptionUpserts.first().id)
    }

    // ========== Helper Methods ==========

    private fun createCalendar(
        id: Long = 1,
        ctag: String? = null,
        syncToken: String? = null
    ) = Calendar(
        id = id,
        accountId = 1L,
        caldavUrl = "https://caldav.icloud.com/123456/calendars/test/",
        displayName = "Test Calendar",
        color = -1,
        ctag = ctag,
        syncToken = syncToken,
        isVisible = true,
        isDefault = false,
        isReadOnly = false,
        sortOrder = 0
    )

    private fun parseDate(dateStr: String): Long {
        val parts = dateStr.split(" ")
        val dateParts = parts[0].split("-")
        val timeParts = parts[1].split(":")
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.set(
            dateParts[0].toInt(),
            dateParts[1].toInt() - 1,
            dateParts[2].toInt(),
            timeParts[0].toInt(),
            timeParts[1].toInt(),
            0
        )
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
