package org.onekash.kashcal.domain.scheduling

import org.onekash.kashcal.data.db.entity.Event

/**
 * Returns whether saving an edit notifies the event's attendees, which drives the inline "Save &
 * notify" banner and the relabeled save action.
 *
 * Notifies on any of three cases:
 * - a scheduling-significant change, decided by [SequenceBumper.shouldBump] so the banner matches
 *   the wire behavior with no second field list;
 * - a change to the attendee set: an added guest gets an invitation (RFC 5546 §3.2.2.2);
 * - a removed guest, who gets a CANCEL (§3.2.2.6).
 *
 * ATTENDEE is deliberately not in the §2.1.4 SEQUENCE-bump set for an add, so this predicate
 * drives only the banner and relabel, never SEQUENCE.
 *
 * The first two cases need [attendeeCount] > 0 (someone left to notify). A removal doesn't:
 * removing the last guest leaves zero attendees, yet the dropped guest still gets a CANCEL.
 *
 * @param old the event as loaded, or null for a new event (never notifies).
 * @param new the candidate event built from the current form state.
 * @param attendeeCount the number of attendees that will be saved.
 * @param attendeeSetChanged whether the user changed the attendee set this session.
 * @param attendeeRemoved whether the user removed a previously invited guest this session.
 */
fun shouldNotifyAttendees(
    old: Event?,
    new: Event,
    attendeeCount: Int,
    attendeeSetChanged: Boolean = false,
    attendeeRemoved: Boolean = false,
): Boolean {
    if (old == null) return false
    if (attendeeRemoved) return true
    if (attendeeCount <= 0) return false
    return SequenceBumper.shouldBump(old, new) || attendeeSetChanged
}
