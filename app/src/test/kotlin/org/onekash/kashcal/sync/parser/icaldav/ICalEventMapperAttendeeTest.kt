package org.onekash.kashcal.sync.parser.icaldav

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [ICalEventMapper.toEntity]'s ATTENDEE translation layer.
 *
 * Each test parses a VEVENT through the icaldav-core parser, runs it
 * through `toEntity`, and asserts the resulting Room [Attendee] has the
 * correct field shape. This locks the icaldav-core → Room translation
 * contract:
 *
 * - `email` → `address` with `mailto:` prefix re-prepended
 * - `partStat` enum → TEXT via `toICalString()` (NEEDS_ACTION → NEEDS-ACTION)
 * - `role` enum → TEXT via `toICalString()` (REQ_PARTICIPANT → REQ-PARTICIPANT)
 * - `cutype` enum → `.name` (INDIVIDUAL passes through)
 * - `rsvp: Boolean?` → passthrough (nullable, three-state semantics)
 * - `member: List<String>` → passthrough (list)
 * - `delegatedFrom`/`delegatedTo`: List<String> → passthrough
 * - `scheduleAgent`/`scheduleForceSend` enums → `.name`
 * - `scheduleStatus: List<ScheduleStatus>?` → first.code → TEXT
 * - `sortOrder` → wire-order index from icalEvent.attendees
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ICalEventMapperAttendeeTest {

    private val parser = ICalParser()

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun parseAndMap(attendeeLines: List<String>): List<org.onekash.kashcal.data.db.entity.Attendee> {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:mapper-test-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Test Event
            ${attendeeLines.joinToString("\n            ")}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val result = parser.parse(ical)
        assertTrue("Parse failed", result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.events.single()
        return ICalEventMapper.toEntity(
            icalEvent = event,
            rawIcal = ical,
            calendarId = 1L,
            caldavUrl = null,
            etag = null
        ).attendees
    }

    @Test
    fun `single attendee with all RFC 5545 params translates every field`() {
        val attendees = parseAndMap(
            listOf(
                """ATTENDEE;CN=Alice Test;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT;""" +
                    """RSVP=TRUE;CUTYPE=INDIVIDUAL:mailto:alice@example.com"""
            )
        )
        assertEquals(1, attendees.size)
        val a = attendees[0]
        assertEquals("mailto:alice@example.com", a.address)
        assertEquals("Alice Test", a.displayName)
        assertEquals("ACCEPTED", a.partstat)
        assertEquals("REQ-PARTICIPANT", a.role)
        assertEquals(true, a.rsvp)
        assertEquals("INDIVIDUAL", a.cutype)
        assertEquals(0, a.sortOrder)
    }

    @Test
    fun `urn-uuid CAL-ADDRESS is stored verbatim, not prefixed with mailto`() {
        // RFC 5545 §3.3.3: ATTENDEE is any URI. A urn:uuid form must not become
        // mailto:urn:uuid: in the table — that breaks display, matchesAttendee,
        // and avatar canonicalization, and round-trips wrong on the next push.
        val a = parseAndMap(
            listOf("ATTENDEE;CN=Alice:urn:uuid:0c3f2d4e-9b1a-4f6e-8a2b-1c2d3e4f5061")
        )[0]
        assertEquals("urn:uuid:0c3f2d4e-9b1a-4f6e-8a2b-1c2d3e4f5061", a.address)
    }

    @Test
    fun `principal-href CAL-ADDRESS is stored verbatim, not prefixed with mailto`() {
        val a = parseAndMap(
            listOf("ATTENDEE;CN=Boss:https://caldav.example.com/principals/users/boss/")
        )[0]
        assertEquals("https://caldav.example.com/principals/users/boss/", a.address)
    }

    @Test
    fun `partStat NEEDS_ACTION translates to hyphenated NEEDS-ACTION on wire`() {
        val a = parseAndMap(listOf("ATTENDEE:mailto:alice@example.com"))[0]
        assertEquals("NEEDS-ACTION", a.partstat)
    }

    @Test
    fun `role OPT_PARTICIPANT translates to hyphenated OPT-PARTICIPANT`() {
        val a = parseAndMap(
            listOf("ATTENDEE;ROLE=OPT-PARTICIPANT:mailto:alice@example.com")
        )[0]
        assertEquals("OPT-PARTICIPANT", a.role)
    }

    @Test
    fun `cutype defaults to INDIVIDUAL when CUTYPE param absent`() {
        val a = parseAndMap(listOf("ATTENDEE:mailto:alice@example.com"))[0]
        assertEquals("INDIVIDUAL", a.cutype)
    }

    @Test
    fun `cutype RESOURCE translates to RESOURCE`() {
        val a = parseAndMap(
            listOf("ATTENDEE;CUTYPE=RESOURCE:mailto:room1@example.com")
        )[0]
        assertEquals("RESOURCE", a.cutype)
    }

    @Test
    fun `rsvp TRUE on wire translates to true`() {
        val a = parseAndMap(
            listOf("ATTENDEE;RSVP=TRUE:mailto:alice@example.com")
        )[0]
        assertEquals(true, a.rsvp)
    }

    @Test
    fun `rsvp FALSE on wire translates to false (explicit-FALSE preserved)`() {
        val a = parseAndMap(
            listOf("ATTENDEE;RSVP=FALSE:mailto:alice@example.com")
        )[0]
        assertEquals(false, a.rsvp)
    }

    @Test
    fun `rsvp absent on wire translates to null (three-state semantics)`() {
        val a = parseAndMap(listOf("ATTENDEE:mailto:alice@example.com"))[0]
        assertNull(a.rsvp)
    }

    @Test
    fun `member single value passes through as one-element list`() {
        val a = parseAndMap(
            listOf(
                """ATTENDEE;MEMBER="mailto:dlist@example.com":mailto:alice@example.com"""
            )
        )[0]
        // parseMailtoList strips mailto: prefix on parse — that's intentional for delegatedTo/From parity.
        assertEquals(listOf("dlist@example.com"), a.member)
    }

    @Test
    @org.junit.Ignore(
        "ical4j 4.x truncates DELEGATED-FROM multi-value lists to the first value " +
            "(same root cause as MEMBER multi-value, see disabled test in " +
            "ICalParserAttendeeTest). Deferred until ical4j parser workaround lands; " +
            "the Room schema (List<String>) is forward-compatible."
    )
    fun `delegatedFrom multi-value passes through as List`() {
        val a = parseAndMap(
            listOf(
                """ATTENDEE;DELEGATED-FROM="mailto:a@x.com","mailto:b@x.com":mailto:alice@example.com"""
            )
        )[0]
        assertEquals(listOf("a@x.com", "b@x.com"), a.delegatedFrom)
    }

    @Test
    fun `delegatedFrom single value passes through correctly (multi-value deferred)`() {
        val a = parseAndMap(
            listOf(
                """ATTENDEE;DELEGATED-FROM="mailto:a@x.com":mailto:alice@example.com"""
            )
        )[0]
        assertEquals(listOf("a@x.com"), a.delegatedFrom)
    }

    @Test
    fun `delegatedTo single value passes through`() {
        val a = parseAndMap(
            listOf(
                """ATTENDEE;DELEGATED-TO="mailto:delegate@x.com":mailto:alice@example.com"""
            )
        )[0]
        assertEquals(listOf("delegate@x.com"), a.delegatedTo)
    }

    @Test
    fun `sentBy translates to sent_by column`() {
        val a = parseAndMap(
            listOf(
                """ATTENDEE;SENT-BY="mailto:assistant@x.com":mailto:alice@example.com"""
            )
        )[0]
        assertEquals("assistant@x.com", a.sentBy)
    }

    @Test
    fun `scheduleAgent SERVER translates to TEXT SERVER`() {
        val a = parseAndMap(
            listOf("ATTENDEE;SCHEDULE-AGENT=SERVER:mailto:alice@example.com")
        )[0]
        assertEquals("SERVER", a.scheduleAgent)
    }

    @Test
    fun `scheduleAgent absent translates to null`() {
        val a = parseAndMap(listOf("ATTENDEE:mailto:alice@example.com"))[0]
        assertNull(a.scheduleAgent)
    }

    @Test
    fun `scheduleStatus first code stored when multi-value`() {
        val a = parseAndMap(
            listOf(
                """ATTENDEE;SCHEDULE-STATUS="2.0;Server delivered":mailto:alice@example.com"""
            )
        )[0]
        assertEquals("2.0", a.scheduleStatus)
    }

    @Test
    fun `scheduleForceSend REQUEST translates to REQUEST`() {
        val a = parseAndMap(
            listOf(
                "ATTENDEE;SCHEDULE-FORCE-SEND=REQUEST:mailto:alice@example.com"
            )
        )[0]
        assertEquals("REQUEST", a.scheduleForceSend)
    }

    @Test
    fun `multiple attendees get sequential sortOrder values`() {
        val attendees = parseAndMap(
            listOf(
                "ATTENDEE:mailto:alice@example.com",
                "ATTENDEE:mailto:bob@example.com",
                "ATTENDEE:mailto:carol@example.com"
            )
        )
        assertEquals(3, attendees.size)
        assertEquals(0, attendees[0].sortOrder)
        assertEquals(1, attendees[1].sortOrder)
        assertEquals(2, attendees[2].sortOrder)
        assertEquals("mailto:alice@example.com", attendees[0].address)
        assertEquals("mailto:bob@example.com", attendees[1].address)
        assertEquals("mailto:carol@example.com", attendees[2].address)
    }

    @Test
    fun `event with no attendees yields empty list`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:no-attendees
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Solo Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val event = (parser.parse(ical) as ParseResult.Success).value.events.single()
        val mapped = ICalEventMapper.toEntity(
            icalEvent = event,
            rawIcal = ical,
            calendarId = 1L,
            caldavUrl = null,
            etag = null
        )
        assertEquals(0, mapped.attendees.size)
    }

    @Test
    fun `eventId is 0L on mapper output (caller must rewrite after upsert)`() {
        val a = parseAndMap(listOf("ATTENDEE:mailto:alice@example.com"))[0]
        assertEquals(
            "Mapper produces eventId=0L; PullStrategy/ConflictResolver/IcsParserService " +
                "must copy() with the real eventId after the parent event upsert returns its ID",
            0L, a.eventId
        )
    }
}
