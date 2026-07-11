package org.onekash.kashcal.domain.scheduling

import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.util.RruleUtils

/**
 * Decides when an organizer's edit must bump the iCalendar SEQUENCE.
 *
 * The single source of truth for this decision. When the organizer changes a
 * scheduling-significant property, attendees' calendars must treat the event
 * as a new revision; SEQUENCE is the monotonic counter that tells them so. A
 * change to a purely cosmetic property (notes, categories, colour) does NOT
 * invalidate a prior acceptance, so bumping SEQUENCE for it would make
 * attendee clients re-surface the invitation and re-notify for nothing.
 *
 * Significant properties (bump): DTSTART, DTEND, DURATION, RRULE, RDATE,
 * EXDATE, a transition of STATUS to CANCELLED, and the attendee-facing SUMMARY
 * (title) and LOCATION. RFC 5546 §2.1.4 names LOCATION as an example of a
 * change that can jeopardize an attendee's participation status; a renamed
 * meeting is likewise attendee-facing, so both re-notify. Title and location
 * are compared trimmed so a no-op re-save that only changes whitespace (or
 * null-vs-blank location) does not spuriously re-notify.
 *
 * STATUS scope: only the transition TO CANCELLED bumps. Un-cancelling
 * (CANCELLED back to CONFIRMED) and other STATUS transitions are rare flows
 * not handled here; they don't bump. This is a deliberate scope choice, not an
 * oversight.
 *
 * KashCal events always carry an explicit start/end and timezone, and the
 * `Event` entity has no DUE field (DUE is a VTODO property), so the property
 * set below is the applicable subset of RFC 5546 §2.1.4 for VEVENTs.
 */
object SequenceBumper {

    private const val STATUS_CANCELLED = "CANCELLED"

    /**
     * True when the change from [old] to [new] is scheduling-significant and
     * therefore requires a SEQUENCE bump.
     */
    fun shouldBump(old: Event, new: Event): Boolean {
        val timingChanged = old.startTs != new.startTs ||
            old.endTs != new.endTs ||
            old.isAllDay != new.isAllDay ||
            old.duration != new.duration
        // Compare the RRULE by meaning, not bytes: a picker that re-emits
        // the same rule with reordered parts, different case, or extra
        // whitespace is not a scheduling change, and bumping SEQUENCE for
        // it would spuriously re-notify every attendee. RDATE/EXDATE stay
        // on exact comparison — they are timestamp lists, not RRULEs, and
        // any real add/remove always changes the string.
        val recurrenceChanged = !RruleUtils.rrulesEquivalent(old.rrule, new.rrule) ||
            old.rdate != new.rdate ||
            old.exdate != new.exdate
        val cancelled = old.status != STATUS_CANCELLED && new.status == STATUS_CANCELLED
        val titleChanged = old.title.trim() != new.title.trim()
        val locationChanged = old.location.orEmpty().trim() != new.location.orEmpty().trim()
        return timingChanged || recurrenceChanged || cancelled ||
            titleChanged || locationChanged
    }

    /**
     * The SEQUENCE to persist for [new]: [new].sequence + 1 when the edit is
     * significant, otherwise [new].sequence unchanged. Relative to the new
     * event's own sequence so a caller that already advanced it isn't
     * clobbered.
     */
    fun nextSequence(old: Event, new: Event): Int =
        if (shouldBump(old, new)) new.sequence + 1 else new.sequence
}
