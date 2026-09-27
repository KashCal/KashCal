package org.onekash.icaldav.scheduling

import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.parser.ICalGenerator

/**
 * Builds iTIP messages (RFC 5546) for one event each.
 *
 * SEQUENCE per RFC 5546 §2.1.4:
 * - REQUEST ([createRequest], [createUpdate]): emitted as given. Whether a change needs an
 *   increment depends on the old and new versions, which only the caller holds, so the caller
 *   advances SEQUENCE before building the message.
 * - REPLY, COUNTER, DECLINECOUNTER: MUST NOT be incremented, so the original is kept.
 * - REFRESH: not written; the §3.2.6 table gives SEQUENCE a presence of 0.
 * - CANCEL: MUST be incremented (§2.1.4, §3.2.5).
 * - ADD: MUST be incremented and MUST be greater than 0 (§2.1.4, §3.2.4).
 *
 * Every method except [createUpdate] keeps the event's DTSTAMP when it has one.
 */
class ITipBuilder(
    private val generator: ICalGenerator = ICalGenerator()
) {
    /**
     * Builds a METHOD:REQUEST inviting [attendees], which replace the event's own, each with
     * PARTSTAT=NEEDS-ACTION and RSVP=TRUE.
     */
    fun createRequest(event: ICalEvent, attendees: List<Attendee>): String {
        val requestEvent = event.copy(
            attendees = attendees.map { attendee ->
                attendee.copy(
                    partStat = PartStat.NEEDS_ACTION,
                    rsvp = true
                )
            }
        )
        return generator.generate(requestEvent, ITipMethod.REQUEST, preserveDtstamp = true)
    }

    /**
     * Builds a METHOD:REPLY to [event] carrying only [attendee], the responder with their
     * PARTSTAT set. SEQUENCE stays that of the request (RFC 5546 §3.2.3).
     */
    fun createReply(event: ICalEvent, attendee: Attendee): String {
        val replyEvent = event.copy(
            attendees = listOf(attendee),
            sequence = event.sequence  // MUST NOT be incremented
        )
        return generator.generate(replyEvent, ITipMethod.REPLY, preserveDtstamp = true)
    }

    /**
     * Builds a METHOD:CANCEL with SEQUENCE incremented (RFC 5546 §2.1.4, §3.2.5).
     *
     * @param attendeesToCancel when null, cancels the whole event: STATUS=CANCELLED and the
     *   event's own attendees. Otherwise disinvites these attendees, which replace the event's
     *   and leave its STATUS as is.
     */
    fun createCancel(event: ICalEvent, attendeesToCancel: List<Attendee>? = null): String {
        val cancelEvent = if (attendeesToCancel != null) {
            event.copy(
                attendees = attendeesToCancel,
                sequence = event.sequence + 1  // §2.1.4: MUST increment on CANCEL
            )
        } else {
            event.copy(
                status = EventStatus.CANCELLED,
                sequence = event.sequence + 1  // §2.1.4: MUST increment on CANCEL
            )
        }
        return generator.generate(cancelEvent, ITipMethod.CANCEL, preserveDtstamp = true)
    }

    /**
     * Builds a METHOD:REQUEST for an updated [event], emitting its SEQUENCE as given and a new
     * DTSTAMP.
     *
     * RFC 5546 §2.1.4 requires an increment when the organizer changes DTSTART, DTEND,
     * DURATION, DUE, RRULE, RDATE, EXDATE or STATUS, or any property it deems affects the
     * attendees' participation status (a distant LOCATION, for example). Deciding needs the old
     * and new versions, and this builder gets only one [ICalEvent], so the caller sets SEQUENCE
     * first.
     */
    fun createUpdate(event: ICalEvent): String {
        return generator.generate(event, ITipMethod.REQUEST, preserveDtstamp = false)
    }

    /**
     * Builds a METHOD:ADD adding [newInstance] to [masterEvent]'s series (RFC 5546 §3.2.4).
     *
     * The message takes the master's UID, SEQUENCE one above the master's and at least 1, no
     * RRULE, and [attendees] with PARTSTAT=NEEDS-ACTION and RSVP=TRUE. [newInstance] must carry
     * a RECURRENCE-ID, which is emitted, though the §3.2.4 ADD table gives RECURRENCE-ID a
     * presence of 0.
     *
     * @throws IllegalArgumentException if [newInstance] has no RECURRENCE-ID
     */
    fun createAdd(
        masterEvent: ICalEvent,
        newInstance: ICalEvent,
        attendees: List<Attendee>
    ): String {
        require(newInstance.recurrenceId != null) {
            "ADD method requires RECURRENCE-ID to identify the new instance"
        }

        val addEvent = newInstance.copy(
            uid = masterEvent.uid,
            // §2.1.4: MUST increment on ADD; §3.2.4: result MUST be > 0.
            sequence = maxOf(masterEvent.sequence + 1, 1),
            recurrenceId = newInstance.recurrenceId,
            rrule = null,
            attendees = attendees.map { attendee ->
                attendee.copy(
                    partStat = PartStat.NEEDS_ACTION,
                    rsvp = true
                )
            }
        )
        return generator.generate(addEvent, ITipMethod.ADD, preserveDtstamp = true)
    }

    /**
     * Builds a METHOD:COUNTER from [attendee] proposing [proposedStart] to [proposedEnd] for
     * [originalEvent]. Only [attendee] is included, and SEQUENCE stays the original's (RFC 5546
     * §3.2.7).
     */
    fun createCounter(
        originalEvent: ICalEvent,
        attendee: Attendee,
        proposedStart: ICalDateTime,
        proposedEnd: ICalDateTime
    ): String {
        val counterEvent = originalEvent.copy(
            dtStart = proposedStart,
            dtEnd = proposedEnd,
            attendees = listOf(attendee),
            sequence = originalEvent.sequence  // MUST NOT be incremented
        )
        return generator.generate(counterEvent, ITipMethod.COUNTER, preserveDtstamp = true)
    }

    /**
     * Builds the organizer's METHOD:DECLINECOUNTER rejecting [attendee]'s COUNTER (RFC 5546
     * §3.2.8). Pass the original event, not the proposed version; only [attendee] is included
     * and SEQUENCE stays the original's.
     */
    fun createDeclineCounter(originalEvent: ICalEvent, attendee: Attendee): String {
        val declineCounterEvent = originalEvent.copy(
            attendees = listOf(attendee),
            sequence = originalEvent.sequence
        )
        return generator.generate(declineCounterEvent, ITipMethod.DECLINECOUNTER, preserveDtstamp = true)
    }

    /**
     * Builds [attendee]'s METHOD:REFRESH asking the organizer for the current version of
     * [event] (RFC 5546 §3.2.6). [ICalGenerator] writes only the properties REFRESH allows, so
     * [event] can be minimal.
     */
    fun createRefresh(event: ICalEvent, attendee: Attendee): String {
        val refreshEvent = event.copy(
            attendees = listOf(attendee)
        )
        return generator.generate(refreshEvent, ITipMethod.REFRESH, preserveDtstamp = true)
    }

    companion object {
        /** Shared instance over a default [ICalGenerator]. */
        val default = ITipBuilder()
    }

    // =====================================================================
    // RECURRING EVENT HANDLING (RFC 5546 Section 3.2)
    // =====================================================================
    // 1. One occurrence: set RECURRENCE-ID on the event; the message applies only to that
    //    occurrence, for example event.copy(recurrenceId = instanceDateTime).
    //
    // 2. This and future (RANGE=THISANDFUTURE on RECURRENCE-ID): ICalEvent has no RANGE
    //    parameter, so these builders can't emit one.
    //
    // 3. Cancelling one occurrence: a CANCEL with that occurrence's RECURRENCE-ID, or an
    //    EXDATE on the master.
    //
    // Example for one occurrence:
    // ```kotlin
    // fun createRequestForInstance(
    //     masterEvent: ICalEvent,
    //     instanceId: ICalDateTime,
    //     attendees: List<Attendee>
    // ): String {
    //     val instanceEvent = masterEvent.copy(
    //         recurrenceId = instanceId,
    //         rrule = null,
    //         attendees = attendees.map { it.copy(partStat = PartStat.NEEDS_ACTION, rsvp = true) }
    //     )
    //     return generator.generate(instanceEvent, ITipMethod.REQUEST, preserveDtstamp = true)
    // }
    // ```
    // =====================================================================
}
