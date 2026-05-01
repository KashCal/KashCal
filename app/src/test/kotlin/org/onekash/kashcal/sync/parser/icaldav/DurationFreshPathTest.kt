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

/**
 * A0.3 — DURATION fresh-path preservation for recurring events.
 *
 * Rule (Fossify + Etar + AOSP Calendar convention, aligned with RFC 5545 §3.8.5):
 * - RRULE present: emit DURATION, null DTEND.
 * - RRULE absent: emit DTEND, null DURATION.
 *
 * Value preference for DURATION: Event.duration column if populated (preserves
 * server-original form), else computed from exclusiveEndTs(event) - event.startTs.
 *
 * Exceptions never carry RRULE per RFC 5545 §3.8.5.1, so the exception overload
 * always emits DTEND.
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
     * Find lines starting with [prefix] inside the first VEVENT block.
     * VTIMEZONE sub-components emit DTSTART/DTEND for DST transitions, so
     * a plain top-level filter would match those too.
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
        title: String = "A0.3 Test",
        startTs: Long = 1_767_088_800_000L,   // 2025-12-30T14:00:00Z
        endTs: Long = 1_767_092_400_000L,     // 2025-12-30T15:00:00Z (1 hour after start)
        isAllDay: Boolean = false,
        timezone: String? = "UTC",
        rrule: String? = null,
        duration: String? = null,
        originalEventId: Long? = null,
        originalInstanceTime: Long? = null
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
        originalEventId = originalEventId,
        originalInstanceTime = originalInstanceTime,
        sequence = 0,
        rawIcal = null,
        dtstamp = 0L,
        createdAt = 1_579_089_600_000L,
        updatedAt = 1_718_440_200_000L,
        syncStatus = SyncStatus.SYNCED
    )

    // ========== Recurring → DURATION form ==========

    @Test
    fun `fresh path emits DURATION and no DTEND when event has RRULE`() {
        // 1-hour window + RRULE + no stored Event.duration → computed DURATION:PT1H
        val event = createEvent(
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR",
            duration = null
        )

        val ics = IcsPatcher.generateFresh(event)

        val durationLines = findLines(ics, "DURATION:")
        val dtEndLines = findLines(ics, "DTEND")
        assertEquals(
            "Recurring event must emit exactly one DURATION line; got: $durationLines",
            listOf("DURATION:PT1H"),
            durationLines
        )
        assertTrue(
            "Recurring event must not emit DTEND; got: $dtEndLines",
            dtEndLines.isEmpty()
        )
    }

    @Test
    fun `fresh path preserves server-original DURATION string when Event duration populated`() {
        // 1-hour window (computed would be PT1H) + stored Event.duration = "PT30M".
        // The 1-hour gap is load-bearing: it makes stored PT30M and computed PT1H
        // distinguishable. A broken "always compute" fix would fail with PT1H.
        val event = createEvent(
            rrule = "FREQ=DAILY",
            duration = "PT30M"
        )

        val ics = IcsPatcher.generateFresh(event)

        val durationLines = findLines(ics, "DURATION:")
        assertEquals(
            "Must preserve stored Event.duration (PT30M), not computed PT1H",
            listOf("DURATION:PT30M"),
            durationLines
        )
        assertFalse(
            "Must not emit computed PT1H when stored PT30M is preserved",
            ics.contains("DURATION:PT1H")
        )
    }

    @Test
    fun `fresh path emits DURATION P1D for single-day all-day recurring event`() {
        // Single all-day Dec 25: startTs = Dec 25 00:00 UTC, endTs = Dec 25 23:59:59.999 UTC.
        // Uses exclusiveEndTs helper (endTs + 1) → Dec 26 00:00, delta = P1D.
        // Naive endTs - startTs would give PT23H59M59.999S — regression guard.
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

        val durationLines = findLines(ics, "DURATION:")
        assertEquals(
            "All-day single-day recurring event must emit DURATION:P1D (not PT23H59M59.999S)",
            listOf("DURATION:P1D"),
            durationLines
        )
        assertTrue(findLines(ics, "DTEND").isEmpty())
    }

    @Test
    fun `fresh path emits DURATION P3D for multi-day all-day recurring event`() {
        // Feb 18-20 (3 days inclusive) all-day
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

        val durationLines = findLines(ics, "DURATION:")
        assertEquals(
            "3-day all-day recurring event must emit DURATION:P3D",
            listOf("DURATION:P3D"),
            durationLines
        )
        assertTrue(findLines(ics, "DTEND").isEmpty())
    }

    // ========== Non-recurring → DTEND form (regression guards) ==========

    @Test
    fun `fresh path emits DTEND and no DURATION when event has no RRULE`() {
        // Plain timed event, no RRULE. Today's behavior — must remain intact.
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
        // rrule=null but duration column happens to be set (rare inbound case).
        // Per Fossify/Etar convention, stored column does NOT trigger DURATION
        // without an RRULE — emit DTEND form.
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
        // Master with RRULE, exception row ALSO has stale rrule = "FREQ=DAILY"
        // (corrupt-but-possible per today's code at EventToICalEventMapper.kt:102
        // which hardcodes rrule=null on emit regardless). Exception overload must
        // emit DTEND form, not DURATION — discriminates against an over-application
        // where someone mistakenly wires isRecurring = exception.rrule != null.
        val masterUid = "bundle-master@kashcal.test"
        val master = createEvent(
            uid = masterUid,
            rrule = "FREQ=WEEKLY"
        )
        val exception = createEvent(
            uid = masterUid,
            rrule = "FREQ=DAILY",   // stale — must not trigger DURATION form
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
    fun `round-trip recurring event DURATION - parse - Event duration populated`() {
        // Build recurring event with no stored duration → computed PT1H emitted →
        // parser recovers duration → re-mapped Event.duration == "PT1H" exactly.
        val event = createEvent(
            rrule = "FREQ=WEEKLY",
            duration = null
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()
        val roundTripped = ICalEventMapper.toEntity(parsed, ics, 1L, null, null)

        assertNotNull("RRULE must survive round-trip", roundTripped.rrule)
        assertTrue(roundTripped.rrule!!.contains("FREQ=WEEKLY"))
        assertEquals(
            "Event.duration must round-trip as exact PT1H (catches format-change regressions)",
            "PT1H",
            roundTripped.duration
        )
    }

    @Test
    fun `round-trip recurring event endTs recovered correctly from DURATION`() {
        // Emits DURATION only on the wire; inbound mapper must reconstruct
        // Event.endTs via ICalEvent.effectiveEnd() = startTs + duration.toMillis().
        val event = createEvent(
            rrule = "FREQ=WEEKLY",
            duration = null
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()
        val roundTripped = ICalEventMapper.toEntity(parsed, ics, 1L, null, null)

        assertEquals(
            "Round-tripped endTs must equal original (reconstructed from DURATION)",
            event.endTs,
            roundTripped.endTs
        )
    }
}
