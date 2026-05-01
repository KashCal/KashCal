package org.onekash.icaldav.scheduling

import org.onekash.icaldav.model.*
import org.onekash.icaldav.parser.ICalGenerator

/**
 * Builder for iTIP messages (RFC 5546).
 * Provides convenience methods for common scheduling operations.
 *
 * SEQUENCE Handling per RFC 5546:
 * - REQUEST: Increment SEQUENCE only for significant changes (time, recurrence)
 * - REPLY: MUST preserve original SEQUENCE from REQUEST
 * - CANCEL: Uses same SEQUENCE as the event being cancelled
 * - COUNTER: MUST preserve original SEQUENCE from REQUEST
 *
 * @param generator ICalGenerator instance for generating ICS content
 */
class ITipBuilder(
    private val generator: ICalGenerator = ICalGenerator()
) {
    /**
     * Create a REQUEST message to invite attendees.
     * Sets PARTSTAT=NEEDS-ACTION and RSVP=TRUE for attendees.
     *
     * @param event The event to send as a meeting request
     * @param attendees Attendees to invite
     * @return ICS string with METHOD:REQUEST
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
     * Create a REPLY message responding to an invitation.
     *
     * Per RFC 5546:
     * - REPLY includes ONLY the responding attendee
     * - SEQUENCE MUST match the original REQUEST
     * - DTSTAMP should be preserved from original
     *
     * @param event The original event being responded to
     * @param attendee The responding attendee with their PARTSTAT set
     * @return ICS string with METHOD:REPLY
     */
    fun createReply(event: ICalEvent, attendee: Attendee): String {
        val replyEvent = event.copy(
            attendees = listOf(attendee),
            sequence = event.sequence  // MUST preserve original SEQUENCE
        )
        return generator.generate(replyEvent, ITipMethod.REPLY, preserveDtstamp = true)
    }

    /**
     * Create a CANCEL message to cancel an event or disinvite attendees.
     *
     * Per RFC 5546:
     * - To cancel entire event: set STATUS=CANCELLED
     * - To remove specific attendees: include only those attendees
     * - SEQUENCE matches the event being cancelled
     *
     * @param event The event to cancel
     * @param attendeesToCancel If null, cancels entire event; if specified, disinvites those attendees
     * @return ICS string with METHOD:CANCEL
     */
    fun createCancel(event: ICalEvent, attendeesToCancel: List<Attendee>? = null): String {
        val cancelEvent = if (attendeesToCancel != null) {
            event.copy(
                attendees = attendeesToCancel,
                sequence = event.sequence  // Preserve SEQUENCE
            )
        } else {
            event.copy(
                status = EventStatus.CANCELLED,
                sequence = event.sequence  // Preserve SEQUENCE
            )
        }
        return generator.generate(cancelEvent, ITipMethod.CANCEL, preserveDtstamp = true)
    }

    /**
     * Create an updated REQUEST with incremented SEQUENCE.
     *
     * Per RFC 5546, SEQUENCE should increment for significant changes:
     * - DTSTART, DTEND, DURATION changes
     * - RRULE, RDATE, EXDATE changes
     * - Status changes (except CANCELLED which uses CANCEL method)
     *
     * DTSTAMP is regenerated for updates (preserveDtstamp = false).
     *
     * @param event The updated event (SEQUENCE will be incremented)
     * @return ICS string with METHOD:REQUEST and incremented SEQUENCE
     */
    fun createUpdate(event: ICalEvent): String {
        val updatedEvent = event.copy(
            sequence = event.sequence + 1  // Increment for update
        )
        return generator.generate(updatedEvent, ITipMethod.REQUEST, preserveDtstamp = false)
    }

    /**
     * Create an ADD message to add new instances to a recurring event.
     *
     * Per RFC 5546 Section 3.2.4:
     * - ADD is used to add instances to a recurring event
     * - The RECURRENCE-ID identifies the new instance(s) being added
     * - SEQUENCE should match the master event
     * - The new instance must have RECURRENCE-ID set
     *
     * @param masterEvent The master recurring event
     * @param newInstance The new instance to add (must have RECURRENCE-ID set)
     * @param attendees Attendees for the new instance
     * @return ICS string with METHOD:ADD
     * @throws IllegalArgumentException if newInstance.recurrenceId is null
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
            uid = masterEvent.uid,                // Preserve master UID
            sequence = masterEvent.sequence,      // Preserve master SEQUENCE
            recurrenceId = newInstance.recurrenceId,  // Required for ADD
            rrule = null,                         // Instance should not have RRULE
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
     * Create a COUNTER message proposing alternative time.
     *
     * Per RFC 5546:
     * - COUNTER includes the proposed changes
     * - SEQUENCE MUST match the original REQUEST
     * - Only the counter-proposing attendee is included
     *
     * @param originalEvent The original event being counter-proposed
     * @param attendee The attendee making the counter-proposal
     * @param proposedStart Proposed alternative start time
     * @param proposedEnd Proposed alternative end time
     * @return ICS string with METHOD:COUNTER
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
            sequence = originalEvent.sequence  // MUST preserve original SEQUENCE
        )
        return generator.generate(counterEvent, ITipMethod.COUNTER, preserveDtstamp = true)
    }

    /**
     * Create a DECLINECOUNTER message to decline a counter-proposal.
     *
     * Per RFC 5546:
     * - DECLINECOUNTER is sent by the organizer to reject a COUNTER
     * - The event data matches the original event (not the proposed changes)
     * - Only the attendee who sent the COUNTER is included
     *
     * @param originalEvent The original event (not the counter-proposed version)
     * @param attendee The attendee whose counter-proposal is being declined
     * @return ICS string with METHOD:DECLINECOUNTER
     */
    fun createDeclineCounter(originalEvent: ICalEvent, attendee: Attendee): String {
        val declineCounterEvent = originalEvent.copy(
            attendees = listOf(attendee),
            sequence = originalEvent.sequence
        )
        return generator.generate(declineCounterEvent, ITipMethod.DECLINECOUNTER, preserveDtstamp = true)
    }

    /**
     * Create a REFRESH message to request the latest event version.
     *
     * Per RFC 5546:
     * - REFRESH is sent by an attendee to the organizer
     * - Requests the current version of the calendar object
     *
     * @param event The event to refresh (can have minimal properties)
     * @param attendee The attendee requesting the refresh
     * @return ICS string with METHOD:REFRESH
     */
    fun createRefresh(event: ICalEvent, attendee: Attendee): String {
        val refreshEvent = event.copy(
            attendees = listOf(attendee)
        )
        return generator.generate(refreshEvent, ITipMethod.REFRESH, preserveDtstamp = true)
    }

    companion object {
        /** Default instance for convenience when default generator is sufficient */
        val default = ITipBuilder()
    }

    // =====================================================================
    // RECURRING EVENT HANDLING (RFC 5546 Section 3.2)
    // =====================================================================
    // For scheduling recurring events, the following considerations apply:
    //
    // 1. Modifying a single instance:
    //    - Use RECURRENCE-ID to identify the specific instance
    //    - The iTIP message applies only to that instance
    //    - Example: event.copy(recurrenceId = instanceDateTime)
    //
    // 2. Modifying all future instances (RANGE=THISANDFUTURE):
    //    - Include RANGE=THISANDFUTURE on RECURRENCE-ID
    //    - Changes apply from specified instance forward
    //    - Note: RANGE parameter requires ICalEvent enhancement (future work)
    //
    // 3. Cancelling a single instance:
    //    - Send CANCEL with RECURRENCE-ID for that instance
    //    - Alternatively, add EXDATE to master event
    //
    // Example for single instance modification:
    // ```kotlin
    // fun createRequestForInstance(
    //     masterEvent: ICalEvent,
    //     instanceId: ICalDateTime,
    //     attendees: List<Attendee>
    // ): String {
    //     val instanceEvent = masterEvent.copy(
    //         recurrenceId = instanceId,
    //         rrule = null, // Remove RRULE for instance
    //         attendees = attendees.map { it.copy(partStat = PartStat.NEEDS_ACTION, rsvp = true) }
    //     )
    //     return generator.generate(instanceEvent, ITipMethod.REQUEST, preserveDtstamp = true)
    // }
    // ```
    // =====================================================================
}
