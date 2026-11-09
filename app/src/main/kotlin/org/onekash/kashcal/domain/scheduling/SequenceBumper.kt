package org.onekash.kashcal.domain.scheduling

import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.util.RruleUtils

/**
 * Decides when an organizer's edit bumps the iCalendar SEQUENCE, and when a change reschedules
 * (resetting an attendee's answer); the source of truth for both.
 *
 * A scheduling-significant change makes attendees' calendars treat the event as a new revision,
 * and SEQUENCE is the monotonic counter that tells them so. A cosmetic change (notes, categories,
 * color) doesn't invalidate a prior acceptance, so bumping for it would make attendee clients
 * re-surface the invitation and re-notify for nothing.
 *
 * Significant (bump): the [reschedules] fields, a transition of STATUS to CANCELLED, and the
 * attendee-facing SUMMARY and LOCATION. RFC 5546 §2.1.4 names LOCATION as a change that can
 * jeopardize an attendee's participation status; a renamed meeting is likewise attendee-facing.
 * Title and location compare trimmed, and a null location equals a blank one, so a
 * whitespace-only re-save doesn't re-notify.
 *
 * Only the transition to CANCELLED bumps. Un-cancelling and other STATUS transitions don't; this
 * is a deliberate scope choice.
 *
 * KashCal events always carry an explicit start, end and timezone, and `Event` has no DUE field
 * (a VTODO property), so this is the applicable subset of RFC 5546 §2.1.4 for VEVENTs.
 */
object SequenceBumper {

    private const val STATUS_CANCELLED = "CANCELLED"

    /** Returns true when the change from [old] to [new] is scheduling-significant. */
    fun shouldBump(old: Event, new: Event): Boolean {
        val cancelled = old.status != STATUS_CANCELLED && new.status == STATUS_CANCELLED
        val titleChanged = old.title.trim() != new.title.trim()
        val locationChanged = old.location.orEmpty().trim() != new.location.orEmpty().trim()
        return reschedules(old, new) || cancelled || titleChanged || locationChanged
    }

    /**
     * Returns true when the change from [old] to [new] moves, adds or removes an occurrence:
     * DTSTART, DTEND, DURATION, the all-day flag, RRULE, RDATE or EXDATE (the reschedule of RFC
     * 6638 §3.2.8, which resets an attendee's PARTSTAT).
     */
    fun reschedules(old: Event, new: Event): Boolean {
        val timingChanged = old.startTs != new.startTs ||
            old.endTs != new.endTs ||
            old.isAllDay != new.isAllDay ||
            old.duration != new.duration
        // Compare the RRULE by meaning: a picker that re-emits the same rule with reordered
        // parts, other case or extra whitespace isn't a change, and a bump would re-notify every
        // attendee. RDATE and EXDATE compare exactly: they are timestamp lists, and any real add
        // or remove changes the string.
        val recurrenceChanged = !RruleUtils.rrulesEquivalent(old.rrule, new.rrule) ||
            old.rdate != new.rdate ||
            old.exdate != new.exdate
        return timingChanged || recurrenceChanged
    }

    /**
     * Returns the SEQUENCE to persist for [new]: its own sequence + 1 when the edit is
     * significant, else unchanged. Relative to [new] so a caller that already advanced it isn't
     * clobbered.
     */
    fun nextSequence(old: Event, new: Event): Int =
        if (shouldBump(old, new)) new.sequence + 1 else new.sequence
}
