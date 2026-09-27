package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that the fresh path ([IcsPatcher.generateFresh]) ends every VEVENT with DTEND and never
 * DURATION, with or without an RRULE.
 *
 * RFC 5545 §3.6.1 permits either DTEND or DURATION, never both. KashCal emits DTEND on every
 * serialization path (fresh, patch and the exception overload) so they agree, and because at
 * least one major server (iCloud) rejects an EXDATE update on a bounded recurring scheduling
 * object written as DTSTART+DURATION, while DTEND is accepted everywhere.
 *
 * An exception describes one occurrence, so the exception overload clears its RRULE and also
 * emits DTEND.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DurationFreshPathTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ---------- Helpers ----------

    /**
     * Finds lines starting with [prefix] inside the first VEVENT block. VTIMEZONE
     * sub-components emit DTSTART for DST transitions, which a whole-file filter would match.
     */
    private fun findLines(ics: String, prefix: String): List<String> {
        val lines = ics.lines()
        val start = lines.indexOfFirst { it.trim() == "BEGIN:VEVENT" }
        val end = lines.indexOfFirst { it.trim() == "END:VEVENT" }
        if (start < 0 || end < 0 || end <= start) return emptyList()
        return lines.subList(start + 1, end).filter { it.startsWith(prefix) }
    }

    private fun createEvent(
        uid: String = "a03-test@kashcal.test",
        title: String = "Duration Fresh-Path Test",
        startTs: Long = 1_767_088_800_000L,   // 2025-12-30T14:00:00Z
        endTs: Long = 1_767_092_400_000L,     // 2025-12-30T15:00:00Z (1 hour after start)
        isAllDay: Boolean = false,
        timezone: String? = "UTC",
        rrule: String? = null,
        duration: String? = null,
        originalEventId: Long? = null,
        originalInstanceTime: Long? = null,
        organizerEmail: String? = null,
        exdate: String? = null
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
        duration = duration,
        organizerEmail = organizerEmail,
        exdate = exdate,
        originalEventId = originalEventId,
        originalInstanceTime = originalInstanceTime,
        sequence = 0,
        rawIcal = null,
        dtstamp = 0L,
        createdAt = 1_579_089_600_000L,
        updatedAt = 1_718_440_200_000L,
        syncStatus = SyncStatus.SYNCED
    )

    // ========== Recurring → DTEND form ==========

    @Test
    fun `fresh path emits DTEND and no DURATION when event has RRULE`() {
        // 1-hour window + RRULE → DTEND one hour after DTSTART, no DURATION.
        val event = createEvent(
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR",
            duration = null
        )

        val ics = IcsPatcher.generateFresh(event)

        val dtEndLines = findLines(ics, "DTEND")
        val durationLines = findLines(ics, "DURATION:")
        assertEquals(
            "Recurring event must emit exactly one DTEND line; got: $dtEndLines",
            1,
            dtEndLines.size
        )
        assertTrue(
            "Recurring event must not emit DURATION; got: $durationLines",
            durationLines.isEmpty()
        )
    }

    @Test
    fun `fresh path ignores stored Event duration string and emits DTEND from endTs`() {
        // Stored Event.duration = "PT30M" but the window (startTs..endTs) is 1 hour.
        // The end form comes from endTs, never the duration column.
        val event = createEvent(
            rrule = "FREQ=DAILY",
            duration = "PT30M"
        )

        val ics = IcsPatcher.generateFresh(event)

        assertTrue(
            "Recurring event must emit DTEND regardless of stored duration string",
            findLines(ics, "DTEND").isNotEmpty()
        )
        assertTrue(
            "Stored Event.duration must not produce a DURATION line on the wire; got: ${findLines(ics, "DURATION:")}",
            findLines(ics, "DURATION:").isEmpty()
        )
    }

    @Test
    fun `fresh path emits exclusive next-day DTEND for single-day all-day recurring event`() {
        // Single all-day Dec 25: startTs = Dec 25 00:00 UTC, endTs = Dec 25 23:59:59.999 UTC.
        // exclusiveEndTs (endTs + 1) → Dec 26 00:00, so DTEND;VALUE=DATE:20251226.
        // The DATE form avoids a sub-second PT23H59M59.999S duration.
        val startTs = 1_766_620_800_000L          // 2025-12-25T00:00:00Z
        val endTs = startTs + 86_400_000L - 1     // inclusive last-second
        val event = createEvent(
            startTs = startTs,
            endTs = endTs,
            isAllDay = true,
            timezone = null,
            rrule = "FREQ=WEEKLY",
            duration = null
        )

        val ics = IcsPatcher.generateFresh(event)

        assertEquals(
            "All-day single-day recurring event must emit exclusive next-day DTEND;VALUE=DATE",
            listOf("DTEND;VALUE=DATE:20251226"),
            findLines(ics, "DTEND")
        )
        assertTrue(
            "All-day recurring event must not emit DURATION; got: ${findLines(ics, "DURATION:")}",
            findLines(ics, "DURATION:").isEmpty()
        )
    }

    @Test
    fun `fresh path emits exclusive end-date DTEND for multi-day all-day recurring event`() {
        // Feb 18-20 (3 days inclusive) all-day → exclusive DTEND on Feb 21.
        val startTs = 1_739_836_800_000L              // 2025-02-18T00:00:00Z
        val endTs = startTs + 3 * 86_400_000L - 1     // Feb 20 23:59:59.999
        val event = createEvent(
            startTs = startTs,
            endTs = endTs,
            isAllDay = true,
            timezone = null,
            rrule = "FREQ=YEARLY",
            duration = null
        )

        val ics = IcsPatcher.generateFresh(event)

        assertEquals(
            "3-day all-day recurring event must emit exclusive DTEND;VALUE=DATE:20250221",
            listOf("DTEND;VALUE=DATE:20250221"),
            findLines(ics, "DTEND")
        )
        assertTrue(findLines(ics, "DURATION:").isEmpty())
    }

    @Test
    fun `fresh path emits DTEND no DURATION for organized bounded recurring event with EXDATE`() {
        // The exact iCloud-rejected shape: ORGANIZER + bounded RRULE (COUNT) + EXDATE.
        // iCloud 500s when this is serialized with DTSTART+DURATION; DTEND is accepted
        // by every server tested. This guards the wire form that occurrence-delete
        // (which adds an EXDATE to the master and re-PUTs it) must produce.
        val event = createEvent(
            rrule = "FREQ=DAILY;COUNT=10",
            duration = null,
            organizerEmail = "host@example.test",
            exdate = (1_767_088_800_000L + 86_400_000L).toString()  // second occurrence excluded
        )

        val ics = IcsPatcher.generateFresh(event)

        assertTrue(
            "Organized bounded recurring event must emit DTEND",
            findLines(ics, "DTEND").isNotEmpty()
        )
        assertTrue(
            "Organized bounded recurring event must NOT emit DURATION (iCloud rejects DURATION+EXDATE here); got: ${findLines(ics, "DURATION:")}",
            findLines(ics, "DURATION:").isEmpty()
        )
        assertTrue(
            "EXDATE must be present (occurrence cancellation)",
            findLines(ics, "EXDATE").isNotEmpty()
        )
        assertTrue(
            "ORGANIZER must be present (scheduling object)",
            findLines(ics, "ORGANIZER").isNotEmpty()
        )
    }

    // ========== Non-recurring → DTEND form (regression guards) ==========

    @Test
    fun `fresh path emits DTEND and no DURATION when event has no RRULE`() {
        // Plain timed event, no RRULE.
        val event = createEvent(rrule = null, duration = null)

        val ics = IcsPatcher.generateFresh(event)

        assertTrue(
            "Non-recurring event must emit DTEND; got DTEND lines: ${findLines(ics, "DTEND")}",
            findLines(ics, "DTEND").isNotEmpty()
        )
        assertTrue(
            "Non-recurring event must not emit DURATION; got: ${findLines(ics, "DURATION:")}",
            findLines(ics, "DURATION:").isEmpty()
        )
    }

    @Test
    fun `fresh path emits DTEND even when non-recurring event has Event duration populated`() {
        // rrule=null but the duration column is set, as a pulled one-off written with
        // DURATION leaves it. The stored column never drives the end form: DTEND.
        val event = createEvent(
            rrule = null,
            duration = "PT30M"
        )

        val ics = IcsPatcher.generateFresh(event)

        assertTrue(findLines(ics, "DTEND").isNotEmpty())
        assertTrue(
            "Non-recurring event with stored duration must still emit DTEND, not DURATION",
            findLines(ics, "DURATION:").isEmpty()
        )
    }

    // ========== Exception overload ==========

    @Test
    fun `fresh path exception overload always emits DTEND no DURATION even when exception row has stale rrule`() {
        // Master with RRULE, and the exception row also has a stale rrule = "FREQ=DAILY"
        // (corrupt but possible; the exception overload emits rrule=null regardless).
        // The overload must still emit DTEND, which catches an end form keyed on
        // exception.rrule != null.
        val masterUid = "bundle-master@kashcal.test"
        val master = createEvent(
            uid = masterUid,
            rrule = "FREQ=WEEKLY"
        )
        val exception = createEvent(
            uid = masterUid,
            rrule = "FREQ=DAILY",   // stale; must not trigger the DURATION form
            originalEventId = 1L,
            originalInstanceTime = master.startTs
        )

        val icalEvent = EventToICalEventMapper.toICalEvent(master, exception)

        assertNotNull(
            "Exception overload must emit DTEND (exceptions never recur per RFC 5545 §3.8.5.1)",
            icalEvent.dtEnd
        )
        assertNull(
            "Exception overload must not emit DURATION regardless of stored rrule on exception row",
            icalEvent.duration
        )
    }

    // ========== Round-trip ==========

    @Test
    fun `round-trip recurring event - DTEND wire form leaves Event duration null`() {
        // A recurring event emits DTEND on the wire, so the re-mapped Event.duration is
        // null: the duration column is set only when the source carried DURATION.
        val event = createEvent(
            rrule = "FREQ=WEEKLY",
            duration = null
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()
        val roundTripped = ICalEventMapper.toEntity(parsed, ics, 1L, null, null).event

        assertNotNull("RRULE must survive round-trip", roundTripped.rrule)
        assertTrue(roundTripped.rrule!!.contains("FREQ=WEEKLY"))
        assertNull(
            "DTEND wire form must leave Event.duration null (no DURATION property emitted)",
            roundTripped.duration
        )
    }

    @Test
    fun `round-trip recurring event endTs recovered correctly from DTEND`() {
        // The pull mapper rebuilds Event.endTs from the emitted DTEND, which must equal
        // the original window end.
        val event = createEvent(
            rrule = "FREQ=WEEKLY",
            duration = null
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()
        val roundTripped = ICalEventMapper.toEntity(parsed, ics, 1L, null, null).event

        assertEquals(
            "Round-tripped endTs must equal original (reconstructed from DTEND)",
            event.endTs,
            roundTripped.endTs
        )
    }
}
