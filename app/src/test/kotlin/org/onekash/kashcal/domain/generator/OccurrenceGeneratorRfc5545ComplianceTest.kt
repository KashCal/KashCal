package org.onekash.kashcal.domain.generator
import org.onekash.kashcal.testutil.TestDataStoreFactory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * RFC 5545 compliance tests for OccurrenceGenerator.
 *
 * Tests behaviors required by RFC 5545 that are not covered by existing tests:
 * - COUNT + UNTIL mutual exclusivity (Section 3.3.10)
 * - EXDATE with millisecond timestamps (from ICalEventMapper)
 * - RDATE with millisecond timestamps (from ICalEventMapper)
 * - Set algebra: (RRULE UNION RDATE) MINUS EXDATE
 * - EXDATE on all-day recurring events
 * - EXDATE precision: day-level matching for timed events
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OccurrenceGeneratorRfc5545ComplianceTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0

    private val defaultZone = ZoneId.of("America/New_York")

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())

        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.LOCAL, email = "test@test.com")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://test.com/cal/",
                    displayName = "Test Calendar",
                    color = 0xFF0000FF.toInt()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun parseDate(dateStr: String): Long {
        val parts = dateStr.split(" ")
        val dateParts = parts[0].split("-").map { it.toInt() }
        val timeParts = parts[1].split(":").map { it.toInt() }
        return ZonedDateTime.of(
            dateParts[0], dateParts[1], dateParts[2],
            timeParts[0], timeParts[1], 0, 0,
            defaultZone
        ).toInstant().toEpochMilli()
    }

    private fun parseUtcDate(dateStr: String): Long {
        val parts = dateStr.split(" ")
        val dateParts = parts[0].split("-").map { it.toInt() }
        val timeParts = parts[1].split(":").map { it.toInt() }
        return ZonedDateTime.of(
            dateParts[0], dateParts[1], dateParts[2],
            timeParts[0], timeParts[1], 0, 0,
            ZoneId.of("UTC")
        ).toInstant().toEpochMilli()
    }

    private suspend fun createAndInsertEvent(
        startTs: Long,
        endTs: Long,
        rrule: String? = null,
        exdate: String? = null,
        rdate: String? = null,
        title: String = "Test Event",
        isAllDay: Boolean = false,
        timezone: String? = null
    ): Event {
        val event = Event(
            uid = "test-uid-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            isAllDay = isAllDay,
            timezone = timezone,
            rrule = rrule,
            exdate = exdate,
            rdate = rdate,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        return event.copy(id = eventId)
    }

    // ==================== RFC 5545 Section 3.3.10: COUNT + UNTIL Mutual Exclusivity ====================

    @Test
    fun `COUNT and UNTIL in same RRULE should honor COUNT`() = runTest {
        // RFC 5545 Section 3.3.10: "The UNTIL or COUNT rule parts are OPTIONAL,
        // but they MUST NOT occur in the same 'recur'."
        //
        // BUG: lib-recur returns 0 occurrences when both are present, silently
        // dropping all occurrences. A non-compliant server sending both causes
        // the event to appear non-recurring.
        //
        // COUNT=5 → Jan 5-9 (5 occurrences)
        // UNTIL=Mar 1 → Jan 5 through Mar 1 (55+ occurrences)
        //
        // FIX NEEDED: OccurrenceGenerator should strip UNTIL when COUNT is present
        // before passing to lib-recur, as COUNT is the more restrictive constraint.
        val startTs = parseDate("2026-01-05 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5;UNTIL=20260301T000000Z"
        )

        val rangeEnd = startTs + 90L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        // Should honor COUNT (5 occurrences) rather than producing 0
        assertEquals(
            "COUNT+UNTIL: should honor COUNT and produce 5 occurrences",
            5, count
        )
    }

    // ==================== RFC 5545 Section 3.8.5.1: EXDATE with Millisecond Timestamps ====================

    @Test
    fun `EXDATE with millisecond timestamps excludes correct occurrences`() = runTest {
        // ICalEventMapper stores EXDATE as comma-separated millisecond timestamps.
        // OccurrenceGenerator.parseMultiValueField converts these to day codes.
        // Verify the excluded dates are actually removed from the occurrence set.
        val startTs = parseDate("2026-01-05 10:00") // Monday
        val jan7 = parseDate("2026-01-07 10:00") // Wednesday
        val jan9 = parseDate("2026-01-09 10:00") // Friday

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=7",
            exdate = "$jan7,$jan9" // Millisecond format from ICalEventMapper
        )

        val rangeEnd = startTs + 30L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        // 7 occurrences minus 2 excluded = 5
        assertEquals("Should have 5 occurrences (7 minus 2 excluded)", 5, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val occDates = occurrences.map {
            Instant.ofEpochMilli(it.startTs).atZone(defaultZone).toLocalDate()
        }

        // Jan 7 and Jan 9 should be excluded
        assertTrue("Jan 7 should be excluded", !occDates.contains(LocalDate.of(2026, 1, 7)))
        assertTrue("Jan 9 should be excluded", !occDates.contains(LocalDate.of(2026, 1, 9)))

        // Jan 5, 6, 8, 10, 11 should be present
        assertTrue("Jan 5 should be present", occDates.contains(LocalDate.of(2026, 1, 5)))
        assertTrue("Jan 6 should be present", occDates.contains(LocalDate.of(2026, 1, 6)))
        assertTrue("Jan 8 should be present", occDates.contains(LocalDate.of(2026, 1, 8)))
    }

    @Test
    fun `EXDATE with single millisecond timestamp excludes one occurrence`() = runTest {
        val startTs = parseDate("2026-02-02 09:00") // Monday
        val feb4 = parseDate("2026-02-04 09:00") // Wednesday

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "$feb4"
        )

        val rangeEnd = startTs + 30L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        assertEquals("Should have 4 occurrences (5 minus 1 excluded)", 4, count)
    }

    // ==================== RFC 5545 EXDATE on All-Day Events ====================

    @Test
    fun `EXDATE on all-day recurring event excludes correct dates`() = runTest {
        // All-day events use UTC. EXDATE stored as ms of UTC midnight.
        // Verify day-code conversion uses UTC, not local timezone.
        val startTs = parseUtcDate("2026-01-05 00:00") // UTC midnight
        val jan7Utc = parseUtcDate("2026-01-07 00:00") // UTC midnight

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 86400000 - 1, // All-day: end at 23:59:59.999 UTC
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "$jan7Utc",
            isAllDay = true
        )

        val rangeEnd = startTs + 30L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        assertEquals("Should have 4 occurrences (5 minus 1 excluded)", 4, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val dayDates = occurrences.map {
            Instant.ofEpochMilli(it.startTs).atZone(ZoneOffset.UTC).toLocalDate()
        }

        assertTrue("Jan 7 should be excluded", !dayDates.contains(LocalDate.of(2026, 1, 7)))
        assertTrue("Jan 5 should be present", dayDates.contains(LocalDate.of(2026, 1, 5)))
        assertTrue("Jan 6 should be present", dayDates.contains(LocalDate.of(2026, 1, 6)))
    }

    // ==================== RFC 5545: RDATE with Millisecond Timestamps ====================

    @Test
    fun `RDATE with millisecond timestamps adds occurrences to set`() = runTest {
        // RFC 5545: RecurrenceSet = (DTSTART UNION RRULE UNION RDATE) MINUS EXDATE
        // RDATE adds additional occurrences beyond what the RRULE generates.
        val startTs = parseDate("2026-01-05 10:00") // Monday
        val jan10 = parseDate("2026-01-10 10:00") // Saturday (not in BYDAY=MO)

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=3", // Jan 5, 12, 19
            rdate = "$jan10" // Add Jan 10 (Saturday) via RDATE
        )

        val rangeEnd = startTs + 30L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        // 3 from RRULE + 1 from RDATE = 4
        assertEquals("Should have 4 occurrences (3 from RRULE + 1 from RDATE)", 4, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val occDates = occurrences.map {
            Instant.ofEpochMilli(it.startTs).atZone(defaultZone).toLocalDate()
        }.sorted()

        assertTrue("Jan 10 (RDATE) should be present", occDates.contains(LocalDate.of(2026, 1, 10)))
    }

    @Test
    fun `RDATE with multiple millisecond timestamps adds all occurrences`() = runTest {
        val startTs = parseDate("2026-03-01 14:00")
        val mar15 = parseDate("2026-03-15 14:00")
        val apr1 = parseDate("2026-04-01 14:00")

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=MONTHLY;COUNT=2", // Mar 1, Apr 1 (DTSTART already at Mar 1)
            rdate = "$mar15,$apr1" // Add Mar 15 + duplicate Apr 1
        )

        val rangeEnd = startTs + 90L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val occDates = occurrences.map {
            Instant.ofEpochMilli(it.startTs).atZone(defaultZone).toLocalDate()
        }.sorted()

        assertTrue("Mar 15 (RDATE) should be present", occDates.contains(LocalDate.of(2026, 3, 15)))
    }

    // ==================== RFC 5545: Set Algebra (RRULE UNION RDATE) MINUS EXDATE ====================

    @Test
    fun `RDATE and EXDATE together follow set algebra`() = runTest {
        // RFC 5545: RecurrenceSet = (DTSTART UNION RRULE UNION RDATE) MINUS EXDATE
        // Add via RDATE, then remove some via EXDATE - verify correct final set.
        val startTs = parseDate("2026-01-05 10:00") // Monday
        val jan7 = parseDate("2026-01-07 10:00") // Wednesday
        val jan10 = parseDate("2026-01-10 10:00") // Saturday
        val jan12 = parseDate("2026-01-12 10:00") // Monday (from RRULE)

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=3", // Jan 5, 12, 19
            rdate = "$jan7,$jan10", // Add Jan 7, Jan 10
            exdate = "$jan12" // Remove Jan 12 (from RRULE)
        )

        val rangeEnd = startTs + 30L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        // RRULE: Jan 5, 12, 19 (3)
        // RDATE: Jan 7, 10 (+2)
        // EXDATE: Jan 12 (-1)
        // Total: 4
        assertEquals("Should have 4 occurrences (3 RRULE + 2 RDATE - 1 EXDATE)", 4, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val occDates = occurrences.map {
            Instant.ofEpochMilli(it.startTs).atZone(defaultZone).toLocalDate()
        }

        assertTrue("Jan 5 should be present (RRULE)", occDates.contains(LocalDate.of(2026, 1, 5)))
        assertTrue("Jan 7 should be present (RDATE)", occDates.contains(LocalDate.of(2026, 1, 7)))
        assertTrue("Jan 10 should be present (RDATE)", occDates.contains(LocalDate.of(2026, 1, 10)))
        assertTrue("Jan 12 should be excluded (EXDATE)", !occDates.contains(LocalDate.of(2026, 1, 12)))
        assertTrue("Jan 19 should be present (RRULE)", occDates.contains(LocalDate.of(2026, 1, 19)))
    }

    @Test
    fun `EXDATE can exclude RDATE occurrences`() = runTest {
        // EXDATE should remove from the union, not just from RRULE.
        val startTs = parseDate("2026-02-02 10:00") // Monday
        val feb7 = parseDate("2026-02-07 10:00") // Saturday (added by RDATE)

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=2", // Feb 2, Feb 9
            rdate = "$feb7", // Add Feb 7
            exdate = "$feb7" // Remove Feb 7
        )

        val rangeEnd = startTs + 30L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        // RRULE: Feb 2, 9 (2) + RDATE: Feb 7 (+1) - EXDATE: Feb 7 (-1) = 2
        assertEquals("Should have 2 occurrences (RDATE cancelled by EXDATE)", 2, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val occDates = occurrences.map {
            Instant.ofEpochMilli(it.startTs).atZone(defaultZone).toLocalDate()
        }

        assertTrue("Feb 7 should be excluded (RDATE cancelled by EXDATE)",
            !occDates.contains(LocalDate.of(2026, 2, 7)))
    }

    // ==================== EXDATE Day-Level Precision Tests ====================

    @Test
    fun `EXDATE with different time than DTSTART still excludes the day`() = runTest {
        // EXDATE stored as ms may have slightly different time than DTSTART.
        // parseMultiValueField converts to day code, so any time on that day should match.
        val startTs = parseDate("2026-01-05 10:00") // 10 AM
        // EXDATE with midnight timestamp (different time, same day)
        val jan7Midnight = parseDate("2026-01-07 00:00")

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "$jan7Midnight"
        )

        val rangeEnd = startTs + 30L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        // Day-level matching should exclude Jan 7 even though time differs
        assertEquals("Should have 4 occurrences (day-level EXDATE match)", 4, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val occDates = occurrences.map {
            Instant.ofEpochMilli(it.startTs).atZone(defaultZone).toLocalDate()
        }

        assertTrue("Jan 7 should be excluded despite different time",
            !occDates.contains(LocalDate.of(2026, 1, 7)))
    }

    // ==================== RRULE Edge Cases from RFC 5545 ====================

    @Test
    fun `FREQ=YEARLY with BYMONTH and BYDAY generates correct occurrences`() = runTest {
        // RFC 5545 example: US Thanksgiving - 4th Thursday in November
        val startTs = parseDate("2025-11-27 10:00") // Thanksgiving 2025
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=YEARLY;BYMONTH=11;BYDAY=4TH;COUNT=3",
            timezone = "America/New_York"
        )

        val rangeEnd = startTs + 4L * 365 * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        assertEquals("Should generate 3 Thanksgiving occurrences", 3, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            val date = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertEquals("Should be in November", 11, date.monthValue)
            assertEquals("Should be Thursday", java.time.DayOfWeek.THURSDAY, date.dayOfWeek)
        }
    }

    @Test
    fun `FREQ=MONTHLY with BYDAY=-1FR generates last Friday of each month`() = runTest {
        // RFC 5545: negative offset counts from end of month
        val startTs = parseDate("2026-01-30 10:00") // Last Friday of Jan 2026
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=MONTHLY;BYDAY=-1FR;COUNT=6",
            timezone = "America/New_York"
        )

        val rangeEnd = startTs + 365L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        assertEquals("Should generate 6 occurrences", 6, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            val date = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertEquals("Should be Friday", java.time.DayOfWeek.FRIDAY, date.dayOfWeek)
            // Verify it's the last Friday: adding 7 days should go to next month
            val nextFriday = date.plusWeeks(1)
            assertTrue("Next Friday should be in a different month",
                nextFriday.monthValue != date.monthValue)
        }
    }

    @Test
    fun `FREQ=MONTHLY with BYMONTHDAY=29 skips Feb in non-leap years`() = runTest {
        // RFC 5545: BYMONTHDAY=29 should not generate Feb 29 in non-leap years
        val startTs = parseDate("2026-01-29 10:00") // 2026 is not a leap year
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=MONTHLY;BYMONTHDAY=29;COUNT=12",
            timezone = "America/New_York"
        )

        val rangeEnd = startTs + 400L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val months = occurrences.map {
            Instant.ofEpochMilli(it.startTs).atZone(defaultZone).toLocalDate().monthValue
        }

        // In 2026 (non-leap), Feb 29 doesn't exist - should be skipped
        assertTrue("February should be skipped in non-leap year 2026", !months.contains(2))
    }

    @Test
    fun `FREQ=MONTHLY with BYMONTHDAY=29 includes Feb in leap years`() = runTest {
        // 2028 is a leap year - Feb 29 should be included
        val startTs = parseDate("2028-01-29 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=MONTHLY;BYMONTHDAY=29;COUNT=12",
            timezone = "America/New_York"
        )

        val rangeEnd = startTs + 400L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val months = occurrences.map {
            Instant.ofEpochMilli(it.startTs).atZone(defaultZone).toLocalDate().monthValue
        }

        // In 2028 (leap year), Feb 29 exists
        assertTrue("February should be included in leap year 2028", months.contains(2))
    }

    // ==================== RRULE with UNTIL: Value Type Matching ====================

    @Test
    fun `all-day event with DATE format UNTIL generates correct occurrences`() = runTest {
        // RFC 5545 Section 3.3.10: UNTIL value type MUST match DTSTART
        // All-day DTSTART (DATE) requires DATE UNTIL (YYYYMMDD)
        val startTs = parseUtcDate("2026-01-05 00:00") // UTC midnight

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 86400000 - 1,
            rrule = "FREQ=WEEKLY;UNTIL=20260126", // DATE format UNTIL
            isAllDay = true
        )

        val rangeEnd = startTs + 60L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        // Jan 5, 12, 19, 26 = 4 occurrences (UNTIL inclusive)
        assertTrue("Should generate occurrences up to and including UNTIL date", count >= 3)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            val date = Instant.ofEpochMilli(occ.startTs).atZone(ZoneOffset.UTC).toLocalDate()
            assertTrue("All dates should be <= Jan 26",
                !date.isAfter(LocalDate.of(2026, 1, 26)))
        }
    }

    @Test
    fun `timed event with DATETIME format UNTIL generates correct occurrences`() = runTest {
        // Timed DTSTART requires DATETIME UNTIL
        val startTs = parseDate("2026-01-05 10:00")

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=WEEKLY;UNTIL=20260126T150000Z", // DATETIME format UNTIL
            timezone = "America/New_York"
        )

        val rangeEnd = startTs + 60L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        assertTrue("Should generate at least 3 occurrences", count >= 3)
    }

    // ==================== Non-recurring events with EXDATE/RDATE (should be ignored) ====================

    @Test
    fun `non-recurring event ignores EXDATE and RDATE`() = runTest {
        // EXDATE and RDATE are only meaningful for recurring events
        val startTs = parseDate("2026-01-05 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = null, // Non-recurring
            exdate = "$startTs",
            rdate = "${startTs + 86400000}"
        )

        val rangeEnd = startTs + 30L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        // Non-recurring: always exactly 1 occurrence regardless of EXDATE/RDATE
        assertEquals("Non-recurring event should have exactly 1 occurrence", 1, count)
    }

    // ==================== EXDATE format variants ====================

    @Test
    fun `EXDATE with datetime format (YYYYMMDDTHHMMSSZ) excludes correct occurrences`() = runTest {
        val startTs = parseDate("2026-01-05 10:00")

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "20260107T150000Z" // DateTime format
        )

        val rangeEnd = startTs + 30L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        assertEquals("Should have 4 occurrences (1 excluded by datetime EXDATE)", 4, count)
    }

    @Test
    fun `EXDATE with day code format (YYYYMMDD) excludes correct occurrences`() = runTest {
        val startTs = parseDate("2026-01-05 10:00")

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "20260107" // Day code format
        )

        val rangeEnd = startTs + 30L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        assertEquals("Should have 4 occurrences (1 excluded by daycode EXDATE)", 4, count)
    }

    @Test
    fun `EXDATE with mixed formats handles all correctly`() = runTest {
        val startTs = parseDate("2026-01-05 10:00")
        val jan7Ms = parseDate("2026-01-07 10:00")

        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=7",
            // Mixed: milliseconds, datetime, day code
            exdate = "$jan7Ms,20260109T150000Z,20260108"
        )

        val rangeEnd = startTs + 30L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        assertEquals("Should have 4 occurrences (3 excluded by mixed-format EXDATE)", 4, count)
    }

    // ==================== RRULE: BYSETPOS combined with BYDAY ====================

    @Test
    fun `BYSETPOS with BYDAY selects correct positional occurrences`() = runTest {
        // RFC 5545: First and last weekday of each month
        val startTs = parseDate("2026-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1,-1;COUNT=6",
            timezone = "America/New_York"
        )

        val rangeEnd = startTs + 200L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        assertEquals("Should generate 6 occurrences (first+last weekday for 3 months)", 6, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            val date = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            val dayOfWeek = date.dayOfWeek
            assertTrue("Should be a weekday, got $dayOfWeek",
                dayOfWeek != java.time.DayOfWeek.SATURDAY && dayOfWeek != java.time.DayOfWeek.SUNDAY)
        }
    }

    // ==================== RRULE: Multiple BYMONTHDAY values ====================

    @Test
    fun `multiple BYMONTHDAY values generate occurrences on all specified days`() = runTest {
        // RFC 5545: BYMONTHDAY=1,15 generates on 1st and 15th of each month
        val startTs = parseDate("2026-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=MONTHLY;BYMONTHDAY=1,15;COUNT=6",
            timezone = "America/New_York"
        )

        val rangeEnd = startTs + 200L * 24 * 3600000
        val count = occurrenceGenerator.generateOccurrences(event, startTs - 86400000, rangeEnd)

        assertEquals("Should generate 6 occurrences", 6, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            val date = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertTrue("Day of month should be 1 or 15, got ${date.dayOfMonth}",
                date.dayOfMonth == 1 || date.dayOfMonth == 15)
        }
    }
}
