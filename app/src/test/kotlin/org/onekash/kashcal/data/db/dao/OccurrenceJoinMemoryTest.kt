package org.onekash.kashcal.data.db.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Regression test for OOM fix (KashCal/KashCal#140).
 *
 * Verifies that the OccurrencesDao JOIN queries exclude raw_ical to prevent
 * memory waste from duplicating large ICS blobs across occurrence rows.
 *
 * Dataset matches issue conditions: 11 calendars, ~495 events, many recurring.
 */
class OccurrenceJoinMemoryTest : BaseDaoTest() {

    private lateinit var occurrencesDao: OccurrencesDao
    private lateinit var eventsDao: EventsDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var accountsDao: AccountsDao

    private val calendarIds = mutableListOf<Long>()

    // Simulate issue #140: 11 calendars, ~495 events (reported ~493)
    private val numCalendars = 11
    private val singleEventsPerCalendar = 25
    private val recurringEventsPerCalendar = 20

    // Realistic rawIcal sizes (bytes)
    private val smallRawIcal = generateRawIcal(3_000)   // 3KB
    private val mediumRawIcal = generateRawIcal(8_000)  // 8KB
    private val largeRawIcal = generateRawIcal(20_000)  // 20KB

    // 3-year occurrence window
    private val now = parseDate("2026-04-01 12:00")
    private val windowStart = parseDate("2025-04-01 00:00")
    private val windowEnd = parseDate("2028-04-01 00:00")

    @Before
    override fun setup() {
        super.setup()
        occurrencesDao = database.occurrencesDao()
        eventsDao = database.eventsDao()
        calendarsDao = database.calendarsDao()
        accountsDao = database.accountsDao()
    }

    // ==================== Post-Fix Verification ====================

    @Test
    fun `getOccurrencesWithEventsInRange returns null rawIcal for all rows`() = runTest {
        setupRealisticDataset()

        val results = occurrencesDao.getOccurrencesWithEventsInRange(
            windowStart, windowEnd
        ).first()

        // Dataset produces thousands of occurrences
        assertTrue("Expected >1000 rows, got ${results.size}", results.size > 1000)

        // Every row must have null rawIcal (the fix)
        val nonNullCount = results.count { it.event.rawIcal != null }
        assertEquals("All rows must have null rawIcal", 0, nonNullCount)

        // Other fields must still be populated
        val uniqueEventIds = results.map { it.event.id }.distinct()
        assertTrue("Expected ~495 unique events", uniqueEventIds.size > 400)
        assertTrue("All events must have titles", results.all { it.event.title.isNotBlank() })
        assertTrue("All events must have valid startTs", results.all { it.event.startTs > 0 })
    }

    @Test
    fun `getOccurrencesWithEventsForDay returns null rawIcal for all rows`() = runTest {
        setupRealisticDataset()

        // Query a day that should have events (Apr 1 2026 = today in test)
        val dayCode = 20260401
        val results = occurrencesDao.getOccurrencesWithEventsForDay(dayCode).first()

        assertTrue("Expected events on day $dayCode, got ${results.size}", results.isNotEmpty())

        val nonNullCount = results.count { it.event.rawIcal != null }
        assertEquals("All rows must have null rawIcal (ForDay query)", 0, nonNullCount)

        // Other fields preserved
        assertTrue("All events must have titles", results.all { it.event.title.isNotBlank() })
    }

    @Test
    fun `rawIcal still accessible via direct event query`() = runTest {
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calId = calendarsDao.insert(
            Calendar(accountId = accountId, caldavUrl = "https://test/cal/",
                displayName = "Test", color = 0xFF0000FF.toInt())
        )
        val eventId = eventsDao.insert(
            Event(
                uid = "test@test.com", calendarId = calId, title = "Test Event",
                startTs = now, endTs = now + 3600_000, dtstamp = now,
                rawIcal = largeRawIcal, syncStatus = SyncStatus.SYNCED
            )
        )
        occurrencesDao.insertAll(listOf(
            Occurrence(
                eventId = eventId, calendarId = calId,
                startTs = now, endTs = now + 3600_000,
                startDay = 20260401, endDay = 20260401
            )
        ))

        // JOIN query: rawIcal is null
        val joinResult = occurrencesDao.getOccurrencesWithEventsInRange(
            now - 1000, now + 7200_000
        ).first()
        assertEquals(1, joinResult.size)
        assertNull("JOIN query must return null rawIcal", joinResult[0].event.rawIcal)

        // Direct query: rawIcal is preserved
        val directEvent = eventsDao.getById(eventId)
        assertEquals("Direct query must return full rawIcal", largeRawIcal, directEvent?.rawIcal)

        // Other fields match between JOIN and direct
        assertEquals(joinResult[0].event.title, directEvent?.title)
        assertEquals(joinResult[0].event.startTs, directEvent?.startTs)
        assertEquals(joinResult[0].event.calendarId, directEvent?.calendarId)
    }

    /** Uses raw SQL to measure what the old query (with e.raw_ical) would have loaded. */
    @Test
    fun `fix eliminates rawIcal payload duplication`() = runTest {
        setupRealisticDataset()

        // Fixed DAO query: 0 bytes rawIcal
        val fixedResults = occurrencesDao.getOccurrencesWithEventsInRange(
            windowStart, windowEnd
        ).first()
        val fixedRawIcalBytes = fixedResults.sumOf { it.event.rawIcal?.length?.toLong() ?: 0L }
        assertEquals("Fixed query must have 0 rawIcal bytes", 0L, fixedRawIcalBytes)

        // Measure what the OLD query would have loaded via raw SQL
        val oldSql = """
            SELECT SUM(LENGTH(e.raw_ical)) as total_bytes,
                   COUNT(*) as row_count
            FROM occurrences o
            JOIN events e ON (o.exception_event_id IS NOT NULL AND o.exception_event_id = e.id)
                          OR (o.exception_event_id IS NULL AND o.event_id = e.id)
            WHERE o.end_ts >= ? AND o.start_ts <= ?
            AND o.is_cancelled = 0
        """.trimIndent()

        val cursor = database.openHelper.readableDatabase.query(
            oldSql, arrayOf(windowStart.toString(), windowEnd.toString())
        )
        cursor.use { c ->
            c.moveToFirst()
            val oldTotalBytes = c.getLong(0)
            val rowCount = c.getInt(1)

            println("=" .repeat(70))
            println("OOM FIX VERIFICATION (KashCal/KashCal#140)")
            println("=" .repeat(70))
            println("Dataset: $numCalendars calendars, ~${numCalendars * (singleEventsPerCalendar + recurringEventsPerCalendar)} events")
            println("Occurrence rows: $rowCount")
            println("Old query rawIcal payload: ${formatBytes(oldTotalBytes)}")
            println("Fixed query rawIcal payload: ${formatBytes(fixedRawIcalBytes)}")
            println("MEMORY SAVED: ${formatBytes(oldTotalBytes)}")
            println("=" .repeat(70))

            assertEquals("Row count must match", rowCount, fixedResults.size)
            assertTrue(
                "Old query would have loaded >100MB of rawIcal, got ${formatBytes(oldTotalBytes)}",
                oldTotalBytes > 100_000_000
            )
        }
    }

    @Test
    fun `concurrent subscriptions have zero rawIcal overhead`() = runTest {
        setupRealisticDataset()

        val dotResults = occurrencesDao.getOccurrencesWithEventsInRange(
            parseDate("2025-10-01 00:00"), parseDate("2026-10-01 00:00")).first()
        val monthResults = occurrencesDao.getOccurrencesWithEventsInRange(
            parseDate("2026-03-15 00:00"), parseDate("2026-04-30 00:00")).first()
        val agendaResults = occurrencesDao.getOccurrencesWithEventsInRange(
            now, parseDate("2026-05-01 00:00")).first()
        val dayPagerResults = occurrencesDao.getOccurrencesWithEventsInRange(
            parseDate("2026-03-29 00:00"), parseDate("2026-04-04 00:00")).first()

        val totalRawIcalBytes = listOf(dotResults, monthResults, agendaResults, dayPagerResults)
            .sumOf { results -> results.sumOf { it.event.rawIcal?.length?.toLong() ?: 0L } }

        val totalRows = dotResults.size + monthResults.size + agendaResults.size + dayPagerResults.size

        println("Concurrent subscriptions: $totalRows total rows, ${formatBytes(totalRawIcalBytes)} rawIcal")

        assertEquals("All concurrent subscriptions must have 0 rawIcal bytes", 0L, totalRawIcalBytes)
        assertTrue("Expected >5000 total rows across subscriptions", totalRows > 5000)
    }

    private suspend fun setupRealisticDataset() {
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.CALDAV, email = "user@radicale.local")
        )

        for (calIdx in 0 until numCalendars) {
            val calId = calendarsDao.insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://radicale.local/user/cal-$calIdx/",
                    displayName = "Calendar $calIdx",
                    color = (0xFF000000 + (calIdx * 0x112233)).toInt()
                )
            )
            calendarIds.add(calId)

            for (i in 0 until singleEventsPerCalendar) {
                val dayOffset = (i * 40L)
                val startTs = windowStart + dayOffset * 24 * 3600_000
                val eventId = eventsDao.insert(
                    Event(
                        uid = "single-$calIdx-$i@radicale.local",
                        calendarId = calId,
                        title = "Single Event $i in Cal $calIdx",
                        description = "Description for event $i with some detail text " +
                            "that adds to the payload size in a realistic way.",
                        startTs = startTs,
                        endTs = startTs + 3600_000,
                        dtstamp = now,
                        rawIcal = pickRawIcal(i),
                        syncStatus = SyncStatus.SYNCED
                    )
                )
                val dayCode = toDayCode(startTs)
                occurrencesDao.insertAll(listOf(
                    Occurrence(
                        eventId = eventId, calendarId = calId,
                        startTs = startTs, endTs = startTs + 3600_000,
                        startDay = dayCode, endDay = dayCode
                    )
                ))
            }

            for (i in 0 until recurringEventsPerCalendar) {
                val baseHour = (i % 12) + 8
                val baseDayOffset = i * 3L
                val seriesStart = windowStart + baseDayOffset * 24 * 3600_000 +
                    baseHour * 3600_000
                val eventId = eventsDao.insert(
                    Event(
                        uid = "recurring-$calIdx-$i@radicale.local",
                        calendarId = calId,
                        title = "Weekly Meeting $i in Cal $calIdx",
                        description = "Recurring meeting notes with agenda items " +
                            "and action items from last week's discussion.",
                        startTs = seriesStart,
                        endTs = seriesStart + 3600_000,
                        rrule = "FREQ=WEEKLY",
                        dtstamp = now,
                        rawIcal = pickRawIcal(i + singleEventsPerCalendar),
                        syncStatus = SyncStatus.SYNCED
                    )
                )

                val occurrences = mutableListOf<Occurrence>()
                var occTs = seriesStart
                while (occTs < windowEnd) {
                    if (occTs + 3600_000 >= windowStart) {
                        val dayCode = toDayCode(occTs)
                        occurrences.add(
                            Occurrence(
                                eventId = eventId, calendarId = calId,
                                startTs = occTs, endTs = occTs + 3600_000,
                                startDay = dayCode, endDay = dayCode
                            )
                        )
                    }
                    occTs += 7 * 24 * 3600_000
                }
                occurrencesDao.insertAll(occurrences)
            }
        }
    }

    private fun pickRawIcal(index: Int): String {
        return when (index % 5) {
            0 -> largeRawIcal
            1, 2 -> mediumRawIcal
            else -> smallRawIcal
        }
    }

    private fun generateRawIcal(targetBytes: Int): String {
        val header = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//Test//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:America/New_York
            BEGIN:DAYLIGHT
            TZOFFSETFROM:-0500
            TZOFFSETTO:-0400
            TZNAME:EDT
            DTSTART:19700308T020000
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
            END:DAYLIGHT
            BEGIN:STANDARD
            TZOFFSETFROM:-0400
            TZOFFSETTO:-0500
            TZNAME:EST
            DTSTART:19701101T020000
            RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:test@radicale.local
            DTSTAMP:20260401T120000Z
            DTSTART;TZID=America/New_York:20260401T100000
            DTEND;TZID=America/New_York:20260401T110000
            SUMMARY:Test Event
        """.trimIndent()
        val footer = """
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val padding = targetBytes - header.length - footer.length
        val description = if (padding > 0) {
            "DESCRIPTION:" + "X".repeat(padding.coerceAtLeast(0)) + "\n"
        } else ""

        return header + "\n" + description + footer
    }

    private fun toDayCode(epochMs: Long): Int {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        return cal.get(java.util.Calendar.YEAR) * 10000 +
            (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
            cal.get(java.util.Calendar.DAY_OF_MONTH)
    }

    private fun parseDate(dateStr: String): Long {
        val parts = dateStr.split(" ")
        val dateParts = parts[0].split("-")
        val timeParts = parts[1].split(":")
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.set(
            dateParts[0].toInt(), dateParts[1].toInt() - 1, dateParts[2].toInt(),
            timeParts[0].toInt(), timeParts[1].toInt(), 0
        )
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
