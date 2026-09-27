package org.onekash.kashcal.sync.strategy

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * [ServerChangeMerge] puts the fields the user changed in KashCal on top of the server's
 * current copy and keeps everything else as the server holds it.
 *
 * Local rows come from [ICalEventMapper.toEntity] over the stored body, as pull maps it, with the
 * edited fields changed by `copy`; the tests of an event KashCal created build the row and store
 * the body [IcsPatcher.serialize] makes of it. The tests cover each field group, SEQUENCE, the
 * server's unknown properties and alarms, attendees, changed occurrences, an event KashCal created,
 * a missing stored copy or one of another series, server bodies that can't be merged, and an
 * untouched event, which merges to the server's copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ServerChangeMergeTest {

    private val uid = "review@example.test"
    private val self = "mailto:self@example.test"
    private val account = Account(
        id = 1L, provider = AccountProvider.CALDAV, email = "self@example.test", calendarUserAddresses = listOf(self)
    )

    /** The organizer's copy KashCal last stored. */
    private fun baseline(
        guestPartstat: String = "NEEDS-ACTION",
        extraSeries: List<String> = emptyList(),
        occurrences: List<List<String>> = emptyList(),
        rrule: String? = "RRULE:FREQ=DAILY;COUNT=5",
        exdate: String? = null,
    ) = (
        listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Other//Client 1.0//EN",
            "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261101T100000Z",
            "DTSTART:20261201T100000Z", "DTEND:20261201T110000Z",
        ) + listOfNotNull(rrule, exdate) + listOf(
            "SUMMARY:Review", "SEQUENCE:2",
            "ORGANIZER;CN=Self:$self",
            "ATTENDEE;CN=Self;ROLE=CHAIR;PARTSTAT=ACCEPTED:$self",
            "ATTENDEE;CN=Guest;ROLE=REQ-PARTICIPANT;PARTSTAT=$guestPartstat;RSVP=TRUE:mailto:guest@example.test",
        ) + extraSeries + listOf("END:VEVENT") +
            occurrences.flatMap { listOf("BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261101T100000Z") + it + "END:VEVENT" } +
            listOf("END:VCALENDAR", "")
        ).joinToString("\r\n")

    private fun occurrence(day: Int, summary: String, vararg extra: String) = listOf(
        "RECURRENCE-ID:202612%02dT100000Z".format(day),
        "DTSTART:202612%02dT120000Z".format(day), "DTEND:202612%02dT130000Z".format(day),
        "SUMMARY:$summary", "SEQUENCE:2",
        "ORGANIZER;CN=Self:$self",
        "ATTENDEE;CN=Self;ROLE=CHAIR;PARTSTAT=ACCEPTED:$self",
        "ATTENDEE;CN=Guest;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:guest@example.test",
    ) + extra

    private fun instant(day: Int) = Instant.parse("2026-12-%02dT10:00:00Z".format(day)).toEpochMilli()

    /** The rows pull stores for [ics]. */
    private class Pulled(
        val series: Event,
        val attendees: List<Attendee>,
        val occurrences: List<Pair<Event, List<Attendee>>>,
    )

    private fun pull(ics: String): Pulled {
        val parsed = ICalParser().parseAllEvents(ics).getOrNull()!!
        val seriesParsed = parsed.single { it.recurrenceId == null }
        val series = ICalEventMapper.toEntity(seriesParsed, ics, 7L, "https://dav.example.test/cal/r.ics", "e1")
        val occurrences = parsed.filter { it.recurrenceId != null }.mapIndexed { index, change ->
            val mapped = ICalEventMapper.toEntity(
                change, ics, 7L, "https://dav.example.test/cal/r.ics", "e1", masterDtStart = seriesParsed.dtStart
            )
            val id = 100L + index
            mapped.event.copy(id = id, originalEventId = 1L) to mapped.attendees.map { it.copy(eventId = id) }
        }
        return Pulled(series.event.copy(id = 1L), series.attendees.map { it.copy(eventId = 1L) }, occurrences)
    }

    private fun merge(
        base: String?,
        local: Pulled,
        server: String,
        series: Event = local.series,
        attendees: List<Attendee> = local.attendees,
        occurrences: List<Pair<Event, List<Attendee>>> = local.occurrences,
    ) = ServerChangeMerge.merge(
        baseline = base, series = series.copy(rawIcal = base), seriesAttendees = attendees,
        occurrences = occurrences, server = server, account = account
    )

    // ---- reading the result ----

    private fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")

    private fun vevents(ics: String) =
        Regex("""BEGIN:VEVENT.*?END:VEVENT""", RegexOption.DOT_MATCHES_ALL).findAll(unfold(ics)).map { it.value }.toList()

    private fun seriesOf(ics: String) = vevents(ics).single { "RECURRENCE-ID" !in it }
    private fun occurrenceOf(ics: String, day: Int) =
        vevents(ics).single { "RECURRENCE-ID:202612%02dT100000Z".format(day) in it }

    private fun prop(vevent: String, name: String) =
        vevent.lines().firstOrNull { it.startsWith("$name:") || it.startsWith("$name;") }?.substringAfter(':')?.trim()

    private fun attendeeLinesFor(vevent: String, addresses: List<String>) =
        vevent.lines().filter { line -> line.startsWith("ATTENDEE") && addresses.any { line.trimEnd().endsWith(it) } }

    private fun partstat(vevent: String, address: String) =
        vevent.lines().firstOrNull { it.startsWith("ATTENDEE") && it.trimEnd().endsWith(address) }
            ?.let { Regex("""PARTSTAT=([A-Z-]+)""").find(it)?.groupValues?.get(1) }

    private fun attendees(vevent: String) = ICalParser().parseAllEvents(
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$vevent\r\nEND:VCALENDAR\r\n"
    ).getOrNull()!!.single().attendees.map { listOf(it.email, it.name, it.partStat, it.role, it.rsvp) }.toSet()

    private fun parsedSeries(ics: String) =
        ICalParser().parseAllEvents(ics).getOrNull()!!.single { it.recurrenceId == null }

    private fun mappedColor(ics: String) = ICalEventMapper.toEntity(parsedSeries(ics), ics, 7L, null, null).event.color

    // ---- series fields ----

    @Test
    fun `a rename in KashCal and a description added elsewhere both survive`() {
        val base = baseline()
        val server = baseline(extraSeries = listOf("DESCRIPTION:Other device note"))
        val local = pull(base)

        val merged = merge(base, local, server, series = local.series.copy(title = "Review renamed"))!!

        val series = seriesOf(merged.body)
        assertEquals("Review renamed", prop(series, "SUMMARY"))
        assertEquals("Other device note", prop(series, "DESCRIPTION"))
    }

    @Test
    fun `a guest's answer on the server survives a rename and every attendee keeps the server's parameters`() {
        val base = baseline()
        val server = baseline(guestPartstat = "ACCEPTED")
        val local = pull(base)

        val merged = merge(base, local, server, series = local.series.copy(title = "Review renamed"))!!

        val series = seriesOf(merged.body)
        assertEquals("Review renamed", prop(series, "SUMMARY"))
        assertEquals("ACCEPTED", partstat(series, "mailto:guest@example.test"))
        // The generator rewrites each line (a default ROLE is left out), so compare the
        // parameters it carries.
        assertEquals(attendees(seriesOf(server)), attendees(series))
    }

    @Test
    fun `attendee rows that differ from the stored copy in another parameter still keep the guest's answer`() {
        val base = baseline()
        val server = baseline(guestPartstat = "ACCEPTED")
        val local = pull(base)
        // The rows were rewritten from a read-back that dropped RSVP and the display name.
        val rows = local.attendees.map { if ("guest" in it.address) it.copy(rsvp = null, displayName = null) else it }

        val merged = merge(base, local, server, attendees = rows, series = local.series.copy(title = "Review renamed"))!!

        assertEquals("ACCEPTED", partstat(seriesOf(merged.body), "mailto:guest@example.test"))
    }

    @Test
    fun `a field both sides changed takes KashCal's value`() {
        val base = baseline()
        val server = baseline().replace("SUMMARY:Review\r\n", "SUMMARY:Review (server)\r\n")
        val local = pull(base)

        val merged = merge(base, local, server, series = local.series.copy(title = "Review (phone)"))!!

        assertEquals("Review (phone)", prop(seriesOf(merged.body), "SUMMARY"))
    }

    @Test
    fun `each field group is taken from KashCal only when KashCal changed it`() {
        val base = baseline(extraSeries = listOf("LOCATION:Room 1", "DESCRIPTION:Agenda", "URL:https://example.test/a",
            "PRIORITY:5", "CLASS:PUBLIC", "TRANSP:OPAQUE", "STATUS:CONFIRMED", "CATEGORIES:Work", "GEO:1.0;2.0",
            "COLOR:red", "RDATE:20261220T100000Z",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM"))
        val server = base
            .replace("LOCATION:Room 1", "LOCATION:Room 2")
            .replace("DESCRIPTION:Agenda", "DESCRIPTION:Agenda v2")
            .replace("URL:https://example.test/a", "URL:https://example.test/b")
            .replace("PRIORITY:5", "PRIORITY:1")
            .replace("CLASS:PUBLIC", "CLASS:PRIVATE")
            .replace("TRANSP:OPAQUE", "TRANSP:TRANSPARENT")
            .replace("STATUS:CONFIRMED", "STATUS:TENTATIVE")
            .replace("CATEGORIES:Work", "CATEGORIES:Home")
            .replace("GEO:1.0;2.0", "GEO:3.0;4.0")
            .replace("COLOR:red", "COLOR:blue")
            .replace("RDATE:20261220T100000Z", "RDATE:20261221T100000Z")
            .replace("TRIGGER:-PT15M", "TRIGGER:-PT30M")
            .replace("RRULE:FREQ=DAILY;COUNT=5", "RRULE:FREQ=DAILY;COUNT=7")
            .replace("DTSTART:20261201T100000Z", "DTSTART:20261201T090000Z")
        val local = pull(base)

        // Untouched locally: each group asserted below comes from the server.
        val untouched = seriesOf(merge(base, local, server, series = local.series.copy(title = "Renamed"))!!.body)
        assertEquals("Room 2", prop(untouched, "LOCATION"))
        assertEquals("Agenda v2", prop(untouched, "DESCRIPTION"))
        assertEquals("https://example.test/b", prop(untouched, "URL"))
        assertEquals("1", prop(untouched, "PRIORITY"))
        assertEquals("PRIVATE", prop(untouched, "CLASS"))
        assertEquals("TRANSPARENT", prop(untouched, "TRANSP"))
        assertEquals("TENTATIVE", prop(untouched, "STATUS"))
        assertEquals("Home", prop(untouched, "CATEGORIES"))
        assertEquals("3.0;4.0", prop(untouched, "GEO"))
        assertEquals("FREQ=DAILY;COUNT=7", prop(untouched, "RRULE"))
        assertEquals("20261201T090000Z", prop(untouched, "DTSTART"))
        val untouchedBody = merge(base, local, server, series = local.series.copy(title = "Renamed"))!!.body
        assertEquals("the server's color", mappedColor(server), mappedColor(untouchedBody))
        assertEquals(setOf(Instant.parse("2026-12-21T10:00:00Z").toEpochMilli()),
            parsedSeries(untouchedBody).rdates.map { it.timestamp }.toSet())
        assertTrue("the server's alarm", "TRIGGER:-PT30M" in untouched)

        // Changed locally: each group asserted below comes from KashCal.
        val edited = local.series.copy(
            location = "Room 9", description = "Mine", url = "https://example.test/mine", priority = 9,
            classification = "CONFIDENTIAL", transp = "OPAQUE", status = "CANCELLED", categories = listOf("Mine"),
            geoLat = 5.0, geoLon = 6.0, rrule = "FREQ=DAILY;COUNT=3",
            startTs = local.series.startTs + 3_600_000L, endTs = local.series.endTs + 3_600_000L,
            reminders = listOf("-PT45M"), rdate = null,
        )
        val mineBody = merge(base, local, server, series = edited)!!.body
        val mine = seriesOf(mineBody)
        assertEquals("Room 9", prop(mine, "LOCATION"))
        assertEquals("Mine", prop(mine, "DESCRIPTION"))
        assertEquals("https://example.test/mine", prop(mine, "URL"))
        assertEquals("9", prop(mine, "PRIORITY"))
        assertEquals("CONFIDENTIAL", prop(mine, "CLASS"))
        assertEquals("CANCELLED", prop(mine, "STATUS"))
        assertEquals("Mine", prop(mine, "CATEGORIES"))
        assertEquals("5.0;6.0", prop(mine, "GEO"))
        assertEquals("FREQ=DAILY;COUNT=3", prop(mine, "RRULE"))
        assertEquals("20261201T110000Z", prop(mine, "DTSTART"))
        assertTrue("KashCal's alarm", "TRIGGER:-PT45M" in mine)
        assertEquals("KashCal's removed RDATE stays removed, the server's new one stays",
            setOf(Instant.parse("2026-12-21T10:00:00Z").toEpochMilli()), parsedSeries(mineBody).rdates.map { it.timestamp }.toSet())
        // TRANSP, all-day and color, changed on their own.
        val transp = seriesOf(merge(base, local, server, series = local.series.copy(transp = "TRANSPARENT"))!!.body)
        assertEquals("TRANSPARENT", prop(transp, "TRANSP"))
        val day = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()
        val allDay = seriesOf(merge(base, local, server, series = local.series.copy(
            isAllDay = true, startTs = day, endTs = day + 86_400_000L - 1, timezone = null,
        ))!!.body)
        assertEquals("20261201", prop(allDay, "DTSTART"))
        val green = 0xFF00AA00.toInt()
        assertEquals("KashCal's color", green, mappedColor(merge(base, local, server, series = local.series.copy(color = green))!!.body))
    }

    @Test
    fun `a zone change in KashCal that keeps the same moment is kept`() {
        val base = baseline().replace("DTSTART:20261201T100000Z", "DTSTART;TZID=America/New_York:20261201T050000")
            .replace("DTEND:20261201T110000Z", "DTEND;TZID=America/New_York:20261201T060000")
        val server = base.replace("SUMMARY:Review", "SUMMARY:Review (server)")
        val local = pull(base)
        // The form's zone picker keeps the moment and changes the zone.
        val moved = local.series.copy(timezone = "Europe/London")

        val series = seriesOf(merge(base, local, server, series = moved)!!.body)

        assertTrue(series, series.lines().any { it.startsWith("DTSTART;TZID=Europe/London:") })
        assertEquals("Review (server)", prop(series, "SUMMARY"))
    }

    @Test
    fun `a form save that rewrites untouched fields counts only the field the user changed`() {
        val base = baseline(extraSeries = listOf("BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT1H", "END:VALARM"))
        val server = base.replace("DTSTART:20261201T100000Z", "DTSTART:20261201T090000Z")
            .replace("RRULE:FREQ=DAILY;COUNT=5", "RRULE:FREQ=DAILY;COUNT=6")
            .replace("TRIGGER:-PT15M", "TRIGGER:-PT20M")
            .replace("SUMMARY:Review", "SUMMARY:Review\r\nLOCATION:Room 4\r\nDESCRIPTION:Server agenda")
        val local = pull(base)
        // Untouched fields written back in another form: reminders in another order, the rule's
        // parts reordered, an empty location for none.
        val saved = local.series.copy(
            title = "Review renamed",
            reminders = local.series.reminders!!.reversed(),
            rrule = "COUNT=5;FREQ=DAILY",
            description = null,
            location = "",
        )

        val merged = seriesOf(merge(base, local, server, series = saved)!!.body)

        assertEquals("Review renamed", prop(merged, "SUMMARY"))
        assertEquals("the server's move survives", "20261201T090000Z", prop(merged, "DTSTART"))
        assertEquals("the server's rule survives", setOf("FREQ=DAILY", "COUNT=6"), prop(merged, "RRULE")!!.split(";").toSet())
        assertTrue("the server's alarm survives", "TRIGGER:-PT20M" in merged)
        assertEquals("Room 4", prop(merged, "LOCATION"))
        assertEquals("Server agenda", prop(merged, "DESCRIPTION"))
    }

    @Test
    fun `occurrences deleted on both sides stay deleted`() {
        val base = baseline()
        val server = baseline(exdate = "EXDATE:20261203T100000Z")
        val local = pull(base)

        val merged = merge(base, local, server, series = local.series.copy(exdate = instant(4).toString()))!!

        val excluded = parsedSeries(merged.body).exdates.map { it.timestamp }.toSet()
        assertEquals(setOf(instant(3), instant(4)), excluded)
    }

    @Test
    fun `an occurrence restored in KashCal comes back even if the server deleted another`() {
        val base = baseline(exdate = "EXDATE:20261203T100000Z")
        val server = baseline(exdate = "EXDATE:20261203T100000Z,20261204T100000Z")
        val local = pull(base)

        val merged = merge(base, local, server, series = local.series.copy(exdate = null))!!

        assertEquals(setOf(instant(4)), parsedSeries(merged.body).exdates.map { it.timestamp }.toSet())
    }

    @Test
    fun `SEQUENCE never goes below the server's and a local bump ends above it`() {
        val base = baseline()
        val server = baseline().replace("SEQUENCE:2", "SEQUENCE:4")
        val local = pull(base)

        val untouched = merge(base, local, server, series = local.series.copy(title = "Renamed"))!!
        assertEquals(4, parsedSeries(untouched.body).sequence)

        val bumped = merge(base, local, server, series = local.series.copy(sequence = 3, startTs = local.series.startTs + 1_800_000L, endTs = local.series.endTs + 1_800_000L))!!
        assertEquals(5, parsedSeries(bumped.body).sequence)
    }

    @Test
    fun `the server's unknown properties and hidden alarms survive on the series`() {
        val alarms = (1..6).flatMap { listOf("BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT${it}0M", "END:VALARM") }
        val base = baseline(extraSeries = alarms)
        val server = baseline(extraSeries = alarms + "X-OTHER-CLIENT;X-P=\"a;b\":kept as sent")
        val local = pull(base)

        val merged = seriesOf(merge(base, local, server, series = local.series.copy(title = "Renamed"))!!.body)

        assertTrue(merged, "X-OTHER-CLIENT;X-P=\"a;b\":kept as sent" in merged)
        assertEquals("the sixth alarm, never shown in the form, is kept", 6, Regex("BEGIN:VALARM").findAll(merged).count())
    }

    // ---- attendees ----

    @Test
    fun `an attendee added in KashCal joins a guest who answered on the server`() {
        val base = baseline()
        val server = baseline(guestPartstat = "ACCEPTED")
        val local = pull(base)
        val added = local.attendees + Attendee(eventId = 1L, address = "mailto:new@example.test", partstat = "NEEDS-ACTION", rsvp = true)

        val series = seriesOf(merge(base, local, server, attendees = added)!!.body)

        assertEquals("ACCEPTED", partstat(series, "mailto:guest@example.test"))
        assertEquals("NEEDS-ACTION", partstat(series, "mailto:new@example.test"))
    }

    @Test
    fun `an attendee removed in KashCal stays removed even if they answered on the server`() {
        val base = baseline()
        val server = baseline(guestPartstat = "ACCEPTED")
        val local = pull(base)

        val series = seriesOf(merge(base, local, server, attendees = local.attendees.filterNot { "guest" in it.address })!!.body)

        assertNull(partstat(series, "mailto:guest@example.test"))
        assertEquals("ACCEPTED", partstat(series, self))
    }

    @Test
    fun `the account's own answer changed in KashCal goes out with the server's other attendees`() {
        val base = baseline()
        val server = baseline(guestPartstat = "ACCEPTED")
        val local = pull(base)
        val mine = local.attendees.map { if (it.address == self) it.copy(partstat = "TENTATIVE") else it }

        val series = seriesOf(merge(base, local, server, attendees = mine)!!.body)

        assertEquals("TENTATIVE", partstat(series, self))
        assertEquals("ACCEPTED", partstat(series, "mailto:guest@example.test"))
    }

    @Test
    fun `the account's own answer changed on the server is kept when KashCal left it alone`() {
        val base = baseline()
        val server = baseline().replace("PARTSTAT=ACCEPTED:$self", "PARTSTAT=TENTATIVE:$self")
        val local = pull(base)
        val added = local.attendees + Attendee(eventId = 1L, address = "mailto:new@example.test", partstat = "NEEDS-ACTION")

        val series = seriesOf(merge(base, local, server, attendees = added)!!.body)

        assertEquals("TENTATIVE", partstat(series, self))
    }

    @Test
    fun `an attendee added on the server is kept`() {
        val base = baseline()
        val server = baseline(extraSeries = listOf("ATTENDEE;CN=Late;PARTSTAT=NEEDS-ACTION:mailto:late@example.test"))
        val local = pull(base)

        val series = seriesOf(merge(base, local, server, series = local.series.copy(title = "Renamed"))!!.body)

        assertEquals("NEEDS-ACTION", partstat(series, "mailto:late@example.test"))
    }

    // ---- changed occurrences ----

    @Test
    fun `changed occurrences keep the side that changed them and one added on the server is kept`() {
        val base = baseline(occurrences = listOf(occurrence(2, "Mine to edit"), occurrence(3, "Server edits this")))
        val server = baseline(occurrences = listOf(
            occurrence(2, "Mine to edit"),
            occurrence(3, "Server edited this"),
            occurrence(4, "Added on the server"),
        ))
        val local = pull(base)
        val edited = local.occurrences.map { (event, attendees) ->
            (if (event.originalInstanceTime == instant(2)) event.copy(title = "Edited in KashCal") else event) to attendees
        }

        val merged = merge(base, local, server, occurrences = edited)!!

        assertEquals("Edited in KashCal", prop(occurrenceOf(merged.body, 2), "SUMMARY"))
        assertEquals("Server edited this", prop(occurrenceOf(merged.body, 3), "SUMMARY"))
        assertEquals("Added on the server", prop(occurrenceOf(merged.body, 4), "SUMMARY"))
        assertEquals("only KashCal's rows count as sent", setOf(100L, 101L), merged.occurrences.map { it.id }.toSet())
    }

    @Test
    fun `a guest's answer to one occurrence on the server survives a series rename`() {
        val base = baseline(occurrences = listOf(occurrence(2, "Moved")))
        val server = baseline(occurrences = listOf(occurrence(2, "Moved").map {
            it.replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:guest", "PARTSTAT=DECLINED;RSVP=TRUE:mailto:guest")
        }))
        val local = pull(base)

        val merged = merge(base, local, server, series = local.series.copy(title = "Renamed"))!!

        assertEquals("DECLINED", partstat(occurrenceOf(merged.body, 2), "mailto:guest@example.test"))
    }

    @Test
    fun `a changed occurrence edited in KashCal but deleted elsewhere is sent without the deletion`() {
        val base = baseline(occurrences = listOf(occurrence(2, "Moved")))
        val server = baseline(exdate = "EXDATE:20261202T100000Z")
        val local = pull(base)
        val edited = local.occurrences.map { (event, rows) -> event.copy(title = "Edited in KashCal") to rows }

        val merged = merge(base, local, server, occurrences = edited)!!

        assertEquals("Edited in KashCal", prop(occurrenceOf(merged.body, 2), "SUMMARY"))
        assertFalse("its own instance isn't excluded", instant(2) in parsedSeries(merged.body).exdates.map { it.timestamp })
    }

    @Test
    fun `a changed occurrence deleted elsewhere and untouched in KashCal is left out`() {
        val base = baseline(occurrences = listOf(occurrence(2, "Moved")))
        val server = baseline(exdate = "EXDATE:20261202T100000Z")
        val local = pull(base)

        val merged = merge(base, local, server, series = local.series.copy(title = "Renamed"))!!

        assertTrue(vevents(merged.body).none { "RECURRENCE-ID" in it })
        assertTrue(merged.occurrences.isEmpty())
        assertTrue(instant(2) in parsedSeries(merged.body).exdates.map { it.timestamp })
    }

    @Test
    fun `a stored copy of another series counts as no stored copy`() {
        // A series split off with "this and future" starts as a copy of the old row, stored
        // body included.
        val other = baseline(occurrences = listOf(occurrence(4, "Old series change"))).replace("UID:$uid", "UID:old-series@example.test")
        val server = baseline(occurrences = listOf(occurrence(4, "Added on the server")))
        val local = pull(baseline())

        val merged = merge(other, local, server, occurrences = emptyList())!!

        assertEquals("Added on the server", prop(occurrenceOf(merged.body, 4), "SUMMARY"))
    }

    @Test
    fun `merged changed occurrences carry the merged fields`() {
        val base = baseline(occurrences = listOf(occurrence(2, "Moved")))
        val server = baseline(occurrences = listOf(occurrence(2, "Moved by the server")))
        val local = pull(base)

        val merged = merge(base, local, server, series = local.series.copy(title = "Renamed"))!!

        assertEquals(listOf(100L), merged.occurrences.map { it.id })
        assertEquals("Moved by the server", merged.occurrences.single().title)
    }

    @Test
    fun `a changed occurrence re-saved with the series' zone keeps the server's move of it`() {
        // The single-occurrence save builds the row from the series and writes the series'
        // zone, so an occurrence stored in another zone comes back with the same instants in
        // the series' zone.
        val base = baseline(occurrences = listOf(occurrence(2, "Moved").map {
            it.replace("DTSTART:20261202T120000Z", "DTSTART;TZID=Europe/Berlin:20261202T130000")
                .replace("DTEND:20261202T130000Z", "DTEND;TZID=Europe/Berlin:20261202T140000")
        }))
        val server = base.replace("DTSTART;TZID=Europe/Berlin:20261202T130000", "DTSTART;TZID=Europe/Berlin:20261202T160000")
            .replace("DTEND;TZID=Europe/Berlin:20261202T140000", "DTEND;TZID=Europe/Berlin:20261202T170000")
        val local = pull(base)
        val resaved = local.occurrences.map { (event, rows) -> event.copy(title = "Retitled", timezone = local.series.timezone) to rows }

        val merged = merge(base, local, server, occurrences = resaved)!!

        // The occurrence goes out in its own zone, RECURRENCE-ID included; the asserts check its
        // title and start instant.
        val occurrence = vevents(merged.body).single { "RECURRENCE-ID" in it }
        assertEquals("Retitled", prop(occurrence, "SUMMARY"))
        assertEquals(Instant.parse("2026-12-02T15:00:00Z").toEpochMilli(),
            ICalParser().parseAllEvents(merged.body).getOrNull()!!.single { it.recurrenceId != null }.dtStart.timestamp)
    }

    @Test
    fun `a guest added to a changed occurrence deleted elsewhere keeps the occurrence`() {
        val base = baseline(occurrences = listOf(occurrence(2, "Moved")))
        val server = baseline(exdate = "EXDATE:20261202T100000Z")
        val local = pull(base)
        val withGuest = local.occurrences.map { (event, rows) ->
            event to rows + Attendee(eventId = event.id, address = "mailto:new@example.test", partstat = "NEEDS-ACTION")
        }

        val merged = merge(base, local, server, occurrences = withGuest)!!

        assertEquals("NEEDS-ACTION", partstat(occurrenceOf(merged.body, 2), "mailto:new@example.test"))
    }

    @Test
    fun `the account's own answer changed in KashCal isn't sent twice when the server lists another of its addresses`() {
        val alias = "mailto:self.alias@example.test"
        val twoAddresses = account.copy(calendarUserAddresses = listOf(self, alias))
        val base = baseline()
        val server = baseline().replace("PARTSTAT=ACCEPTED:$self", "PARTSTAT=ACCEPTED:$alias")
        val local = pull(base)
        val mine = local.attendees.map { if (it.address == self) it.copy(partstat = "TENTATIVE") else it }

        val merged = ServerChangeMerge.merge(base, local.series.copy(rawIcal = base), mine, emptyList(), server, twoAddresses)!!

        val own = attendeeLinesFor(seriesOf(merged.body), listOf(self, alias))
        assertEquals(own.toString(), 1, own.size)
        assertTrue(own.single().contains("PARTSTAT=TENTATIVE"))
    }

    @Test
    fun `a changed occurrence KashCal added goes out`() {
        val base = baseline()
        val local = pull(base)
        val added = local.series.copy(
            id = 200L, originalEventId = 1L, originalInstanceTime = instant(5), rrule = null,
            title = "Added in KashCal", startTs = instant(5) + 7_200_000L, endTs = instant(5) + 10_800_000L,
        )

        val merged = merge(base, local, baseline(guestPartstat = "ACCEPTED"), occurrences = listOf(added to emptyList()))!!

        assertEquals("Added in KashCal", prop(occurrenceOf(merged.body, 5), "SUMMARY"))
    }

    // ---- baselines ----

    @Test
    fun `an event KashCal created and then edited only takes the edited field from KashCal`() {
        val start = Instant.parse("2026-12-10T09:00:00Z").toEpochMilli()
        val created = Event(
            id = 1L, uid = "made-here@example.test", calendarId = 7L, title = "Made here", description = "Notes",
            location = "Desk", startTs = start, endTs = start + 3_600_000L, timezone = "Europe/Berlin",
            reminders = listOf("-PT15M", "-PT1H"), color = 0xFF3366CC.toInt(), categories = listOf("Work"),
            rrule = "FREQ=WEEKLY;BYDAY=TH;COUNT=4", dtstamp = start,
        )
        val sent = IcsPatcher.serialize(created, null)
        val server = sent.replace("DESCRIPTION:Notes", "DESCRIPTION:Notes from the laptop")
        val local = Pulled(created.copy(rawIcal = sent), emptyList(), emptyList())

        val merged = seriesOf(merge(sent, local, server, series = local.series.copy(title = "Made here renamed"))!!.body)

        assertEquals("Made here renamed", prop(merged, "SUMMARY"))
        assertEquals("Notes from the laptop", prop(merged, "DESCRIPTION"))
    }

    @Test
    fun `an all-day event KashCal created keeps a server change to its note`() {
        val day = Instant.parse("2026-12-10T00:00:00Z").toEpochMilli()
        val created = Event(
            id = 1L, uid = "all-day@example.test", calendarId = 7L, title = "Holiday", startTs = day,
            endTs = day + 86_400_000L - 1, isAllDay = true, timezone = null, dtstamp = day,
        )
        val sent = IcsPatcher.serialize(created, null)
        val server = sent.replace("SUMMARY:Holiday", "SUMMARY:Holiday\r\nDESCRIPTION:Bring snacks")
        val local = Pulled(created.copy(rawIcal = sent), emptyList(), emptyList())

        val merged = seriesOf(merge(sent, local, server, series = local.series.copy(location = "Lake"))!!.body)

        assertEquals("Lake", prop(merged, "LOCATION"))
        assertEquals("Bring snacks", prop(merged, "DESCRIPTION"))
        assertEquals("20261210", prop(merged, "DTSTART"))
    }

    @Test
    fun `with no stored copy KashCal's fields win and the server's attendee answers are kept`() {
        val base = baseline()
        val server = baseline(guestPartstat = "ACCEPTED", extraSeries = listOf("DESCRIPTION:Server note"))
        val local = pull(base)

        val series = seriesOf(merge(null, local, server, series = local.series.copy(title = "Renamed"))!!.body)

        assertEquals("Renamed", prop(series, "SUMMARY"))
        assertNull("no baseline: KashCal's empty description wins", prop(series, "DESCRIPTION"))
        assertEquals("ACCEPTED", partstat(series, "mailto:guest@example.test"))
    }

    // ---- bodies that can't be merged, and an untouched one ----

    @Test
    fun `a server body that doesn't parse or holds no event can't be merged`() {
        val base = baseline()
        val local = pull(base)
        assertNull(merge(base, local, "not a calendar"))
        assertNull(merge(base, local, "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"))
    }

    @Test
    fun `a server body whose mapping throws can't be merged and nothing escapes`() {
        val base = baseline()
        val local = pull(base)
        mockkObject(ICalEventMapper)
        try {
            every { ICalEventMapper.toEntity(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("hostile")
            assertNull(merge(base, local, baseline(guestPartstat = "ACCEPTED")))
        } finally {
            unmockkObject(ICalEventMapper)
        }
    }

    @Test
    fun `an untouched event merges to the server's copy`() {
        val base = baseline()
        val server = baseline(guestPartstat = "ACCEPTED", extraSeries = listOf("DESCRIPTION:Server note"))
        val merged = merge(base, pull(base), server)
        assertNotNull(merged)
        val series = seriesOf(merged!!.body)
        assertEquals("Server note", prop(series, "DESCRIPTION"))
        assertEquals("Review", prop(series, "SUMMARY"))
    }
}
