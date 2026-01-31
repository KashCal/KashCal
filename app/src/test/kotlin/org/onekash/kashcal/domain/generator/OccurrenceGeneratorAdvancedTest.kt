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
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Advanced RRULE tests for OccurrenceGenerator.
 *
 * Tests complex patterns identified as missing:
 * - Negative BYDAY offsets (-1MO, -2FR)
 * - BYSETPOS combinations
 * - Multiple BYMONTHDAY values
 * - Performance limits (MAX_ITERATIONS)
 * - Edge cases (COUNT=1, UNTIL scenarios)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OccurrenceGeneratorAdvancedTest {

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

    private suspend fun createAndInsertEvent(
        startTs: Long,
        endTs: Long,
        rrule: String? = null,
        exdate: String? = null,
        isAllDay: Boolean = false
    ): Event {
        val event = Event(
            id = 0,
            uid = "test-${System.currentTimeMillis()}",
            calendarId = testCalendarId,
            title = "Test Event",
            startTs = startTs,
            endTs = endTs,
            rrule = rrule,
            exdate = exdate,
            isAllDay = isAllDay,
            dtstamp = System.currentTimeMillis()
        )
        val id = database.eventsDao().insert(event)
        return event.copy(id = id)
    }

    // ==================== Negative BYDAY Tests ====================

    @Test
    fun `monthly last Monday (-1MO)`() = runTest {
        val startTs = parseDate("2026-01-26 10:00") // Last Monday of Jan 2026
        val event = createAndInsertEvent(startTs, startTs + 3600000, rrule = "FREQ=MONTHLY;BYDAY=-1MO;COUNT=6")

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2026-01-01 00:00"),
            parseDate("2026-12-31 23:59")
        )

        assertEquals(6, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(6, occurrences.size)

        // Verify dates are last Mondays
        val expectedLastMondays = listOf(
            LocalDate.of(2026, 1, 26),
            LocalDate.of(2026, 2, 23),
            LocalDate.of(2026, 3, 30),
            LocalDate.of(2026, 4, 27),
            LocalDate.of(2026, 5, 25),
            LocalDate.of(2026, 6, 29)
        )

        occurrences.forEachIndexed { index, occ ->
            val occDate = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertEquals("Occurrence $index", expectedLastMondays[index], occDate)
        }
    }

    @Test
    fun `monthly last Friday (-1FR)`() = runTest {
        val startTs = parseDate("2026-01-30 14:00") // Last Friday of Jan 2026
        val event = createAndInsertEvent(startTs, startTs + 3600000, rrule = "FREQ=MONTHLY;BYDAY=-1FR;COUNT=4")

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2026-01-01 00:00"),
            parseDate("2026-06-30 23:59")
        )

        assertEquals(4, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val expectedLastFridays = listOf(
            LocalDate.of(2026, 1, 30),
            LocalDate.of(2026, 2, 27),
            LocalDate.of(2026, 3, 27),
            LocalDate.of(2026, 4, 24)
        )

        occurrences.forEachIndexed { index, occ ->
            val occDate = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertEquals("Occurrence $index", expectedLastFridays[index], occDate)
        }
    }

    // ==================== BYSETPOS Tests ====================

    @Test
    fun `monthly last weekday (BYSETPOS=-1)`() = runTest {
        val startTs = parseDate("2026-01-30 10:00") // Last weekday of Jan (Fri 30th)
        val event = createAndInsertEvent(
            startTs,
            startTs + 3600000,
            rrule = "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=4"
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2026-01-01 00:00"),
            parseDate("2026-06-30 23:59")
        )

        assertEquals(4, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        // Jan 30 (Fri), Feb 27 (Fri), Mar 31 (Tue), Apr 30 (Thu)
        val expected = listOf(
            LocalDate.of(2026, 1, 30),
            LocalDate.of(2026, 2, 27),
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 30)
        )

        occurrences.forEachIndexed { index, occ ->
            val occDate = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertEquals("Occurrence $index", expected[index], occDate)
        }
    }

    // ==================== Multiple BYMONTHDAY Tests ====================

    @Test
    fun `monthly on 1st and 15th`() = runTest {
        val startTs = parseDate("2026-01-01 10:00")
        val event = createAndInsertEvent(startTs, startTs + 3600000, rrule = "FREQ=MONTHLY;BYMONTHDAY=1,15;COUNT=6")

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2026-01-01 00:00"),
            parseDate("2026-06-30 23:59")
        )

        assertEquals(6, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val expected = listOf(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 15),
            LocalDate.of(2026, 2, 1),
            LocalDate.of(2026, 2, 15),
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 15)
        )

        occurrences.forEachIndexed { index, occ ->
            val occDate = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertEquals("Occurrence $index", expected[index], occDate)
        }
    }

    @Test
    fun `monthly last day (-1) skips months correctly`() = runTest {
        val startTs = parseDate("2026-01-31 10:00")
        val event = createAndInsertEvent(startTs, startTs + 3600000, rrule = "FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=4")

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2026-01-01 00:00"),
            parseDate("2026-06-30 23:59")
        )

        assertEquals(4, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        // Last day of each month
        val expected = listOf(
            LocalDate.of(2026, 1, 31),
            LocalDate.of(2026, 2, 28),
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 30)
        )

        occurrences.forEachIndexed { index, occ ->
            val occDate = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertEquals("Occurrence $index", expected[index], occDate)
        }
    }

    // ==================== US Thanksgiving Pattern ====================

    @Test
    fun `US Thanksgiving - 4th Thursday of November`() = runTest {
        val startTs = parseDate("2026-11-26 10:00") // Thanksgiving 2026
        val event = createAndInsertEvent(startTs, startTs + 3600000, rrule = "FREQ=YEARLY;BYMONTH=11;BYDAY=4TH;COUNT=3")

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2026-01-01 00:00"),
            parseDate("2030-12-31 23:59")
        )

        assertEquals(3, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val expected = listOf(
            LocalDate.of(2026, 11, 26),
            LocalDate.of(2027, 11, 25),
            LocalDate.of(2028, 11, 23)
        )

        occurrences.forEachIndexed { index, occ ->
            val occDate = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertEquals("Occurrence $index", expected[index], occDate)
        }
    }

    // ==================== Performance / Limits Tests ====================

    @Test
    fun `daily event respects iteration limits`() = runTest {
        val startTs = parseDate("2026-01-01 10:00")
        val event = createAndInsertEvent(startTs, startTs + 3600000, rrule = "FREQ=DAILY")

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2026-01-01 00:00"),
            parseDate("2030-12-31 23:59")
        )

        // Should be limited by range or MAX_ITERATIONS
        assertTrue("Should have at least 365 occurrences", count >= 365)
        assertTrue("Should be bounded", count <= 2000)
    }

    @Test
    fun `event with COUNT=1 generates single occurrence`() = runTest {
        val startTs = parseDate("2026-01-15 10:00")
        val event = createAndInsertEvent(startTs, startTs + 3600000, rrule = "FREQ=DAILY;COUNT=1")

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2026-01-01 00:00"),
            parseDate("2026-12-31 23:59")
        )

        assertEquals(1, count)
    }

    // ==================== EXDATE Tests ====================

    @Test
    fun `weekly BYDAY with EXDATE removes specific occurrence`() = runTest {
        val startTs = parseDate("2026-01-05 10:00") // Monday
        val event = createAndInsertEvent(
            startTs,
            startTs + 3600000,
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=9",
            exdate = "20260107T150000Z" // Remove Wed Jan 7
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2026-01-01 00:00"),
            parseDate("2026-03-31 23:59")
        )

        // 9 original - 1 excluded = 8
        assertEquals(8, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(8, occurrences.size)
    }

    // ==================== Interval Tests ====================

    @Test
    fun `bi-weekly (INTERVAL=2) generates correct dates`() = runTest {
        val startTs = parseDate("2026-01-05 10:00") // Monday
        val event = createAndInsertEvent(startTs, startTs + 3600000, rrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;COUNT=4")

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2026-01-01 00:00"),
            parseDate("2026-06-30 23:59")
        )

        assertEquals(4, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val expected = listOf(
            LocalDate.of(2026, 1, 5),
            LocalDate.of(2026, 1, 19),
            LocalDate.of(2026, 2, 2),
            LocalDate.of(2026, 2, 16)
        )

        occurrences.forEachIndexed { index, occ ->
            val occDate = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertEquals("Occurrence $index", expected[index], occDate)
        }
    }

    @Test
    fun `daily INTERVAL=7 is equivalent to weekly`() = runTest {
        val startTs = parseDate("2026-01-05 10:00")

        val dailyEvent = createAndInsertEvent(startTs, startTs + 3600000, rrule = "FREQ=DAILY;INTERVAL=7;COUNT=4")
        val dailyCount = occurrenceGenerator.generateOccurrences(
            dailyEvent,
            parseDate("2026-01-01 00:00"),
            parseDate("2026-06-30 23:59")
        )
        val dailyOccs = database.occurrencesDao().getForEvent(dailyEvent.id)

        val weeklyEvent = createAndInsertEvent(startTs + 1, startTs + 3600001, rrule = "FREQ=WEEKLY;COUNT=4")
        val weeklyCount = occurrenceGenerator.generateOccurrences(
            weeklyEvent,
            parseDate("2026-01-01 00:00"),
            parseDate("2026-06-30 23:59")
        )
        val weeklyOccs = database.occurrencesDao().getForEvent(weeklyEvent.id)

        assertEquals(4, dailyCount)
        assertEquals(4, weeklyCount)

        // Same day of week pattern
        dailyOccs.forEachIndexed { index, occ ->
            val dailyDate = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            val weeklyDate = Instant.ofEpochMilli(weeklyOccs[index].startTs).atZone(defaultZone).toLocalDate()
            assertEquals(dailyDate.dayOfWeek, weeklyDate.dayOfWeek)
        }
    }

    // ==================== Yearly Patterns ====================

    @Test
    fun `yearly specific month (BYMONTH=6)`() = runTest {
        val startTs = parseDate("2026-06-15 10:00")
        val event = createAndInsertEvent(startTs, startTs + 3600000, rrule = "FREQ=YEARLY;BYMONTH=6;COUNT=3")

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2026-01-01 00:00"),
            parseDate("2030-12-31 23:59")
        )

        assertEquals(3, count)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val expected = listOf(
            LocalDate.of(2026, 6, 15),
            LocalDate.of(2027, 6, 15),
            LocalDate.of(2028, 6, 15)
        )

        occurrences.forEachIndexed { index, occ ->
            val occDate = Instant.ofEpochMilli(occ.startTs).atZone(defaultZone).toLocalDate()
            assertEquals("Occurrence $index", expected[index], occDate)
        }
    }
}
