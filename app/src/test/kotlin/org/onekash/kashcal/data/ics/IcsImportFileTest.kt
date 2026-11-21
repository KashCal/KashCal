package org.onekash.kashcal.data.ics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [IcsParserService.parseForImport] keeps what a file import needs and
 * [IcsParserService.parseIcsContent] drops for subscriptions: the METHOD, ORGANIZER and ATTENDEE
 * rows, and cancelled components.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsImportFileTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("ics/itip/$name")) { "missing $name" }.readText()

    private fun vcal(method: String?, vararg vevents: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n" +
            (method?.let { "METHOD:$it\r\n" } ?: "") + vevents.joinToString("") + "END:VCALENDAR\r\n"

    private fun vevent(vararg lines: String) = "BEGIN:VEVENT\r\n" + lines.joinToString("") { "$it\r\n" } + "END:VEVENT\r\n"

    @Test
    fun `the issue 364 file keeps its METHOD, organizer, attendee and UID`() {
        val file = requireNotNull(IcsParserService.parseForImport(fixture("issue_364_request.ics")))

        assertEquals(ImportMethod.REQUEST, file.method)
        assertTrue(file.isInvitation)
        val group = file.groups.single()
        assertEquals("test-invitation-123@example.com", group.uid)
        val master = group.masters.single()
        assertTrue(group.exceptions.isEmpty())
        assertEquals("organizer@example.com", master.event.organizerEmail)
        val attendee = master.attendees.single()
        assertEquals("mailto:attendee@example.com", attendee.address)
        assertEquals("NEEDS-ACTION", attendee.partstat)
        assertEquals(true, attendee.rsvp)
        assertNotNull("the file's DTSTAMP is kept as itipDtstamp", master.event.itipDtstamp)
    }

    @Test
    fun `a CANCEL without STATUS parses as a live component with its METHOD`() {
        val file = requireNotNull(IcsParserService.parseForImport(fixture("nextcloud_cancel.ics")))

        assertEquals(ImportMethod.CANCEL, file.method)
        val c = file.groups.single().masters.single()
        assertFalse("Nextcloud's CANCEL carries no STATUS", c.isCancelled)
        assertEquals(1, c.event.sequence)
    }

    @Test
    fun `a REPLY keeps its METHOD`() {
        val file = requireNotNull(IcsParserService.parseForImport(fixture("nextcloud_reply.ics")))

        assertEquals(ImportMethod.REPLY, file.method)
        assertFalse(file.isInvitation)
    }

    @Test
    fun `a cancelled component is kept for import while the subscription parse drops it`() {
        val content = vcal(
            null,
            vevent(
                "UID:gone@example.com", "DTSTAMP:20260901T120000Z", "DTSTART:20261001T100000Z",
                "DTEND:20261001T110000Z", "SUMMARY:Gone", "STATUS:CANCELLED"
            )
        )

        val file = requireNotNull(IcsParserService.parseForImport(content))

        assertNull(file.method)
        assertTrue(file.groups.single().masters.single().isCancelled)
        assertTrue(IcsParserService.parseIcsContent(content, 0, 0).isEmpty())
    }

    @Test
    fun `server receipts in the file are cleared`() {
        val content = vcal(
            "REQUEST",
            vevent(
                "UID:stamped@example.com", "DTSTAMP:20260901T120000Z", "DTSTART:20261001T100000Z",
                "DTEND:20261001T110000Z", "SUMMARY:Stamped",
                "ORGANIZER;SCHEDULE-STATUS=1.2:mailto:boss@example.com",
                "ATTENDEE;PARTSTAT=ACCEPTED;SCHEDULE-STATUS=1.2:mailto:me@example.com"
            )
        )

        val c = requireNotNull(IcsParserService.parseForImport(content)).groups.single().masters.single()

        assertNull("a file's ORGANIZER receipt must not read as 'Reply sent'", c.event.organizerScheduleStatus)
        assertEquals("ACCEPTED", c.attendees.single().partstat)
    }

    @Test
    fun `an instance-only CANCEL is a group with no master, and RANGE=THISANDFUTURE is flagged`() {
        val content = vcal(
            "CANCEL",
            vevent(
                "UID:series@example.com", "DTSTAMP:20260901T120000Z", "SEQUENCE:2",
                "RECURRENCE-ID:20261008T100000Z", "DTSTART:20261008T100000Z", "DTEND:20261008T110000Z",
                "ORGANIZER:mailto:boss@example.com", "STATUS:CANCELLED"
            ),
            vevent(
                "UID:series@example.com", "DTSTAMP:20260901T120000Z", "SEQUENCE:2",
                "RECURRENCE-ID;RANGE=THISANDFUTURE:20261015T100000Z", "DTSTART:20261015T100000Z",
                "DTEND:20261015T110000Z", "ORGANIZER:mailto:boss@example.com", "STATUS:CANCELLED"
            )
        )

        val group = requireNotNull(IcsParserService.parseForImport(content)).groups.single()

        assertTrue(group.masters.isEmpty())
        assertEquals(2, group.exceptions.size)
        assertTrue(group.exceptions.all { it.isInstance })
        assertEquals(listOf(false, true), group.exceptions.map { it.thisAndFuture })
    }

    @Test
    fun `a file that doesn't parse returns null`() {
        assertNull(IcsParserService.parseForImport("BEGIN:VCALENDAR\r\nnot ical"))
    }

    // ---- the stored copy each group carries ----

    private fun copiesOf(file: IcsImportFile, uid: String) =
        file.groups.single { it.uid == uid }.components.map { it.ics }

    private fun unfoldedLines(copy: String) = copy.replace(Regex("\r?\n[ \t]"), "").lines().map { it.trimEnd('\r') }

    private val newYork = "BEGIN:VTIMEZONE\r\nTZID:America/New_York\r\nBEGIN:STANDARD\r\n" +
        "DTSTART:19701101T020000\r\nTZOFFSETFROM:-0400\r\nTZOFFSETTO:-0500\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"
    private val tokyo = "BEGIN:VTIMEZONE\r\nTZID:Asia/Tokyo\r\nBEGIN:STANDARD\r\n" +
        "DTSTART:19700101T000000\r\nTZOFFSETFROM:+0900\r\nTZOFFSETTO:+0900\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"

    @Test
    fun `every component of a group carries the group's text without METHOD or other groups`() {
        val content = vcal(
            "PUBLISH",
            newYork + tokyo +
                vevent("UID:a", "DTSTART;TZID=America/New_York:20261102T090000", "DURATION:PT1H", "RRULE:FREQ=WEEKLY;COUNT=3", "SUMMARY:A", "X-A:series"),
            vevent("UID:b", "DTSTART;TZID=Asia/Tokyo:20261103T090000", "DURATION:PT1H", "SUMMARY:B", "X-B:1"),
            vevent("UID:a", "RECURRENCE-ID;TZID=America/New_York:20261109T090000", "DTSTART;TZID=America/New_York:20261109T100000", "DURATION:PT1H", "SUMMARY:A moved", "X-A:occurrence"),
        )
        val file = requireNotNull(IcsParserService.parseForImport(content))
        val copies = copiesOf(file, "a")
        assertEquals("series and changed occurrence share one copy", 1, copies.toSet().size)
        val lines = unfoldedLines(requireNotNull(copies.first()))
        assertTrue("no METHOD", lines.none { it.startsWith("METHOD") })
        assertEquals("both of a's VEVENTs, in file order", listOf("X-A:series", "X-A:occurrence"), lines.filter { it.startsWith("X-A") })
        assertTrue("no other group", lines.none { it.startsWith("X-B") || it == "UID:b" })
        assertEquals("only the zone a names", listOf("TZID:America/New_York"), lines.filter { it.startsWith("TZID:") })
        assertTrue("b has its own copy", copiesOf(file, "b").single()!!.contains("X-B:1"))
    }

    @Test
    fun `an invitation's copy has no VALARM`() {
        val content = vcal(
            "REQUEST",
            vevent(
                "UID:inv", "DTSTAMP:20260901T120000Z", "DTSTART:20261020T160000Z", "DTEND:20261020T170000Z",
                "SUMMARY:Invite", "ORGANIZER:mailto:org@example.test", "ATTENDEE:mailto:me@example.test",
                "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Organizer's", "TRIGGER:-PT5M", "END:VALARM",
            ),
        )
        val copy = requireNotNull(copiesOf(requireNotNull(IcsParserService.parseForImport(content)), "inv").single())
        assertFalse(copy.contains("VALARM"))
        assertTrue(copy.contains("ORGANIZER:mailto:org@example.test"))
    }

    @Test
    fun `LF-only and CRLF files both keep a copy`() {
        val lf = requireNotNull(IcsParserService.parseForImport(fixture("issue_364_request.ics")))
        assertNotNull(lf.groups.single().masters.single().ics)
        val crlf = requireNotNull(IcsParserService.parseForImport(fixture("nextcloud_request.ics")))
        crlf.groups.forEach { group -> group.components.forEach { assertNotNull(it.ics) } }
    }

    @Test
    fun `a folded UID line keeps a copy`() {
        val longUid = "folded-" + "0123456789".repeat(8) + "@example.test"
        val folded = "UID:" + longUid.substring(0, 60) + "\r\n " + longUid.substring(60)
        val file = requireNotNull(IcsParserService.parseForImport(vcal(null, vevent(folded, "DTSTART:20261020T160000Z", "DTEND:20261020T170000Z", "SUMMARY:Folded", "X-KEPT:1"))))
        assertEquals(longUid, file.groups.single().uid)
        assertTrue(requireNotNull(file.groups.single().masters.single().ics).contains("X-KEPT:1"))
    }

    @Test
    fun `the copy builder reads component tags in any case`() {
        // The parser reads no events from a lowercase file, so this checks the builder alone.
        val content = vcal(null, "begin:vevent\r\nUID:lower\r\nDTSTART:20261020T160000Z\r\nSUMMARY:Lower\r\nX-KEPT:1\r\nend:vevent\r\n")
        val copy = requireNotNull(ImportedCopy.build(content, dropAlarms = false)["lower"])
        assertTrue(copy.contains("X-KEPT:1"))
        assertTrue(copy.contains("end:vevent"))
    }

    @Test
    fun `a byte order mark keeps a copy`() {
        val content = "\uFEFF" + vcal(null, vevent("UID:bom", "DTSTART:20261020T160000Z", "DTEND:20261020T170000Z", "SUMMARY:Bom", "X-KEPT:1"))
        val file = requireNotNull(IcsParserService.parseForImport(content))
        file.groups.single().components.forEach {
            val copy = requireNotNull(it.ics)
            assertTrue(copy.contains("X-KEPT:1"))
            assertFalse("the mark isn't copied", copy.startsWith("\uFEFF"))
        }
    }

    @Test
    fun `a file with two VCALENDARs keeps the copies of the one the parser reads`() {
        val first = vcal(null, vevent("UID:first-cal", "DTSTART:20261020T160000Z", "DTEND:20261020T170000Z", "SUMMARY:First", "X-ONE:1"))
        val second = vcal(null, vevent("UID:second-cal", "DTSTART:20261021T160000Z", "DTEND:20261021T170000Z", "SUMMARY:Second", "X-TWO:2"))
        val file = requireNotNull(IcsParserService.parseForImport(first + second))
        assertTrue(file.groups.isNotEmpty())
        for (group in file.groups) {
            val copy = requireNotNull(group.masters.single().ics) { "${group.uid} keeps a copy" }
            assertEquals("one calendar in the copy", 1, Regex("BEGIN:VCALENDAR").findAll(copy).count())
            assertEquals("only its own VEVENT", 1, Regex("BEGIN:VEVENT").findAll(copy).count())
            assertTrue(copy.contains("UID:${group.uid}"))
        }
    }

    @Test
    fun `a copy that doesn't parse to the group's VEVENTs is not kept`() {
        val series = vcal(
            null,
            vevent("UID:check", "DTSTART:20261102T160000Z", "DTEND:20261102T170000Z", "RRULE:FREQ=WEEKLY;COUNT=3", "SUMMARY:S"),
            vevent("UID:check", "RECURRENCE-ID:20261109T160000Z", "DTSTART:20261109T180000Z", "DTEND:20261109T190000Z", "SUMMARY:Moved"),
        )
        val events = org.onekash.icaldav.parser.ICalParser().parseAllEvents(series).getOrNull()!!
        assertEquals("the matching copy is kept", series, IcsParserService.verifiedCopy(series, events))
        val otherInstance = series.replace("RECURRENCE-ID:20261109T160000Z", "RECURRENCE-ID:20261116T160000Z")
        assertNull("another occurrence", IcsParserService.verifiedCopy(otherInstance, events))
        val seriesOnly = vcal(null, vevent("UID:check", "DTSTART:20261102T160000Z", "DTEND:20261102T170000Z", "RRULE:FREQ=WEEKLY;COUNT=3", "SUMMARY:S"))
        assertNull("a VEVENT missing", IcsParserService.verifiedCopy(seriesOnly, events))
        assertNull("not iCalendar", IcsParserService.verifiedCopy("BEGIN:VCALENDAR\r\nnot ical", events))
    }

    @Test
    fun `a VEVENT without a UID keeps no copy`() {
        val content = vcal(null, vevent("DTSTART:20261020T160000Z", "DTEND:20261020T170000Z", "SUMMARY:No uid", "X-LOST:1"))
        val file = requireNotNull(IcsParserService.parseForImport(content))
        assertNull(file.groups.single().masters.single().ics)
    }

    @Test
    fun `copies for a large file are built in one pass`() {
        val vevents = (0 until 2_000).joinToString("") { n ->
            vevent("UID:bulk-$n@example.test", "DTSTART:20261020T160000Z", "DTEND:20261020T170000Z", "SUMMARY:Bulk $n", "X-N:$n")
        }
        val content = vcal(null, vevents)
        val started = System.nanoTime()
        val file = requireNotNull(IcsParserService.parseForImport(content))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(2_000, file.groups.size)
        assertTrue("every group keeps its copy", file.groups.all { it.masters.single().ics != null })
        assertTrue("built in ${elapsedMs}ms", elapsedMs < 5_000)
    }
}
