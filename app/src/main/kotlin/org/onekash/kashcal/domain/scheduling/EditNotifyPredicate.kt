package org.onekash.kashcal.domain.scheduling

import org.onekash.kashcal.data.db.entity.Event

/**
 * Whether saving an edit should notify the event's attendees — the trigger for
 * the inline "Save & notify" banner and the relabeled save action.
 *
 * Delegates the "is this change scheduling-significant?" decision to
 * [SequenceBumper] (DTSTART/DTEND/DURATION/RRULE/RDATE/EXDATE and a transition
 * to CANCELLED), so the banner copy matches the wire behaviour exactly and
 * there is no second, drift-prone field list.
 *
 * Notifies on the union of three cases: a scheduling-significant change (per
 * [SequenceBumper]), an addition to the attendee set, OR a removal from it.
 * Adding a guest sends them an invitation (RFC 5546 §3.2.2.2 — an unsolicited
 * REQUEST/update); removing a guest sends them a CANCEL (§3.2.2.6). Both are
 * outbound side effects the organizer should see coming, but ATTENDEE is
 * deliberately NOT in the §2.1.4 SEQUENCE-bump set for an add, so this predicate
 * drives only the banner/relabel — never SEQUENCE — keeping the two decoupled.
 *
 * The [attendeeCount] > 0 gate applies to the SCHEDULING-change and ADD cases
 * (there must be someone left to notify), but NOT to a REMOVAL: removing the
 * last guest leaves zero attendees yet the dropped guest still gets a CANCEL,
 * so a removal notifies even when the resulting set is empty.
 *
 * @param old the event as loaded, or null for a new event (never notifies).
 * @param new the candidate event built from the current form state.
 * @param attendeeCount the number of attendees that will be saved.
 * @param attendeeSetChanged whether the user added to the attendee set this
 *   session. Independent of the scheduling-change check.
 * @param attendeeRemoved whether the user removed a previously-invited guest
 *   this session. Notifies regardless of [attendeeCount] (the dropped guest is
 *   cancelled even if none remain).
 */
fun shouldNotifyAttendees(
    old: Event?,
    new: Event,
    attendeeCount: Int,
    attendeeSetChanged: Boolean = false,
    attendeeRemoved: Boolean = false,
): Boolean {
    if (old == null) return false
    // A removal cancels the dropped guest even if no attendees remain.
    if (attendeeRemoved) return true
    if (attendeeCount <= 0) return false
    return SequenceBumper.shouldBump(old, new) || attendeeSetChanged
}
