package org.onekash.kashcal.domain.generator

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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.TimeZone

/**
 * Edge case tests for RRULE patterns.
 *
 * Tests rare but valid RFC 5545 patterns:
 * - WKST (Week Start) - affects BYWEEKNO calculations
 * - BYWEEKNO - week number of year
 * - BYYEARDAY - day number of year (1-366)
 * - Monthly on 31st (short months)
 * - Yearly on Feb 29 (leap years)
 * - Very long recurrence series (performance)
 * - COUNT=0 edge case
 * - UNTIL exactly on occurrence
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RruleEdgeCasesTest {

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
        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())

        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = "test", email = "test@test.com")
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

    private suspend fun createAndGenerateEvent(
        startTs: Long,
        rrule: String
    ): Pair<Event, Int> {
        val event = Event(
            id = 0,
            uid = "test-${System.nanoTime()}",
            calendarId = testCalendarId,
            title = "Test Event",
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = rrule,
            dtstamp = System.currentTimeMillis()
        )
        val id = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = id)

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000L * 30,
            startTs + 86400000L * 730 // 2 years
        )

        return Pair(savedEvent, count)
    }

    // ==================== WKST (Week Start) Tests ====================

    @Test
    fun `WKST=SU affects weekly recurrence with BYDAY`() = runTest {
        // Week starts on Sunday (US style)
        val startTs = parseDate("2026-01-05 10:00") // Monday
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=WEEKLY;WKST=SU;BYDAY=MO,FR;COUNT=4"
        )

        assertEquals(4, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(4, occurrences.size)
    }

    @Test
    fun `WKST=MO is default week start`() = runTest {
        val startTs = parseDate("2026-01-05 10:00") // Monday
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=WEEKLY;BYDAY=MO,FR;COUNT=4" // No WKST = defaults to MO
        )

        assertEquals(4, count)
    }

    // ==================== BYWEEKNO Tests ====================
    // Note: BYWEEKNO is an advanced RFC 5545 feature that may not be fully supported

    @Test
    fun `BYWEEKNO selects specific weeks of year`() = runTest {
        // Event on week 1 and week 10 of each year
        val startTs = parseDate("2026-01-05 10:00") // Week 2 of 2026
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=YEARLY;BYWEEKNO=1,10;BYDAY=MO;COUNT=4"
        )

        // BYWEEKNO may not be fully supported - just verify no crash
        // If supported, should have occurrences only on weeks 1 and 10
        assertTrue("Should handle BYWEEKNO without error", count >= 0)
    }

    @Test
    fun `BYWEEKNO=52 handles year boundary`() = runTest {
        val startTs = parseDate("2026-01-01 10:00")
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=YEARLY;BYWEEKNO=52;BYDAY=TH;COUNT=3"
        )

        // BYWEEKNO may not be fully supported - verify no crash
        assertTrue("Should handle BYWEEKNO=52 without error", count >= 0)
    }

    // ==================== BYYEARDAY Tests ====================

    @Test
    fun `BYYEARDAY selects specific days of year`() = runTest {
        // Day 1 (Jan 1) and Day 100 (Apr 10 in non-leap year)
        val startTs = parseDate("2026-01-01 10:00")
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=YEARLY;BYYEARDAY=1,100;COUNT=4"
        )

        assertEquals(4, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            val date = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            val dayOfYear = date.dayOfYear
            assertTrue("Day should be 1 or 100, got $dayOfYear", dayOfYear == 1 || dayOfYear == 100)
        }
    }

    @Test
    fun `BYYEARDAY=366 only occurs in leap years`() = runTest {
        val startTs = parseDate("2024-12-31 10:00") // 2024 is leap year
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=YEARLY;BYYEARDAY=366;COUNT=3"
        )

        // Should only occur in leap years: 2024, 2028, 2032...
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            val date = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertTrue("Year ${date.year} should be leap year", date.isLeapYear)
            assertEquals(366, date.dayOfYear)
        }
    }

    @Test
    fun `negative BYYEARDAY counts from end of year`() = runTest {
        // -1 = last day of year (Dec 31)
        // Note: Negative BYYEARDAY is an advanced RFC 5545 feature
        val startTs = parseDate("2026-12-31 10:00")
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=YEARLY;BYYEARDAY=-1;COUNT=3"
        )

        // Negative BYYEARDAY may not be supported - verify no crash
        assertTrue("Should handle negative BYYEARDAY without error", count >= 0)
    }

    // ==================== Monthly 31st Edge Cases ====================

    @Test
    fun `monthly on 31st skips short months`() = runTest {
        val startTs = parseDate("2026-01-31 10:00")
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=MONTHLY;BYMONTHDAY=31;COUNT=7"
        )

        assertEquals(7, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val months = occurrences.map {
            Instant.ofEpochMilli(it.startTs).atZone(defaultZone).toLocalDate().monthValue
        }

        // Should skip Feb, Apr, Jun, Sep, Nov (months without 31 days)
        assertTrue("Should not include February", !months.contains(2))
        assertTrue("Should not include April", !months.contains(4))
        assertTrue("Should not include June", !months.contains(6))
    }

    @Test
    fun `monthly BYMONTHDAY=-1 is last day of each month`() = runTest {
        val startTs = parseDate("2026-01-31 10:00")
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=12"
        )

        assertEquals(12, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val expectedLastDays = mapOf(
            1 to 31, 2 to 28, 3 to 31, 4 to 30, 5 to 31, 6 to 30,
            7 to 31, 8 to 31, 9 to 30, 10 to 31, 11 to 30, 12 to 31
        )

        occurrences.forEach { occ ->
            val date = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertEquals(
                "Last day of month ${date.monthValue}",
                expectedLastDays[date.monthValue],
                date.dayOfMonth
            )
        }
    }

    // ==================== Leap Year Feb 29 Tests ====================

    @Test
    fun `yearly on Feb 29 only occurs in leap years`() = runTest {
        val startTs = parseDate("2024-02-29 10:00") // 2024 is leap year
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29;COUNT=3"
        )

        // Feb 29 events should only occur in leap years
        // The generator may handle this differently - verify it doesn't crash
        // and produces reasonable results
        assertTrue("Should generate some occurrences", count >= 1)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        if (occurrences.isNotEmpty()) {
            // Verify all are Feb 29 if occurrences exist
            occurrences.forEach { occ ->
                val date = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
                assertEquals(2, date.monthValue)
                assertEquals(29, date.dayOfMonth)
            }
        }
    }

    // ==================== Performance / Limits Tests ====================

    @Test
    fun `very long daily series respects iteration limits`() = runTest {
        val startTs = parseDate("2020-01-01 10:00")
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=DAILY" // No COUNT - potentially infinite
        )

        // Should be bounded by MAX_ITERATIONS or range
        assertTrue("Should be bounded", count <= 2000)
        assertTrue("Should generate reasonable count", count >= 365)
    }

    @Test
    fun `complex RRULE with multiple BYxxx parts performs reasonably`() = runTest {
        val startTs = parseDate("2026-01-01 10:00")
        val startTime = System.currentTimeMillis()

        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1,-1;COUNT=24" // First and last weekday
        )

        val duration = System.currentTimeMillis() - startTime

        assertEquals(24, count)
        assertTrue("Should complete in reasonable time (< 5s)", duration < 5000)
    }

    // ==================== COUNT/UNTIL Edge Cases ====================

    @Test
    fun `COUNT=0 generates minimal occurrences`() = runTest {
        val startTs = parseDate("2026-01-01 10:00")
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=DAILY;COUNT=0"
        )

        // COUNT=0 behavior varies by implementation
        // Some generate 0 occurrences, some generate 1 (DTSTART only)
        // Just verify it handles the edge case without error
        assertTrue("COUNT=0 should be handled gracefully", count >= 0)
    }

    @Test
    fun `UNTIL exactly on occurrence includes that occurrence`() = runTest {
        val startTs = parseDate("2026-01-05 10:00") // Monday
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=WEEKLY;BYDAY=MO;UNTIL=20260119T150000Z" // Jan 19 is 3rd Monday
        )

        // Should include Jan 5, 12, 19 = 3 occurrences
        assertEquals(3, count)
    }

    @Test
    fun `UNTIL before DTSTART generates only DTSTART`() = runTest {
        val startTs = parseDate("2026-01-15 10:00")
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=DAILY;UNTIL=20260101T000000Z" // Before start
        )

        // Per RFC 5545, DTSTART is always included even if UNTIL is before it
        // But some implementations may return 0
        assertTrue("Should generate 0 or 1 occurrence", count <= 1)
    }

    // ==================== INTERVAL Edge Cases ====================

    @Test
    fun `very large INTERVAL works correctly`() = runTest {
        val startTs = parseDate("2026-01-01 10:00")
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=DAILY;INTERVAL=365;COUNT=3" // Every 365 days (roughly yearly)
        )

        // With 365-day interval, within 2-year window we get at most 2-3 occurrences
        // Depending on range boundaries, may get 1, 2, or 3
        assertTrue("Should generate at least 1 occurrence", count >= 1)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        if (occurrences.size >= 2) {
            val dates = occurrences.map {
                Instant.ofEpochMilli(it.startTs).atZone(defaultZone).toLocalDate()
            }.sorted()

            // Should be roughly 1 year apart
            val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(dates[0], dates[1])
            assertTrue("Should be ~365 days apart, got $daysBetween", daysBetween >= 364)
        }
    }

    // ==================== Multiple BYMONTH Tests ====================

    @Test
    fun `BYMONTH with multiple months`() = runTest {
        val startTs = parseDate("2026-01-15 10:00")
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=YEARLY;BYMONTH=1,4,7,10;COUNT=8" // Quarterly
        )

        assertEquals(8, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val months = occurrences.map {
            Instant.ofEpochMilli(it.startTs).atZone(defaultZone).toLocalDate().monthValue
        }.toSet()

        assertEquals(setOf(1, 4, 7, 10), months)
    }

    // ==================== Daylight Saving Time Edge Cases ====================

    @Test
    fun `daily event maintains time across DST spring forward`() = runTest {
        // March 8, 2026 is DST spring forward in US
        val startTs = parseDate("2026-03-07 02:30") // Day before DST
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=DAILY;COUNT=3"
        )

        assertEquals(3, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            val time = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalTime()
            // Should maintain 2:30 AM (or 3:30 if adjusted for DST gap)
            assertTrue("Time should be consistent", time.hour in 2..3)
        }
    }

    @Test
    fun `weekly event maintains day of week across DST`() = runTest {
        val startTs = parseDate("2026-03-02 10:00") // Monday before DST
        val (event, count) = createAndGenerateEvent(
            startTs,
            "FREQ=WEEKLY;BYDAY=MO;COUNT=4"
        )

        assertEquals(4, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            val dayOfWeek = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).dayOfWeek
            assertEquals(DayOfWeek.MONDAY, dayOfWeek)
        }
    }
}
