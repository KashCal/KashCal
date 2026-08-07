package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.scheduler.parseReminderOffset
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end post-sync pipeline for the far-future, multi-day, two-VALARM event
 * that only became visible once the CalDAV time-range query dropped its 32-bit
 * upper bound (issue #326). The parser layer is already covered by SogoParseTest;
 * this drives the SAME event through the steps that run AFTER the parser —
 * map → Room insert → occurrence generation → reminder-offset parsing — because
 * a newly-surfaced event exercises those paths for the first time.
 *
 * The event runs 2026-08-15 → 2026-08-22 (Europe/Berlin), so it is genuinely in
 * the future relative to the test-fixture "now"; the assertions therefore pin the
 * behavior a real first sync would hit, not a past-dated stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SogoUrlaubPipelineTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var parser: ICalParser
    private var testCalendarId: Long = 0

    private val urlaubIcs = "BEGIN:VCALENDAR\r\n" +
        "PRODID:-//Test Client//NONSGML Sync Agent//EN\r\n" +
        "VERSION:2.0\r\n" +
        "BEGIN:VTIMEZONE\r\n" +
        "TZID:Europe/Berlin\r\n" +
        "BEGIN:STANDARD\r\n" +
        "TZNAME:CET\r\n" +
        "TZOFFSETFROM:+0200\r\n" +
        "TZOFFSETTO:+0100\r\n" +
        "DTSTART:19961027T030000\r\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n" +
        "END:STANDARD\r\n" +
        "BEGIN:DAYLIGHT\r\n" +
        "TZNAME:CEST\r\n" +
        "TZOFFSETFROM:+0100\r\n" +
        "TZOFFSETTO:+0200\r\n" +
        "DTSTART:19810329T020000\r\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n" +
        "END:DAYLIGHT\r\n" +
        "END:VTIMEZONE\r\n" +
        "BEGIN:VEVENT\r\n" +
        "DTSTAMP:20260723T152834Z\r\n" +
        "UID:1e269e9b-3529-4179-8d6e-0dbadf03f771\r\n" +
        "SUMMARY:Urlaub\r\n" +
        "DTSTART;TZID=Europe/Berlin:20260815T180000\r\n" +
        "DTEND;TZID=Europe/Berlin:20260822T180000\r\n" +
        "STATUS:CONFIRMED\r\n" +
        "BEGIN:VALARM\r\n" +
        "TRIGGER:-PT1H\r\n" +
        "ACTION:DISPLAY\r\n" +
        "DESCRIPTION:Redacted\r\n" +
        "END:VALARM\r\n" +
        "BEGIN:VALARM\r\n" +
        "TRIGGER:-P1D\r\n" +
        "ACTION:DISPLAY\r\n" +
        "DESCRIPTION:Urlaub\r\n" +
        "END:VALARM\r\n" +
        "CLASS:PUBLIC\r\n" +
        "END:VEVENT\r\n" +
        "END:VCALENDAR"

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        occurrenceGenerator = OccurrenceGenerator(
            database,
            database.occurrencesDao(),
            database.eventsDao(),
            TestDataStoreFactory.createDefault()
        )
        parser = ICalParser()

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

    @Test
    fun `Urlaub event maps, inserts, and generates a single valid occurrence`() = runTest {
        // Parse + map (parser layer proven elsewhere; here it feeds the DB path).
        val parsed = (parser.parseAllEvents(urlaubIcs) as ParseResult.Success).value.single()
        val mapped = ICalEventMapper.toEntity(
            parsed, urlaubIcs, testCalendarId, "urlaub.ics", "etag-urlaub"
        ).event.copy(
            calendarId = testCalendarId,
            syncStatus = SyncStatus.SYNCED
        )

        // Insert into Room exactly as PullStrategy would.
        val eventId = database.eventsDao().insert(mapped)
        val stored = mapped.copy(id = eventId)

        // Generate occurrences over a window that spans the event's dates. This is
        // the first code that consumes the newly-surfaced event's timestamps.
        val count = occurrenceGenerator.generateOccurrences(
            stored,
            rangeStartMs = parseUtc("2026-01-01 00:00"),
            rangeEndMs = parseUtc("2027-12-31 23:59")
        )

        assertEquals("Non-recurring event yields exactly one occurrence", 1, count)

        val occurrences = database.occurrencesDao().getForEvent(eventId)
        assertEquals(1, occurrences.size)
        val occ = occurrences.single()

        // Duration must stay non-negative through the pipeline (endTs >= startTs).
        assertTrue(
            "occurrence endTs (${occ.endTs}) must be >= startTs (${occ.startTs})",
            occ.endTs >= occ.startTs
        )

        // Multi-day: Aug 15 → Aug 22, 2026. Day codes must be well-formed YYYYMMDD
        // (guards the eventTsToEndDayCode path against Int overflow / bad math).
        assertEquals(20260815, occ.startDay)
        assertEquals(20260822, occ.endDay)
        assertTrue("endDay must not precede startDay", occ.endDay >= occ.startDay)
    }

    @Test
    fun `Urlaub event's two VALARM triggers parse to usable reminder offsets`() {
        val parsed = (parser.parseAllEvents(urlaubIcs) as ParseResult.Success).value.single()
        val mapped = ICalEventMapper.toEntity(
            parsed, urlaubIcs, testCalendarId, "urlaub.ics", "etag-urlaub"
        ).event

        assertEquals("Both VALARMs mapped to reminders", 2, mapped.alarmCount)
        val reminders = mapped.reminders
        assertNotNull("Reminders must be stored", reminders)

        // Every stored trigger must parse (a null here is the shape that lets a
        // bad offset slip silently past ReminderScheduler).
        reminders!!.forEach { offset ->
            val parsedOffset = parseReminderOffset(offset)
            assertNotNull("Trigger '$offset' must parse to a non-null offset", parsedOffset)
            assertFalse("A 'before' trigger must be negative: $offset", parsedOffset!! > 0)
        }

        // -PT1H = one hour before; -P1D = one day before.
        val parsedMs = reminders.map { parseReminderOffset(it) }.toSet()
        assertTrue("Expected a -1h offset", parsedMs.contains(-60L * 60 * 1000))
        assertTrue("Expected a -1d offset", parsedMs.contains(-24L * 60 * 60 * 1000))
    }

    private fun parseUtc(dateStr: String): Long {
        val parts = dateStr.split(" ")
        val d = parts[0].split("-")
        val t = parts[1].split(":")
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(d[0].toInt(), d[1].toInt() - 1, d[2].toInt(), t[0].toInt(), t[1].toInt(), 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
