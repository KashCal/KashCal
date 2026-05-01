package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * A0.4 — RDATE pushable on patch path.
 *
 * `IcsPatcher.patchToICalEvent` previously preserved server RDATEs via Kotlin
 * `copy()` omission — local edits to `Event.rdate` were silently dropped on push.
 * Mirror the existing `exdates` handling by adding rdates to the copy() call.
 *
 * Note on timestamps: `Event.rdate` CSV must be stringified millisecond-epoch
 * longs (parseTimestampCsv uses toLongOrNull). See IcsPatcherRfc5545Test:669-677
 * for the established pattern used throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RDatePatchPathTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ---------- Timestamp constants (millisecond epoch for Event.rdate column) ----------

    // 2026-02-14T10:00:00Z
    private val rdateMs_Feb14 = ZonedDateTime.of(2026, 2, 14, 10, 0, 0, 0, ZoneOffset.UTC)
        .toInstant().toEpochMilli()
    // 2026-06-01T10:00:00Z
    private val rdateMs_Jun1 = ZonedDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC)
        .toInstant().toEpochMilli()
    // 2026-01-01T10:00:00Z (used for server's "original" RDATE in override tests)
    private val rdateMs_Jan1 = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC)
        .toInstant().toEpochMilli()

    // ---------- Helpers ----------

    private fun findLines(ics: String, prefix: String): List<String> {
        val lines = ics.lines()
        val start = lines.indexOfFirst { it.trim() == "BEGIN:VEVENT" }
        val end = lines.indexOfFirst { it.trim() == "END:VEVENT" }
        if (start < 0 || end < 0 || end <= start) return emptyList()
        return lines.subList(start + 1, end).filter { it.startsWith(prefix) }
    }

    private fun createEvent(
        uid: String = "a04-test@kashcal.test",
        title: String = "A0.4 Test",
        startTs: Long = 1_767_088_800_000L,   // 2025-12-30T14:00:00Z
        endTs: Long = 1_767_092_400_000L,     // 2025-12-30T15:00:00Z (1 hour)
        isAllDay: Boolean = false,
        timezone: String? = "UTC",
        rrule: String? = "FREQ=WEEKLY;BYDAY=TU",
        exdate: String? = null,
        rdate: String? = null,
        rawIcal: String? = null
    ): Event = Event(
        uid = uid,
        calendarId = 1L,
        title = title,
        startTs = startTs,
        endTs = endTs,
        isAllDay = isAllDay,
        timezone = timezone,
        status = "CONFIRMED",
        transp = "OPAQUE",
        classification = "PUBLIC",
        rrule = rrule,
        exdate = exdate,
        rdate = rdate,
        rawIcal = rawIcal,
        sequence = 0,
        dtstamp = 0L,
        createdAt = 1_579_089_600_000L,
        updatedAt = 1_718_440_200_000L,
        syncStatus = SyncStatus.SYNCED
    )

    /**
     * Build a rawIcal fixture for patch-path tests. ICS timestamp format is OK
     * here (this feeds the parser, not parseTimestampCsv).
     */
    private fun buildRawIcal(
        uid: String = "a04-test@kashcal.test",
        rrule: String? = "FREQ=WEEKLY;BYDAY=TU",
        rdateIcs: List<String> = emptyList(),
        exdateIcs: List<String> = emptyList(),
        extraProps: List<String> = emptyList()
    ): String = buildString {
        appendLine("BEGIN:VCALENDAR")
        appendLine("VERSION:2.0")
        appendLine("PRODID:-//Test//Test//EN")
        appendLine("BEGIN:VEVENT")
        appendLine("UID:$uid")
        appendLine("DTSTAMP:20251220T100000Z")
        appendLine("DTSTART:20251230T140000Z")
        appendLine("DTEND:20251230T150000Z")
        appendLine("SUMMARY:Patch Test")
        rrule?.let { appendLine("RRULE:$it") }
        rdateIcs.forEach { appendLine("RDATE:$it") }
        exdateIcs.forEach { appendLine("EXDATE:$it") }
        extraProps.forEach { appendLine(it) }
        appendLine("END:VEVENT")
        appendLine("END:VCALENDAR")
    }

    // ========== Patch path override (core bug fix) ==========

    @Test
    fun `patch path emits RDATE when Event rdate set and rawIcal has no RDATE`() {
        // Pulled event had no RDATE; user adds one-off extra occurrence.
        val raw = buildRawIcal(rdateIcs = emptyList())
        val event = createEvent(
            rdate = "$rdateMs_Feb14",
            rawIcal = raw
        )

        val ics = IcsPatcher.patch(raw, event)

        val rdateLines = findLines(ics, "RDATE")
        assertEquals(
            "Patch path must emit one RDATE line from Event.rdate when rawIcal had none",
            1,
            rdateLines.size
        )
        assertTrue(
            "Output RDATE must reflect Event.rdate (Feb 14 2026)",
            rdateLines.single().contains("20260214")
        )
    }

    @Test
    fun `patch path overrides server RDATE when Event rdate differs`() {
        // rawIcal has Jan 1 RDATE; Event.rdate changed to Feb 14.
        val raw = buildRawIcal(rdateIcs = listOf("20260101T100000Z"))
        val event = createEvent(
            rdate = "$rdateMs_Feb14",
            rawIcal = raw
        )

        val ics = IcsPatcher.patch(raw, event)

        val rdateLines = findLines(ics, "RDATE")
        assertEquals("Exactly one RDATE line after override", 1, rdateLines.size)
        assertTrue(
            "RDATE must reflect Event value (Feb 14); got: ${rdateLines.single()}",
            rdateLines.single().contains("20260214")
        )
        // Negative assertion: rawIcal's original Jan 1 must not survive.
        assertFalse(
            "RawIcal's original Jan 1 RDATE must not survive the override",
            rdateLines.single().contains("20260101")
        )
    }

    @Test
    fun `patch path emits no RDATE line when Event rdate null regardless of server RDATE`() {
        // rawIcal has Jan 1 RDATE; user cleared Event.rdate to null. Push must drop it.
        val raw = buildRawIcal(rdateIcs = listOf("20260101T100000Z"))
        val event = createEvent(
            rdate = null,
            rawIcal = raw
        )

        val ics = IcsPatcher.patch(raw, event)

        assertEquals(
            "Null Event.rdate must produce zero RDATE lines, even when rawIcal had one",
            emptyList<String>(),
            findLines(ics, "RDATE")
        )
    }

    @Test
    fun `patch path emits multiple RDATE lines for CSV with multiple timestamps`() {
        // Two RDATEs in Event.rdate column → two separate RDATE lines in output
        // (ICalGenerator.kt:202-204 emits forEach, one line per entry).
        val raw = buildRawIcal(rdateIcs = emptyList())
        val event = createEvent(
            rdate = "$rdateMs_Feb14,$rdateMs_Jun1",
            rawIcal = raw
        )

        val ics = IcsPatcher.patch(raw, event)

        val rdateLines = findLines(ics, "RDATE")
        assertEquals(
            "Two RDATEs in CSV must produce two RDATE lines; got: $rdateLines",
            2,
            rdateLines.size
        )
        assertTrue(
            "One line must cover Feb 14",
            rdateLines.any { it.contains("20260214") }
        )
        assertTrue(
            "One line must cover Jun 1",
            rdateLines.any { it.contains("20260601") }
        )
    }

    // ========== All-day ==========

    @Test
    fun `patch path emits all-day RDATE as DATE value without TZID`() {
        // All-day event with rdate populated. Output RDATE should use VALUE=DATE form
        // (or a DATE-only timestamp without T0...) and carry no TZID.
        // Midnight UTC for Feb 14 2026.
        val allDayMs = ZonedDateTime.of(2026, 2, 14, 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val allDayStart = ZonedDateTime.of(2025, 12, 30, 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val allDayEnd = allDayStart + 86_399_999L  // inclusive last ms of same day

        val raw = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:a04-allday@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20251230
            DTEND;VALUE=DATE:20251231
            SUMMARY:All Day Patch Test
            RRULE:FREQ=WEEKLY;BYDAY=TU
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = createEvent(
            uid = "a04-allday@kashcal.test",
            startTs = allDayStart,
            endTs = allDayEnd,
            isAllDay = true,
            timezone = null,
            rdate = "$allDayMs",
            rawIcal = raw
        )

        val ics = IcsPatcher.patch(raw, event)

        val rdateLines = findLines(ics, "RDATE")
        assertEquals("Exactly one RDATE line", 1, rdateLines.size)
        val line = rdateLines.single()
        assertFalse(
            "All-day RDATE must not carry TZID param; got: $line",
            line.contains("TZID=")
        )
        assertTrue(
            "All-day RDATE must carry VALUE=DATE parameter; got: $line",
            line.contains("VALUE=DATE")
        )
        // Extract the value after the final colon; for DATE form, must be YYYYMMDD with no 'T' delimiter.
        val valuePart = line.substringAfterLast(':')
        assertFalse(
            "All-day RDATE value must be date-only (no T time marker); got value '$valuePart' in line: $line",
            valuePart.contains("T")
        )
        assertTrue(
            "RDATE must reference Feb 14 2026; got: $line",
            line.contains("20260214")
        )
    }

    // ========== Cross-contamination guard ==========

    @Test
    fun `patch path RDATE override does not affect EXDATE RRULE or rawProperties`() {
        // rawIcal has RRULE, EXDATE (Jan 5), an X-custom prop, and Jan 1 RDATE.
        // Event overrides rdate and exdate to different values.
        val raw = buildRawIcal(
            rrule = "FREQ=WEEKLY;BYDAY=TU",
            rdateIcs = listOf("20260101T100000Z"),
            exdateIcs = listOf("20260105T100000Z"),
            extraProps = listOf("X-APPLE-MARKER:foo")
        )
        val newExdateMs = ZonedDateTime.of(2026, 3, 10, 10, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val event = createEvent(
            rrule = "FREQ=WEEKLY;BYDAY=TU",  // unchanged
            rdate = "$rdateMs_Feb14",
            exdate = "$newExdateMs",
            rawIcal = raw
        )

        val ics = IcsPatcher.patch(raw, event)

        // RDATE reflects Event (Feb 14)
        assertTrue(
            "RDATE must reflect Event value (Feb 14)",
            findLines(ics, "RDATE").single().contains("20260214")
        )
        // EXDATE reflects Event's new value (March 10), not rawIcal's original (Jan 5)
        val exdateLines = findLines(ics, "EXDATE")
        assertTrue(
            "EXDATE must reflect new Event value (March 10); got: $exdateLines",
            exdateLines.any { it.contains("20260310") }
        )
        // RRULE unchanged
        val rruleLines = findLines(ics, "RRULE")
        assertTrue(
            "RRULE must remain FREQ=WEEKLY;BYDAY=TU; got: $rruleLines",
            rruleLines.any { it.contains("FREQ=WEEKLY") && it.contains("BYDAY=TU") }
        )
        // X-* property preserved from rawIcal via copy() omission
        assertTrue(
            "X-APPLE-MARKER custom prop must be preserved from rawIcal",
            ics.contains("X-APPLE-MARKER:foo")
        )
    }

    // ========== No-regression guards (green pre-fix) ==========

    @Test
    fun `patch path emits no RDATE when both Event rdate and server RDATE are null`() {
        // Common case: no RDATE anywhere. Must stay no RDATE.
        val raw = buildRawIcal(rdateIcs = emptyList())
        val event = createEvent(
            rdate = null,
            rawIcal = raw
        )

        val ics = IcsPatcher.patch(raw, event)

        assertEquals(emptyList<String>(), findLines(ics, "RDATE"))
    }

    @Test
    fun `fresh path still emits RDATE from Event rdate unchanged`() {
        // Regression guard: fresh path (no rawIcal) must continue to emit RDATE
        // from Event.rdate. A0.4 does not touch EventToICalEventMapper.
        val event = createEvent(
            rdate = "$rdateMs_Feb14",
            rawIcal = null
        )

        val ics = IcsPatcher.generateFresh(event)

        val rdateLines = findLines(ics, "RDATE")
        assertEquals("Fresh path still emits one RDATE line", 1, rdateLines.size)
        assertTrue(
            "Fresh-path RDATE must reflect Event.rdate",
            rdateLines.single().contains("20260214")
        )
    }
}
