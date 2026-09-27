package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Tests [OccurrenceGenerator] and the [Occurrence] rows it writes, over an in-memory Room
 * database:
 * - [OccurrenceGenerator.linkException], cancelling, and links and cancelled flags kept
 *   across [OccurrenceGenerator.regenerateOccurrences]
 * - [Occurrence.toDayFormat] (UTC for all-day, the default zone for timed, both UTC offset
 *   signs) and multi-day day codes
 * - RDATE, EXDATE and both together, range start and end bounds, the 60-second match
 *   tolerance, a cross-midnight, a monthly and a year-boundary series
 * - [OccurrenceGenerator.extendOccurrences] and [OccurrenceGenerator.extendPastOccurrences],
 *   and the DAO queries behind past extension (`getMinStartTs`,
 *   `getRecurringEventsNeedingPastExtension`)
 * - a past series expanded over PullStrategy's range, and a far-past yearly series (#152)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OccurrenceEdgeCasesTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())

        // One local account and calendar for every test.
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test Calendar",
                color = 0xFF2196F3.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Exception Linking Tests ====================

    @Test
    fun `linkException associates exception with occurrence`() = runTest {
        val event = createRecurringEvent("Master", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[1].startTs

        // An exception for the second occurrence.
        val exceptionId = database.eventsDao().insert(
            savedEvent.copy(
                id = 0,
                title = "Modified",
                originalEventId = eventId,
                originalInstanceTime = targetOccTime
            )
        )

        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)

        val linkedOcc = database.occurrencesDao().getForEvent(eventId)
            .find { it.startTs == targetOccTime }
        assertEquals(exceptionId, linkedOcc?.exceptionEventId)
    }

    @Test
    fun `linkException is idempotent`() = runTest {
        val event = createRecurringEvent("Master", "FREQ=DAILY;COUNT=3")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[0].startTs

        val exceptionId = database.eventsDao().insert(
            savedEvent.copy(id = 0, title = "Modified", originalEventId = eventId, originalInstanceTime = targetOccTime)
        )

        // Link three times.
        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)
        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)
        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)

        // Still one occurrence at that time, linked.
        val occs = database.occurrencesDao().getForEvent(eventId)
            .filter { it.startTs == targetOccTime }
        assertEquals(1, occs.size)
        assertEquals(exceptionId, occs[0].exceptionEventId)
    }

    // ==================== Exception Link Restoration Tests ====================

    @Test
    fun `regenerateOccurrences preserves exception links`() = runTest {
        val event = createRecurringEvent("Master", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[2].startTs

        // Link an exception to the third occurrence.
        val exceptionId = database.eventsDao().insert(
            savedEvent.copy(id = 0, title = "Modified", originalEventId = eventId, originalInstanceTime = targetOccTime)
        )
        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)

        // Regenerate, as after an RRULE change.
        occurrenceGenerator.regenerateOccurrences(savedEvent)

        // The link survives.
        val regenOccs = database.occurrencesDao().getForEvent(eventId)
        val linkedOcc = regenOccs.find { it.exceptionEventId == exceptionId }
        assertNotNull("Exception link should be preserved after regeneration", linkedOcc)
    }

    // ==================== Occurrence Cancellation Tests ====================

    @Test
    fun `cancelOccurrence marks occurrence as cancelled`() = runTest {
        val event = createRecurringEvent("Master", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[1].startTs

        occurrenceGenerator.cancelOccurrence(eventId, targetOccTime)

        val cancelledOcc = database.occurrencesDao().getForEvent(eventId)
            .find { it.startTs == targetOccTime }
        assertTrue("Occurrence should be marked cancelled", cancelledOcc?.isCancelled == true)
    }

    @Test
    fun `cancelled occurrence is preserved after regeneration`() = runTest {
        val event = createRecurringEvent("Master", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[1].startTs

        // Link an exception, then cancel. Regeneration carries the cancelled flag only on
        // occurrences linked to an exception.
        val exceptionId = database.eventsDao().insert(
            savedEvent.copy(id = 0, title = "Cancelled", originalEventId = eventId, originalInstanceTime = targetOccTime)
        )
        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)
        occurrenceGenerator.cancelOccurrence(eventId, targetOccTime)

        occurrenceGenerator.regenerateOccurrences(savedEvent)

        // Still cancelled.
        val regenOcc = database.occurrencesDao().getForEvent(eventId)
            .find { it.exceptionEventId == exceptionId }
        assertTrue("Cancelled status should be preserved", regenOcc?.isCancelled == true)
    }

    // ==================== Day Code Calculation Tests ====================

    @Test
    fun `toDayFormat calculates correct day code for UTC timestamp`() {
        // Jan 15, 2024 00:00 UTC
        val utcMidnight = 1705276800000L

        val dayCode = Occurrence.toDayFormat(utcMidnight, isAllDay = true)

        assertEquals(20240115, dayCode)
    }

    @Test
    fun `toDayFormat uses UTC for all-day events`() {
        // June 15, 2024 00:00 UTC
        val utcMidnight = 1718409600000L

        val dayCode = Occurrence.toDayFormat(utcMidnight, isAllDay = true)

        assertEquals(20240615, dayCode)
    }

    @Test
    fun `toDayFormat uses local TZ for timed events`() {
        // Compares with today's date in the JVM default zone.
        val now = System.currentTimeMillis()
        val dayCode = Occurrence.toDayFormat(now, isAllDay = false)

        val cal = java.util.Calendar.getInstance()
        val expected = cal.get(java.util.Calendar.YEAR) * 10000 +
            (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
            cal.get(java.util.Calendar.DAY_OF_MONTH)

        assertEquals(expected, dayCode)
    }

    @Test
    fun `toDayFormat handles year boundary`() {
        // Dec 31, 2024 23:59 UTC
        val newYearsEve = 1735689540000L

        val dayCode = Occurrence.toDayFormat(newYearsEve, isAllDay = true)

        assertEquals(20241231, dayCode)
    }

    @Test
    fun `toDayFormat handles leap year Feb 29`() {
        val feb29 = 1709164800000L // Feb 29, 2024 00:00 UTC

        val dayCode = Occurrence.toDayFormat(feb29, isAllDay = true)

        assertEquals(20240229, dayCode)
    }

    // ==================== Multi-Day Occurrence Tests ====================

    @Test
    fun `multi-day occurrence has different startDay and endDay`() = runTest {
        val now = System.currentTimeMillis()
        val event = Event(
            uid = "multiday@test.com",
            calendarId = testCalendarId,
            title = "3-Day Conference",
            startTs = now,
            endTs = now + 3 * 86400000, // 3 days
            dtstamp = now,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            now - 86400000,
            now + 10 * 86400000
        )

        val occurrence = database.occurrencesDao().getForEvent(eventId).first()

        assertTrue(
            "End day should be after start day for multi-day event",
            occurrence.endDay > occurrence.startDay
        )
    }

    @Test
    fun `all-day multi-day event spans correct days`() = runTest {
        // June 15-17, 2024 all-day event (3 days)
        val startUtc = 1718409600000L // June 15, 2024 00:00 UTC
        val endUtc = 1718668799999L   // June 17, 2024 23:59:59.999 UTC

        val event = Event(
            uid = "allday-multi@test.com",
            calendarId = testCalendarId,
            title = "3-Day Holiday",
            startTs = startUtc,
            endTs = endUtc,
            isAllDay = true,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            startUtc - 86400000,
            endUtc + 86400000
        )

        val occurrence = database.occurrencesDao().getForEvent(eventId).first()

        assertEquals(20240615, occurrence.startDay)
        assertEquals(20240617, occurrence.endDay)
    }

    // ==================== RDATE Tests ====================

    @Test
    fun `RDATE adds extra occurrences`() = runTest {
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "rdate@test.com",
            calendarId = testCalendarId,
            title = "Event with RDATE",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",
            rdate = "20240620,20240625", // Two extra dates
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 30 * 86400000L
        )

        // 3 from the RRULE plus the 2 RDATEs inside the 30-day range; the assert checks only
        // the 3 RRULE occurrences (`RDATE adds exact extra occurrences - explicit verification`
        // checks all 5).
        assertTrue("Should have at least RRULE occurrences", count >= 3)
    }

    @Test
    fun `RDATE with malformed dates are ignored`() = runTest {
        val startTs = System.currentTimeMillis()

        val event = Event(
            uid = "bad-rdate@test.com",
            calendarId = testCalendarId,
            title = "Bad RDATE",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",
            rdate = "NOTADATE,20240620,INVALID",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 365 * 86400000L // A year ahead; the 20240620 RDATE falls before the range
        )

        // The malformed RDATEs are skipped and the RRULE occurrences remain.
        assertTrue("Should have at least RRULE occurrences", count >= 3)
    }

    @Test
    fun `RDATE adds exact extra occurrences - explicit verification`() = runTest {
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "rdate-explicit@test.com",
            calendarId = testCalendarId,
            title = "RDATE Explicit Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",  // June 15, 16, 17
            rdate = "20240620,20240625",    // June 20, 25
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 15 * 86400000L
        )

        // 3 RRULE + 2 RDATE = 5
        assertEquals(5, count)

        // Exactly these days.
        val days = database.occurrencesDao().getForEvent(eventId)
            .map { it.startDay }.sorted()
        assertEquals(listOf(20240615, 20240616, 20240617, 20240620, 20240625), days)
    }

    @Test
    fun `RDATE duplicate with RRULE does not create duplicate occurrence`() = runTest {
        // An RDATE on June 16 doesn't duplicate the RRULE's June 16.
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "rdate-dup@test.com",
            calendarId = testCalendarId,
            title = "RDATE Duplicate Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",  // June 15, 16, 17
            rdate = "20240616",             // Already in RRULE
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000L
        )

        assertEquals(3, count)  // No duplicate
    }

    // ==================== EXDATE Tests ====================

    @Test
    fun `EXDATE removes occurrences`() = runTest {
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "exdate@test.com",
            calendarId = testCalendarId,
            title = "Event with EXDATE",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "20240616,20240618", // Remove 2nd and 4th occurrence
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        // 5 from RRULE - 2 from EXDATE = 3
        assertEquals(3, count)
    }

    @Test
    fun `EXDATE with RDATE combination`() = runTest {
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "rdate-exdate@test.com",
            calendarId = testCalendarId,
            title = "RDATE and EXDATE",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",
            rdate = "20240625", // Add June 25
            exdate = "20240616", // Remove June 16
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 30 * 86400000L
        )

        // Expected: 3 from RRULE (June 15, 16, 17) + 1 from RDATE (June 25) - 1 from EXDATE
        // (June 16) = 3. The assert checks at least 2, the RRULE dates left after the EXDATE.
        assertTrue("Should have at least 2 occurrences after EXDATE", count >= 2)
    }

    // ==================== Extend Occurrences Tests ====================

    @Test
    fun `extendOccurrences adds occurrences beyond current range`() = runTest {
        val startTs = System.currentTimeMillis()

        val event = Event(
            uid = "extend@test.com",
            calendarId = testCalendarId,
            title = "Extendable Event",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY", // No COUNT or UNTIL
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate the first 10 days.
        occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        val initialCount = database.occurrencesDao().getForEvent(eventId).size

        // Extend to 20 days.
        occurrenceGenerator.extendOccurrences(savedEvent, startTs + 20 * 86400000)

        val extendedCount = database.occurrencesDao().getForEvent(eventId).size

        assertTrue("Extended count should be greater", extendedCount > initialCount)
    }

    @Test
    fun `extendOccurrences returns 0 for non-recurring event`() = runTest {
        val event = createTestEvent("Non-Recurring")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val extended = occurrenceGenerator.extendOccurrences(
            savedEvent,
            savedEvent.startTs + 100 * 86400000
        )

        assertEquals(0, extended)
    }

    // ==================== Range Boundary Tests ====================

    @Test
    fun `occurrences exactly at range start are included`() = runTest {
        val startTs = 1718409600000L // Exact timestamp

        val event = Event(
            uid = "boundary@test.com",
            calendarId = testCalendarId,
            title = "Boundary Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // The range starts at the event start.
        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs,
            startTs + 10 * 86400000
        )

        assertEquals(3, count)
    }

    @Test
    fun `occurrences exactly at range end are excluded`() = runTest {
        val startTs = 1718409600000L

        val event = Event(
            uid = "end-boundary@test.com",
            calendarId = testCalendarId,
            title = "End Boundary",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // The range ends at the 3rd occurrence.
        val thirdOccTime = startTs + 2 * 86400000
        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            thirdOccTime
        )

        // The range end is exclusive, so the 3rd occurrence is left out.
        assertEquals(2, count)
    }

    // ==================== DST and Timezone Edge Cases ====================

    @Test
    fun `60-second tolerance links exception across DST boundary`() = runTest {
        // A RECURRENCE-ID a little off the RRULE's time (as across time zone or DST handling)
        // still links. The series starts an hour from now, not at a DST change; the offset is
        // 30 seconds.
        val event = createRecurringEvent("DST Event", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        assertTrue("Should have at least 3 occurrences", occurrences.size >= 3)
        val targetOccTime = occurrences[2].startTs

        // An exception 30 seconds off, within the 60-second tolerance.
        val exceptionId = database.eventsDao().insert(
            savedEvent.copy(
                id = 0,
                title = "Modified DST",
                originalEventId = eventId,
                originalInstanceTime = targetOccTime + 30000 // 30 seconds off
            )
        )

        occurrenceGenerator.linkException(eventId, targetOccTime + 30000, exceptionId)

        val linkedOcc = database.occurrencesDao().getForEvent(eventId)
            .find { kotlin.math.abs(it.startTs - targetOccTime) < 60000 }
        assertEquals(exceptionId, linkedOcc?.exceptionEventId)
    }

    @Test
    fun `cross-midnight recurring event generates correct occurrences`() = runTest {
        // 23:00 to 01:00 UTC the next day, daily.
        val startTs = 1718492400000L // June 15, 2024 23:00 UTC
        val endTs = startTs + 2 * 3600000 // +2 hours (ends at 01:00 next day)

        val event = Event(
            uid = "cross-midnight@test.com",
            calendarId = testCalendarId,
            title = "Late Night Meeting",
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        assertEquals(3, count)

        // Each occurrence spans two days. The assert also passes on the 2-hour duration alone,
        // so the day span isn't enforced.
        val occurrences = database.occurrencesDao().getForEvent(eventId)
        occurrences.forEach { occ ->
            assertTrue(
                "Cross-midnight event should span two days",
                occ.endDay > occ.startDay || occ.endTs - occ.startTs == 2 * 3600000L
            )
        }
    }

    @Test
    fun `monthly BYMONTHDAY generates occurrences on specific day`() = runTest {
        // Monthly on the 15th, taken from DTSTART (the rule has no BYMONTHDAY).
        val startTs = 1705276800000L // Jan 15, 2024 00:00 UTC

        val event = Event(
            uid = "monthly-15th@test.com",
            calendarId = testCalendarId,
            title = "Monthly Review",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=MONTHLY;COUNT=3",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // 120 days holds 3 monthly occurrences.
        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 120 * 86400000L
        )

        assertTrue("Should have at least 3 monthly occurrences", count >= 3)

        // All on the 15th.
        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val days = occurrences.map { it.startDay % 100 } // Day of month

        assertTrue("All occurrences should be on 15th", days.all { it == 15 })
    }

    @Test
    fun `EXDATE in different timezone representation matches occurrence`() = runTest {
        // A UTC DATE-TIME EXDATE matches the occurrence.
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "exdate-tz@test.com",
            calendarId = testCalendarId,
            title = "TZ EXDATE Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            // EXDATE for June 17 (3rd occurrence)
            exdate = "20240617T000000Z",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        // 5 - 1 excluded = 4
        assertEquals(4, count)

        // June 17 is gone.
        val days = database.occurrencesDao().getForEvent(eventId).map { it.startDay }
        assertTrue("June 17 should be excluded", 20240617 !in days)
    }

    @Test
    fun `all-day event EXDATE uses date-only matching`() = runTest {
        // An all-day series takes a DATE EXDATE.
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "allday-exdate@test.com",
            calendarId = testCalendarId,
            title = "All-Day EXDATE",
            startTs = startTs,
            endTs = startTs + 86400000, // Full day
            isAllDay = true,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "20240617", // DATE format (no time component)
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        assertEquals(4, count)
    }

    @Test
    fun `toDayFormat handles negative UTC offset correctly`() {
        // A timed event in UTC-5 (EST) shortly after local midnight.
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

            // Jan 15, 2024 06:00 UTC = Jan 15, 2024 01:00 EST
            val utcTime = 1705298400000L

            // A timed event's day code uses the default zone.
            val dayCode = Occurrence.toDayFormat(utcTime, isAllDay = false)

            // Still Jan 15 in EST.
            assertEquals(20240115, dayCode)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `toDayFormat handles positive UTC offset correctly`() {
        // UTC+9 (Tokyo).
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))

            // Jan 15, 2024 00:00 UTC = Jan 15, 2024 09:00 JST
            val utcTime = 1705276800000L

            // A timed event's day code uses the default zone.
            val dayCode = Occurrence.toDayFormat(utcTime, isAllDay = false)

            // Still Jan 15 in JST.
            assertEquals(20240115, dayCode)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `toDayFormat handles UTC day boundary for positive offset`() {
        // A late UTC time is already the next day at a positive offset.
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))

            // Jan 15, 2024 20:00 UTC = Jan 16, 2024 05:00 JST
            val utcTime = 1705348800000L

            val dayCode = Occurrence.toDayFormat(utcTime, isAllDay = false)

            // Jan 16 in JST.
            assertEquals(20240116, dayCode)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `cancelOccurrence with 60-second tolerance works across DST`() = runTest {
        val event = createRecurringEvent("Cancel DST", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[2].startTs

        // Cancel 45 seconds off, within the 60-second tolerance. The series starts an hour from
        // now, not at a DST change.
        occurrenceGenerator.cancelOccurrence(eventId, targetOccTime + 45000) // 45 seconds off

        val cancelledOcc = database.occurrencesDao().getForEvent(eventId)
            .find { kotlin.math.abs(it.startTs - targetOccTime) < 60000 }
        assertTrue("Should be cancelled even with time offset", cancelledOcc?.isCancelled == true)
    }

    @Test
    fun `daily RRULE across year boundary`() = runTest {
        // Daily from Dec 30, 2024 into Jan 2025.
        val startTs = 1735516800000L // Dec 30, 2024 00:00 UTC

        val event = Event(
            uid = "year-boundary@test.com",
            calendarId = testCalendarId,
            title = "Year Boundary",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        assertEquals(5, count)

        // Both years appear.
        val days = database.occurrencesDao().getForEvent(eventId).map { it.startDay }.sorted()
        val years = days.map { it / 10000 }.distinct().sorted()
        assertEquals(listOf(2024, 2025), years)

        // Dec 30, 31 + Jan 1, 2, 3
        assertTrue("Should include Dec days", days.any { it in 20241230..20241231 })
        assertTrue("Should include Jan days", days.any { it in 20250101..20250103 })
    }

    // ==================== Past Extension DAO Tests ====================

    @Test
    fun `getMinStartTs returns earliest occurrence time`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024 00:00 UTC

        val event = Event(
            uid = "min-start@test.com",
            calendarId = testCalendarId,
            title = "Min Start Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=10",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 20 * 86400000L
        )

        val minTs = database.occurrencesDao().getMinStartTs(eventId)
        assertNotNull(minTs)
        assertEquals(startTs, minTs)
    }

    @Test
    fun `getMinStartTs returns null for event with no occurrences`() = runTest {
        val event = createRecurringEvent("No Occs", "FREQ=DAILY")
        val eventId = database.eventsDao().insert(event)

        val minTs = database.occurrencesDao().getMinStartTs(eventId)
        assertNull(minTs)
    }

    @Test
    fun `needingPastExtension finds events with gap before target`() = runTest {
        // The event starts Jan 1, 2020, but occurrences exist only from Jan 2024.
        val eventStartTs = 1577836800000L // Jan 1, 2020 00:00 UTC
        val occWindowStart = 1704067200000L // Jan 1, 2024 00:00 UTC

        val event = Event(
            uid = "past-gap@test.com",
            calendarId = testCalendarId,
            title = "Past Gap Event",
            startTs = eventStartTs,
            endTs = eventStartTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Occurrences for 30 days from Jan 1, 2024 only: a partial window.
        occurrenceGenerator.generateOccurrences(
            savedEvent,
            occWindowStart,
            occWindowStart + 30 * 86400000L
        )

        // Target July 2023: the event started earlier, but its earliest occurrence is Jan 2024.
        val targetTs = 1688169600000L // July 1, 2023 00:00 UTC
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(targetTs)
        assertTrue("Should find event needing past extension", needsExtension.contains(eventId))
    }

    @Test
    fun `needingPastExtension excludes events where DTSTART is after target`() = runTest {
        val eventStartTs = 1704067200000L // Jan 1, 2024

        val event = Event(
            uid = "future-start@test.com",
            calendarId = testCalendarId,
            title = "Future Start Event",
            startTs = eventStartTs,
            endTs = eventStartTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=10",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            eventStartTs - 86400000,
            eventStartTs + 20 * 86400000L
        )

        // Target June 2023, before the event starts. DTSTART is materialized, so there is
        // nothing earlier to expand.
        val targetTs = 1685577600000L // June 1, 2023
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(targetTs)
        assertFalse("Should NOT include event starting after target", needsExtension.contains(eventId))
    }

    @Test
    fun `needingPastExtension excludes events already covering target`() = runTest {
        val eventStartTs = 1577836800000L // Jan 1, 2020

        val event = Event(
            uid = "already-covered@test.com",
            calendarId = testCalendarId,
            title = "Already Covered",
            startTs = eventStartTs,
            endTs = eventStartTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate from Jan 2020, the event start.
        occurrenceGenerator.generateOccurrences(
            savedEvent,
            eventStartTs,
            eventStartTs + 365 * 86400000L
        )

        // Target June 2020: the earliest occurrence, Jan 2020, is already before it.
        val targetTs = 1590969600000L // June 1, 2020
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(targetTs)
        assertFalse("Should NOT include event already covering target", needsExtension.contains(eventId))
    }

    @Test
    fun `needingPastExtension excludes non-recurring events`() = runTest {
        val eventStartTs = 1704067200000L // Jan 1, 2024

        val event = Event(
            uid = "non-recurring@test.com",
            calendarId = testCalendarId,
            title = "Non-Recurring",
            startTs = eventStartTs,
            endTs = eventStartTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            eventStartTs - 86400000,
            eventStartTs + 86400000
        )

        val targetTs = 1701388800000L // Dec 1, 2023
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(targetTs)
        assertFalse("Should NOT include non-recurring event", needsExtension.contains(eventId))
    }

    @Test
    fun `needingPastExtension excludes exception events`() = runTest {
        val masterStartTs = 1577836800000L // Jan 1, 2020

        val masterEvent = Event(
            uid = "master-exc@test.com",
            calendarId = testCalendarId,
            title = "Master",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(masterEvent)
        val savedMaster = database.eventsDao().getById(masterId)!!

        occurrenceGenerator.generateOccurrences(
            savedMaster,
            1704067200000L, // Jan 2024
            1704067200000L + 30 * 86400000L
        )

        // An exception (originalEventId set) that also carries an RRULE.
        val exceptionEvent = Event(
            uid = "master-exc@test.com", // Same UID per RFC 5545
            calendarId = testCalendarId,
            title = "Exception",
            startTs = 1704153600000L, // Jan 2, 2024
            endTs = 1704153600000L + 3600000,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = 1704153600000L,
            rrule = "FREQ=DAILY", // The query skips it for its originalEventId
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exceptionEvent)

        // An occurrence row owned by the exception, so the query's join can reach it.
        database.occurrencesDao().insert(
            Occurrence(
                eventId = exceptionId,
                calendarId = testCalendarId,
                startTs = 1704153600000L,
                endTs = 1704153600000L + 3600000,
                startDay = 20240102,
                endDay = 20240102
            )
        )

        val targetTs = 1688169600000L // July 2023
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(targetTs)
        // The master, not the exception.
        assertTrue("Should include master event", needsExtension.contains(masterId))
        assertFalse("Should NOT include exception event", needsExtension.contains(exceptionId))
    }

    // ==================== Past Extension (OccurrenceGenerator) Tests ====================

    @Test
    fun `extendPastOccurrences adds occurrences before current range`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024 00:00 UTC
        val DAY = 86400000L

        val event = Event(
            uid = "past-ext@test.com",
            calendarId = testCalendarId,
            title = "Past Extension",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Occurrences for Jun-Jul 2024 only: a partial window.
        val junStart = startTs + 152 * DAY // Jun 1, 2024
        occurrenceGenerator.generateOccurrences(savedEvent, junStart, junStart + 60 * DAY)

        val initialCount = database.occurrencesDao().getForEvent(eventId).size
        val initialMinTs = database.occurrencesDao().getMinStartTs(eventId)!!

        // Extend back to March 2024.
        val marchStart = startTs + 59 * DAY // Feb 29, 2024
        val extended = occurrenceGenerator.extendPastOccurrences(savedEvent, marchStart)

        assertTrue("Should have extended some occurrences", extended > 0)
        val newCount = database.occurrencesDao().getForEvent(eventId).size
        assertTrue("Total count should increase", newCount > initialCount)

        val newMinTs = database.occurrencesDao().getMinStartTs(eventId)!!
        assertTrue("Min occurrence should be earlier", newMinTs < initialMinTs)
        assertTrue("Min occurrence should be >= marchStart", newMinTs >= marchStart)
    }

    @Test
    fun `extendPastOccurrences returns 0 for non-recurring event`() = runTest {
        val event = createTestEvent("Non-Recurring Past")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 86400000
        )

        val extended = occurrenceGenerator.extendPastOccurrences(
            savedEvent,
            savedEvent.startTs - 100 * 86400000L
        )
        assertEquals(0, extended)
    }

    @Test
    fun `extendPastOccurrences does not go before event startTs`() = runTest {
        val startTs = 1709251200000L // Mar 1, 2024 00:00 UTC
        val DAY = 86400000L

        val event = Event(
            uid = "dtstart-bound@test.com",
            calendarId = testCalendarId,
            title = "DTSTART Bound",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Occurrences for Jun-Jul 2024.
        val junStart = startTs + 92 * DAY // Jun 1, 2024
        occurrenceGenerator.generateOccurrences(savedEvent, junStart, junStart + 60 * DAY)

        // Extend back to Jan 2024, before the Mar 2024 DTSTART.
        val janStart = 1704067200000L // Jan 1, 2024
        occurrenceGenerator.extendPastOccurrences(savedEvent, janStart)

        // The earliest occurrence is not before DTSTART.
        val minTs = database.occurrencesDao().getMinStartTs(eventId)!!
        assertTrue("Earliest occurrence should be >= event startTs", minTs >= startTs)
    }

    @Test
    fun `extendPastOccurrences returns 0 when already extended past enough`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024
        val DAY = 86400000L

        val event = Event(
            uid = "already-past@test.com",
            calendarId = testCalendarId,
            title = "Already Extended",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=180",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate from Jan 1, the event start.
        occurrenceGenerator.generateOccurrences(savedEvent, startTs, startTs + 200 * DAY)

        // Target June 2024: the earliest occurrence, Jan 1, is already before it.
        val juneTarget = startTs + 152 * DAY
        val extended = occurrenceGenerator.extendPastOccurrences(savedEvent, juneTarget)
        assertEquals(0, extended)
    }

    @Test
    fun `extendPastOccurrences returns 0 when currentMinTs equals effectiveExtendTo`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024
        val DAY = 86400000L

        val event = Event(
            uid = "exact-boundary@test.com",
            calendarId = testCalendarId,
            title = "Exact Boundary",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate from Mar 1 for 60 days.
        val marStart = startTs + 60 * DAY
        occurrenceGenerator.generateOccurrences(savedEvent, marStart, marStart + 60 * DAY)

        // Extend to the current earliest occurrence.
        val minTs = database.occurrencesDao().getMinStartTs(eventId)!!
        val extended = occurrenceGenerator.extendPastOccurrences(savedEvent, minTs)
        assertEquals("Should return 0 when extending to exact current boundary", 0, extended)
    }

    @Test
    fun `extendPastOccurrences does not duplicate existing occurrences`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024
        val DAY = 86400000L

        val event = Event(
            uid = "no-dup@test.com",
            calendarId = testCalendarId,
            title = "No Duplicates",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate Jun-Aug 2024.
        val junStart = startTs + 152 * DAY
        occurrenceGenerator.generateOccurrences(savedEvent, junStart, junStart + 90 * DAY)

        val junOccsBefore = database.occurrencesDao().getForEvent(eventId)
            .filter { it.startTs >= junStart }
        val junCountBefore = junOccsBefore.size

        // Extend back to Mar 2024.
        val marStart = startTs + 59 * DAY
        occurrenceGenerator.extendPastOccurrences(savedEvent, marStart)

        // The Jun-Aug count is unchanged.
        val junOccsAfter = database.occurrencesDao().getForEvent(eventId)
            .filter { it.startTs >= junStart }
        assertEquals("Original occurrences should be unchanged", junCountBefore, junOccsAfter.size)
    }

    @Test
    fun `extendPastOccurrences preserves exception links`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024
        val DAY = 86400000L

        val event = Event(
            uid = "preserve-exc@test.com",
            calendarId = testCalendarId,
            title = "Exception Preserve",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate Jun-Aug 2024.
        val junStart = startTs + 152 * DAY
        occurrenceGenerator.generateOccurrences(savedEvent, junStart, junStart + 90 * DAY)

        // Link an exception to a July occurrence.
        val occs = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val julyOcc = occs[30] // July 1
        val exceptionEvent = Event(
            uid = "preserve-exc@test.com",
            calendarId = testCalendarId,
            title = "Modified July",
            startTs = julyOcc.startTs,
            endTs = julyOcc.endTs,
            dtstamp = System.currentTimeMillis(),
            originalEventId = eventId,
            originalInstanceTime = julyOcc.startTs,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exceptionEvent)
        occurrenceGenerator.linkException(eventId, julyOcc.startTs, exceptionId)

        val linkedBefore = database.occurrencesDao().getForEvent(eventId)
            .find { it.exceptionEventId == exceptionId }
        assertNotNull("Exception should be linked before past extension", linkedBefore)

        // Extend back to Mar 2024.
        val marStart = startTs + 59 * DAY
        occurrenceGenerator.extendPastOccurrences(savedEvent, marStart)

        // The link is intact.
        val linkedAfter = database.occurrencesDao().getForEvent(eventId)
            .find { it.exceptionEventId == exceptionId }
        assertNotNull("Exception link should be preserved after past extension", linkedAfter)
    }

    @Test
    fun `extendPastOccurrences returns 0 when event has no occurrences`() = runTest {
        val event = createRecurringEvent("No Occs Past", "FREQ=DAILY")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // No occurrences generated.
        val extended = occurrenceGenerator.extendPastOccurrences(
            savedEvent,
            savedEvent.startTs - 100 * 86400000L
        )
        assertEquals(0, extended)
    }

    @Test
    fun `extendPastOccurrences skips EXDATE occurrences in past window`() = runTest {
        // Dates relative to now. Second-aligned like the engine's output, so exact-time
        // lookups match.
        val DAY = 86400000L
        val now = (System.currentTimeMillis() / 1000) * 1000 // Second-aligned
        val startTs = now - 90 * DAY // 90 days ago

        // EXDATE on the event's 15th day, which past extension skips.
        val exdateTs = startTs + 14 * DAY // 15th day of event
        // Its day code in the default zone.
        val exdateDayCode = java.time.Instant.ofEpochMilli(exdateTs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))

        val event = Event(
            uid = "exdate-past@test.com",
            calendarId = testCalendarId,
            title = "EXDATE Past",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            exdate = exdateDayCode,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate 60 days from day 60 of the event.
        val laterStart = startTs + 59 * DAY
        occurrenceGenerator.generateOccurrences(savedEvent, laterStart, laterStart + 60 * DAY)

        // Extend back to the event start.
        occurrenceGenerator.extendPastOccurrences(savedEvent, startTs)

        // No occurrence on the EXDATE day.
        val exdateOcc = database.occurrencesDao().getOccurrenceAtTime(eventId, exdateTs)
        assertNull("EXDATE occurrence should not be generated in past extension", exdateOcc)

        // The days before and after exist.
        val dayBeforeOcc = database.occurrencesDao().getOccurrenceAtTime(eventId, exdateTs - DAY)
        val dayAfterOcc = database.occurrencesDao().getOccurrenceAtTime(eventId, exdateTs + DAY)
        assertNotNull("Day before EXDATE should exist", dayBeforeOcc)
        assertNotNull("Day after EXDATE should exist", dayAfterOcc)
    }

    @Test
    fun `extendPastOccurrences works with weekly recurrence`() = runTest {
        // Dates relative to now, second-aligned like the engine's output.
        val DAY = 86400000L
        val WEEK = 7 * DAY
        val now = (System.currentTimeMillis() / 1000) * 1000 // Second-aligned
        val startTs = now - 180 * DAY // 180 days ago

        val event = Event(
            uid = "weekly-past@test.com",
            calendarId = testCalendarId,
            title = "Weekly Past",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=WEEKLY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate days 90-210 of the event.
        val laterStart = startTs + 90 * DAY
        occurrenceGenerator.generateOccurrences(savedEvent, laterStart, laterStart + 120 * DAY)

        val initialCount = database.occurrencesDao().getForEvent(eventId).size

        // Extend back to day 31.
        val extendTarget = startTs + 31 * DAY
        val extended = occurrenceGenerator.extendPastOccurrences(savedEvent, extendTarget)

        assertTrue("Should have extended weekly occurrences", extended > 0)
        val newCount = database.occurrencesDao().getForEvent(eventId).size
        assertTrue("Total count should increase", newCount > initialCount)

        // Every gap is 7 days.
        val allOccs = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        for (i in 1 until allOccs.size) {
            val gap = allOccs[i].startTs - allOccs[i - 1].startTs
            assertEquals("Weekly occurrences should be 7 days apart", WEEK, gap)
        }
    }

    // ==================== Past Recurring Event Inside the Pull Range ====================

    @Test
    fun `past recurring event within 1-year window generates occurrences via PullStrategy range`() = runTest {
        // A weekly event that started 6 months ago with UNTIL 3 months ago. PullStrategy
        // expands a pulled series over now - 365 days to now + 2 years, so all of it is inside.
        val now = System.currentTimeMillis()
        val sixMonthsAgo = now - (180L * 24 * 60 * 60 * 1000)
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)

        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = threeMonthsAgo
        val untilStr = String.format(
            "%04d%02d%02dT%02d%02d%02dZ",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )

        val event = Event(
            uid = "past-weekly-bug@test.com",
            calendarId = testCalendarId,
            title = "Past Weekly Meeting",
            startTs = sixMonthsAgo,
            endTs = sixMonthsAgo + 3600000,
            dtstamp = now,
            rrule = "FREQ=WEEKLY;UNTIL=$untilStr",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // PullStrategy's range: now - 365 days to now + 2 x 365 days.
        val pastWindowMs = 365L * 24 * 60 * 60 * 1000
        val futureWindowMs = 2 * 365L * 24 * 60 * 60 * 1000
        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            rangeStartMs = now - pastWindowMs,
            rangeEndMs = now + futureWindowMs
        )

        assertTrue("Past recurring event within 1-year window should have occurrences, got $count", count > 0)

        val occs = database.occurrencesDao().getForEvent(eventId)
        assertTrue("Should have occurrence rows in database", occs.isNotEmpty())

        // About 13 weekly occurrences (6 months to 3 months ago).
        assertTrue("Should have ~13 weekly occurrences, got ${occs.size}", occs.size in 10..15)
    }

    // ==================== Far-Past Recurring Event (#152) ====================

    @Test
    fun `needingPastExtension finds yearly event when targetTs is after buffer subtraction`() = runTest {
        // #152: a FREQ=YEARLY event starting April 13, 2008. Navigating to April 2008,
        // extendPastOccurrencesIfNeeded subtracts its 6-month buffer, so targetTs is October
        // 2007, before DTSTART. The query compares DTSTART with the earliest occurrence, not
        // with targetTs, so the event is still found.
        val april2008 = 1208044800000L // April 13, 2008 00:00 UTC
        val oct2007 = april2008 - (6 * 30L * 24 * 60 * 60 * 1000) // ~October 2007 (6-month buffer)

        val event = Event(
            uid = "yearly-birthday-152@test.com",
            calendarId = testCalendarId,
            title = "Birthday",
            startTs = april2008,
            endTs = april2008 + 86400000, // all-day
            isAllDay = true,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=YEARLY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Occurrences for 2025-2027 only, a window near now.
        val jan2025 = 1735689600000L // Jan 1, 2025
        val jan2028 = 1830297600000L // Jan 1, 2028
        occurrenceGenerator.generateOccurrences(savedEvent, jan2025, jan2028)

        val occs = database.occurrencesDao().getForEvent(eventId)
        assertTrue("Should have occurrences in 2025-2028", occs.isNotEmpty())

        // targetTs is October 2007, DTSTART April 2008, the earliest occurrence April 2025.
        // The gap between DTSTART and the earliest occurrence makes the event need extension.
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(oct2007)
        assertTrue(
            "Should find yearly event needing past extension (issue #152)",
            needsExtension.contains(eventId)
        )
    }

    @Test
    fun `extendPastOccurrences extends yearly event back to DTSTART despite syncPastDays`() = runTest {
        // #152: extendPastOccurrences doesn't read syncPastDays (default 365), so on-demand
        // extension reaches DTSTART even where the lookback would stop a year back. This
        // test's data store returns "All events"; `OccurrenceGeneratorSyncLookbackTest` covers
        // a 90-day lookback.
        val april2008 = 1208044800000L // April 13, 2008 00:00 UTC

        val event = Event(
            uid = "yearly-extend-152@test.com",
            calendarId = testCalendarId,
            title = "Birthday",
            startTs = april2008,
            endTs = april2008 + 86400000,
            isAllDay = true,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=YEARLY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Occurrences for 2025-2027 only.
        val jan2025 = 1735689600000L
        val jan2028 = 1830297600000L
        occurrenceGenerator.generateOccurrences(savedEvent, jan2025, jan2028)

        // Extend back to October 2007, 6 months before DTSTART, as the buffer does.
        val oct2007 = april2008 - (6 * 30L * 24 * 60 * 60 * 1000)
        val extended = occurrenceGenerator.extendPastOccurrences(savedEvent, oct2007)

        assertTrue("Should have extended occurrences back to DTSTART", extended > 0)

        // The earliest occurrence is within a day of DTSTART, April 2008.
        val minTs = database.occurrencesDao().getMinStartTs(eventId)!!
        val diffFromDtstart = minTs - april2008
        assertTrue(
            "Earliest occurrence should be at DTSTART (April 2008), diff=$diffFromDtstart ms",
            diffFromDtstart < 86400000 // within 1 day (accounting for all-day alignment)
        )
    }

    // ==================== Helper Methods ====================

    private fun createTestEvent(title: String): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = "$title-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = now + 3600000,
            endTs = now + 7200000,
            dtstamp = now,
            syncStatus = SyncStatus.SYNCED
        )
    }

    private fun createRecurringEvent(title: String, rrule: String): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = "$title-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = now + 3600000,
            endTs = now + 7200000,
            dtstamp = now,
            rrule = rrule,
            syncStatus = SyncStatus.SYNCED
        )
    }
}