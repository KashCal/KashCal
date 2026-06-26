package org.onekash.icaldav.scheduling

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.parser.ICalParser
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract gate for the wire bytes [ITipBuilder] hands to a CalDAV
 * schedule-outbox POST (RFC 6638 §6 / the explicit client-delivery channel).
 *
 * This is the offline, server-free half of the iTIP delivery oracle: it pins
 * the structural contract a real server outbox enforces, so a regression in the
 * builder or generator is caught here without a live account. The live half —
 * POSTing these bytes to a real outbox and asserting `request-status 2.0` — is a
 * separate, credential-gated integration test; running both is how the
 * end-to-end delivery path is verified.
 *
 * REQUEST is the invite-delivery payload; CANCEL is the cancellation payload.
 * Both travel the same outbox channel, so both contracts are pinned here.
 */
@DisplayName("iTIP outbox payload contract")
class ITipOutboxPayloadContractTest {
    private val builder = ITipBuilder()
    private val parser = ICalParser()

    private fun meetingEvent(sequence: Int = 0): ICalEvent = ICalEvent(
        uid = "outbox-contract-uid@example.com",
        importId = "outbox-contract-uid@example.com",
        summary = "Quarterly planning",
        description = null,
        location = null,
        dtStart = ICalDateTime.parse("20260615T140000Z"),
        dtEnd = ICalDateTime.parse("20260615T150000Z"),
        duration = null,
        isAllDay = false,
        status = EventStatus.CONFIRMED,
        sequence = sequence,
        rrule = null,
        exdates = emptyList(),
        recurrenceId = null,
        alarms = emptyList(),
        categories = emptyList(),
        organizer = Organizer(
            email = "organizer@example.com",
            name = "Org Anizer",
            sentBy = null
        ),
        attendees = listOf(
            Attendee(
                email = "invitee@example.test",
                name = "Invitee",
                partStat = PartStat.NEEDS_ACTION,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = true
            )
        ),
        color = null,
        dtstamp = ICalDateTime.parse("20260601T120000Z"),
        lastModified = null,
        created = null,
        transparency = Transparency.OPAQUE,
        url = null,
        rawProperties = emptyMap()
    )

    private val invitee = meetingEvent().attendees

    /**
     * RFC 5546 §3.2.2: a METHOD:REQUEST of a VEVENT requires ORGANIZER,
     * ATTENDEE, DTSTAMP, DTSTART, SUMMARY, and UID, and forbids REQUEST-STATUS.
     * RFC 5545 §3.1.1: VCALENDAR carries VERSION (MUST be 2.0) and PRODID.
     * A server outbox rejects a payload missing any of these.
     */
    @Test
    fun `REQUEST carries the complete RFC 5546 section 3_2_2 outbox property set`() {
        val ics = builder.createRequest(meetingEvent(), invitee)

        assertTrue(ics.contains("BEGIN:VCALENDAR"), "must be a VCALENDAR")
        assertTrue(ics.contains("VERSION:2.0"), "RFC 5545 §3.1.1: VERSION MUST be 2.0")
        assertTrue(ics.contains("PRODID:"), "RFC 5545 §3.1.1: PRODID required")
        assertTrue(ics.contains("METHOD:REQUEST"), "outbox invite uses METHOD:REQUEST")
        assertTrue(ics.contains("BEGIN:VEVENT"), "must contain a VEVENT")

        assertTrue(ics.contains("UID:outbox-contract-uid@example.com"), "REQUEST requires UID")
        assertTrue(ics.contains("DTSTAMP:"), "REQUEST requires DTSTAMP")
        assertTrue(ics.contains("DTSTART"), "REQUEST requires DTSTART")
        assertTrue(ics.contains("SUMMARY:Quarterly planning"), "REQUEST requires SUMMARY")
        assertTrue(ics.contains("ORGANIZER") && ics.contains("mailto:organizer@example.com"), "REQUEST requires ORGANIZER as a mailto: address")
        assertTrue(ics.contains("ATTENDEE") && ics.contains("mailto:invitee@example.test"), "REQUEST requires ATTENDEE as a mailto: address")

        assertTrue(!ics.contains("REQUEST-STATUS"), "RFC 5546 §3.2.2: REQUEST-STATUS not permitted on a REQUEST")
    }

    /**
     * The outbox marks invitees as awaiting a response. RFC 5546 §3.2.2.1:
     * the organizer sets PARTSTAT=NEEDS-ACTION and requests RSVP=TRUE.
     */
    @Test
    fun `REQUEST marks the invitee NEEDS-ACTION and requests an RSVP`() {
        val ics = builder.createRequest(meetingEvent(), invitee)

        assertTrue(ics.contains("PARTSTAT=NEEDS-ACTION"), "invitee must be NEEDS-ACTION")
        assertTrue(ics.contains("RSVP=TRUE"), "organizer requests a reply")
    }

    /**
     * The bytes must survive a round-trip through the parser the way a recipient
     * server will read them: a well-formed REQUEST with the organizer and the
     * invitee intact.
     */
    @Test
    fun `REQUEST round-trips through the parser as METHOD REQUEST`() {
        val ics = builder.createRequest(meetingEvent(), invitee)

        val parsed = parser.parseWithMethod(ics)
        assertTrue(parsed is ParseResult.Success, "outbox REQUEST must parse")
        val result = parsed.getOrNull()!!
        assertEquals(ITipMethod.REQUEST, result.method, "parsed METHOD must be REQUEST")
        assertEquals(1, result.events.size, "exactly one VEVENT")
        val event = result.events[0]
        assertEquals("outbox-contract-uid@example.com", event.uid)
        assertEquals("organizer@example.com", event.organizer?.email, "ORGANIZER must round-trip")
        assertEquals(1, event.attendees.size, "the invitee must round-trip")
    }

    /**
     * The cancellation payload that travels the same outbox channel. RFC 5546
     * §3.2.5 + §2.1.4: a CANCEL sets STATUS=CANCELLED for the whole event and
     * MUST carry an incremented SEQUENCE. (This assertion would fail against the
     * pre-conformance builder, which emitted the un-incremented SEQUENCE.)
     */
    @Test
    fun `CANCEL outbox payload sets STATUS CANCELLED and an incremented SEQUENCE`() {
        val ics = builder.createCancel(meetingEvent(sequence = 2))

        assertTrue(ics.contains("METHOD:CANCEL"), "outbox cancellation uses METHOD:CANCEL")
        assertTrue(ics.contains("STATUS:CANCELLED"), "RFC 5546 §3.2.5: whole-event cancel sets STATUS=CANCELLED")
        assertTrue(ics.contains("SEQUENCE:3"), "RFC 5546 §2.1.4: CANCEL MUST increment SEQUENCE (2 -> 3)")

        val parsed = parser.parseWithMethod(ics)
        assertTrue(parsed is ParseResult.Success, "outbox CANCEL must parse")
        assertEquals(ITipMethod.CANCEL, parsed.getOrNull()!!.method)
    }

    /**
     * RFC 5545 §3.1: content lines are CRLF-delimited. A schedule-outbox POST
     * sends the body verbatim, so a bare-LF payload risks rejection by a strict
     * server. Every line break the builder emits must be CRLF.
     */
    @Test
    fun `outbox payload uses CRLF line endings`() {
        val ics = builder.createRequest(meetingEvent(), invitee)

        assertTrue(ics.contains("\r\n"), "lines must be CRLF-delimited")
        val withoutCrlf = ics.replace("\r\n", "")
        assertTrue(!withoutCrlf.contains("\n"), "no bare LF outside a CRLF pair")
        assertTrue(!withoutCrlf.contains("\r"), "no bare CR outside a CRLF pair")
    }
}
