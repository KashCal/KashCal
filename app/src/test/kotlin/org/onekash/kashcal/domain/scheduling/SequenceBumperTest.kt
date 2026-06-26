package org.onekash.kashcal.domain.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event

/**
 * Unit tests for [SequenceBumper] — the single source of truth for when an
 * organizer edit must bump the iCalendar SEQUENCE (RFC 5546 §2.1.4).
 *
 * Significant properties (MUST bump): DTSTART, DTEND, DURATION, RRULE, RDATE,
 * EXDATE, and a transition of STATUS to CANCELLED. Everything else
 * (SUMMARY/title, DESCRIPTION, LOCATION, CATEGORIES, COLOR) must NOT bump —
 * those changes don't invalidate an attendee's prior acceptance, so re-sending
 * them as a higher SEQUENCE spuriously re-notifies attendees.
 */
class SequenceBumperTest {

    private val now = 1_700_000_000_000L

    private fun baseEvent(): Event = Event(
        uid = "seq-bumper@example.test",
        calendarId = 1L,
        title = "Standup",
        startTs = now,
        endTs = now + 3_600_000,
        dtstamp = now,
        sequence = 4,
    )

    // ---- Significant (scheduling) properties: MUST bump ----

    @Test
    fun `DTSTART change bumps`() {
        val old = baseEvent()
        val new = old.copy(startTs = old.startTs + 3_600_000)
        assertTrue(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `DTEND change bumps`() {
        val old = baseEvent()
        val new = old.copy(endTs = old.endTs + 3_600_000)
        assertTrue(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `isAllDay change bumps`() {
        val old = baseEvent()
        val new = old.copy(isAllDay = !old.isAllDay)
        assertTrue(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `DURATION change bumps`() {
        val old = baseEvent().copy(duration = "PT1H")
        val new = old.copy(duration = "PT2H")
        assertTrue(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `RRULE change bumps`() {
        val old = baseEvent()
        val new = old.copy(rrule = "FREQ=WEEKLY")
        assertTrue(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `cosmetically reordered RRULE does not bump`() {
        // The recurrence picker can re-emit the same rule with parts in
        // a different order. That is not a scheduling change, so it must
        // not bump SEQUENCE and spuriously re-notify attendees.
        val old = baseEvent().copy(rrule = "FREQ=WEEKLY;BYDAY=MO,WE")
        val new = old.copy(rrule = "BYDAY=WE,MO;FREQ=WEEKLY")
        assertFalse(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `cosmetic RRULE rewrite (case, whitespace, trailing separator) does not bump`() {
        val old = baseEvent().copy(rrule = "FREQ=WEEKLY;COUNT=10")
        val new = old.copy(rrule = " freq=WEEKLY; COUNT=10; ")
        assertFalse(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `genuine FREQ change still bumps`() {
        val old = baseEvent().copy(rrule = "FREQ=WEEKLY;COUNT=10")
        val new = old.copy(rrule = "FREQ=DAILY;COUNT=10")
        assertTrue(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `genuine UNTIL value change still bumps`() {
        val old = baseEvent().copy(rrule = "FREQ=WEEKLY;UNTIL=20271231T000000Z")
        val new = old.copy(rrule = "FREQ=WEEKLY;UNTIL=20261231T000000Z")
        assertTrue(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `ordinal BYDAY change still bumps`() {
        // "first Sunday" vs "last Sunday" is a real cadence change. The
        // semantic compare sorts BYxxx set members but must not collapse
        // the ordinal prefix, so this must still bump.
        val old = baseEvent().copy(rrule = "FREQ=MONTHLY;BYDAY=1SU")
        val new = old.copy(rrule = "FREQ=MONTHLY;BYDAY=-1SU")
        assertTrue(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `RDATE change bumps`() {
        val old = baseEvent()
        val new = old.copy(rdate = "${now + 86_400_000}")
        assertTrue(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `EXDATE change bumps`() {
        val old = baseEvent().copy(rrule = "FREQ=DAILY")
        val new = old.copy(exdate = "${now + 86_400_000}")
        assertTrue(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `STATUS transition to CANCELLED bumps`() {
        val old = baseEvent().copy(status = "CONFIRMED")
        val new = old.copy(status = "CANCELLED")
        assertTrue(SequenceBumper.shouldBump(old, new))
    }

    // ---- Non-significant properties: MUST NOT bump ----

    @Test
    fun `title change does not bump`() {
        val old = baseEvent()
        val new = old.copy(title = "Standup (moved room)")
        assertFalse(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `description change does not bump`() {
        val old = baseEvent()
        val new = old.copy(description = "Bring the deck")
        assertFalse(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `location change does not bump`() {
        val old = baseEvent()
        val new = old.copy(location = "Room B")
        assertFalse(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `categories change does not bump`() {
        val old = baseEvent()
        val new = old.copy(categories = listOf("work"))
        assertFalse(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `color change does not bump`() {
        val old = baseEvent()
        val new = old.copy(color = 0xFF112233.toInt())
        assertFalse(SequenceBumper.shouldBump(old, new))
    }

    @Test
    fun `no change does not bump`() {
        val old = baseEvent()
        assertFalse(SequenceBumper.shouldBump(old, old.copy()))
    }

    @Test
    fun `un-cancel does not bump in T3 scope`() {
        // Revival (CANCELLED -> CONFIRMED) is a rare flow; T3 only bumps on
        // the transition TO cancelled. Documented scope decision.
        val old = baseEvent().copy(status = "CANCELLED")
        val new = old.copy(status = "CONFIRMED")
        assertFalse(SequenceBumper.shouldBump(old, new))
    }

    // ---- nextSequence ----

    @Test
    fun `nextSequence increments on significant change`() {
        val old = baseEvent()
        val new = old.copy(startTs = old.startTs + 3_600_000)
        assertEquals(5, SequenceBumper.nextSequence(old, new))
    }

    @Test
    fun `nextSequence preserves on non-significant change`() {
        val old = baseEvent()
        val new = old.copy(title = "Renamed")
        assertEquals(4, SequenceBumper.nextSequence(old, new))
    }

    @Test
    fun `nextSequence uses new event's stored sequence as the base`() {
        // The bump is relative to the new event's sequence, not the old row's,
        // so a caller that already carried a sequence forward isn't clobbered.
        val old = baseEvent().copy(sequence = 4)
        val new = old.copy(sequence = 9, startTs = old.startTs + 3_600_000)
        assertEquals(10, SequenceBumper.nextSequence(old, new))
    }
}
