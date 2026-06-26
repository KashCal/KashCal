package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import java.util.UUID

/**
 * Exhaustive serialize -> PUT -> GET -> parse round-trip against a real
 * mailbox.org (Open-Xchange / OX App Suite) account.
 *
 * Why this test exists: a user reported pull breaking on mailbox.org on a
 * recent build. The pull-path source is byte-identical across the suspect
 * releases, and the clean MultiServerCalDavWorkflowTest (which PUTs raw,
 * hand-written ICS) passes. That test never exercises KashCal's OWN
 * serializer. The realistic failure shape is:
 *
 *   KashCal serializes an event entity -> PUT to OX -> OX stores/normalizes
 *   it -> KashCal's pull parser chokes reading its own output back.
 *
 * So every case here builds a KashCal [Event] (+ [Attendee] rows), runs it
 * through the PRODUCTION write path ([IcsPatcher.serialize] /
 * [IcsPatcher.generateFresh] / [IcsPatcher.serializeWithExceptions] — the
 * same calls PushStrategy makes), PUTs to OX, fetches it back, and parses
 * with the production pull path ([ICalParser] + [ICalEventMapper.toEntity]).
 * A case fails if the server stores something our own parser can't read or
 * that loses the event's identifying semantics.
 *
 * Covers a broad matrix: plain timed, all-day, multi-day, location/
 * description with special chars + newlines, organizer + attendees,
 * categories, color, priority, reminders, transparency/status, recurring
 * masters (daily/weekly/monthly, COUNT + UNTIL), and recurring series with
 * a per-occurrence exception VEVENT (the path that previously dropped
 * exception attendees).
 *
 * Events are intentionally LEFT on the account (no cleanup) so they can be
 * inspected in any client. UIDs are prefixed `kc-exhaustive-` for easy
 * identification. Synthetic `@example.test` addresses only.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MailboxExhaustiveRoundTripTest*'
 */
class MailboxExhaustiveRoundTripTest {

    private val config = CalDavServerConfig.MAILBOX
    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private var calendarUrl: String? = null
    private val parser = ICalParser()

    // Fixed base instant so DTSTART/DTEND are deterministic across a run.
    // 2026-06-08 09:00 UTC (a Monday — useful for BYDAY=MO weekly cases).
    private val base = 1_780_909_200_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    @Before
    fun setup() {
        val pair = CalDavTestServerLoader.createClient(config)
        if (pair != null) {
            client = pair.first
            creds = pair.second
        }
        assumeTrue(
            "${config.name} credentials not available",
            client != null && creds != null
        )
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)
    }

    private fun discoverCalendar(): String? = runBlocking {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val principal = c.discoverPrincipal(endpoint).getOrNull() ?: return@runBlocking null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull()
            ?: return@runBlocking null
        val calendars = c.listCalendars(home).getOrNull() ?: return@runBlocking null
        calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox") &&
                !cal.url.contains("Birthdays") && !cal.url.contains("Tasks")
        }?.url ?: calendars.firstOrNull {
            !it.url.contains("inbox") && !it.url.contains("outbox")
        }?.url
    }

    private fun baseEvent(
        uid: String,
        title: String,
        startOffset: Long = 0,
        durationMs: Long = hour,
    ) = Event(
        uid = uid,
        calendarId = 1L,
        title = title,
        startTs = base + startOffset,
        endTs = base + startOffset + durationMs,
        dtstamp = base,
        timezone = "UTC",
        syncStatus = SyncStatus.PENDING_CREATE,
    )

    /**
     * The core assertion: serialize [event] (+attendees, +exceptions) through
     * the production write path, PUT it, fetch it back, and parse with the
     * production pull path. Fails if create/fetch fails OR the server stores
     * something the parser can't read OR the parsed event loses its UID/title.
     *
     * Returns the re-parsed master Event so individual cases can assert
     * field-level round-trip semantics.
     */
    private fun roundTrip(
        event: Event,
        attendees: List<Attendee> = emptyList(),
        exceptions: List<Pair<Event, List<Attendee>>> = emptyList(),
    ): Event = runBlocking {
        val ics = when {
            exceptions.isNotEmpty() ->
                IcsPatcher.serializeWithExceptions(event, attendees, exceptions)
            attendees.isNotEmpty() ->
                IcsPatcher.generateFresh(event, attendees)
            else ->
                IcsPatcher.serialize(event)
        }

        // Sanity: our serializer must at least emit the UID + summary we asked for.
        assert(ics.contains("UID:")) { "[${event.title}] serializer produced no UID:\n$ics" }

        val createResult = client!!.createEvent(calendarUrl!!, event.uid, ics)
        assert(createResult.isSuccess()) {
            "[${event.title}] PUT failed: " +
                "${(createResult as? CalDavResult.Error)?.message}\n--- ICS we sent ---\n$ics"
        }
        val (url, etag) = createResult.getOrNull()!!

        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) {
            "[${event.title}] GET failed: ${(fetchResult as? CalDavResult.Error)?.message}"
        }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData

        // PRODUCTION PULL PATH — this is what "pull failing" means.
        val parseResult = parser.parse(fetchedIcs)
        assert(parseResult is ParseResult.Success) {
            "[${event.title}] PULL PARSE FAILED on server-stored body: $parseResult\n" +
                "--- server returned ---\n$fetchedIcs"
        }
        val cal = (parseResult as ParseResult.Success).value
        assert(cal.events.isNotEmpty()) {
            "[${event.title}] parser produced zero VEVENTs from:\n$fetchedIcs"
        }

        // Map the master (the non-exception VEVENT, i.e. no RECURRENCE-ID).
        val masterICal = cal.events.firstOrNull { it.recurrenceId == null } ?: cal.events.first()
        val mapped = ICalEventMapper.toEntity(
            icalEvent = masterICal,
            rawIcal = fetchedIcs,
            calendarId = 1L,
            caldavUrl = url,
            etag = etag,
        )

        // Identity must survive the round-trip — the bare minimum for "pull works".
        assert(mapped.event.uid == event.uid) {
            "[${event.title}] UID changed across round-trip: " +
                "sent ${event.uid}, got ${mapped.event.uid}"
        }
        assert(mapped.event.title == event.title) {
            "[${event.title}] title changed across round-trip: got '${mapped.event.title}'"
        }

        println(
            "[OK] ${event.title} -> ${cal.events.size} VEVENT(s), " +
                "${mapped.attendees.size} attendee(s) parsed back"
        )
        mapped.event
    }

    private fun uid(slug: String) = "kc-exhaustive-$slug-${UUID.randomUUID()}"

    // ---------------------------------------------------------------- basics

    @Test
    fun `01 plain timed event`() {
        // Mon 09:00
        roundTrip(baseEvent(uid("plain"), "Exhaustive 01 plain timed"))
    }

    @Test
    fun `02 all-day single`() {
        // Tue (all-day)
        val e = baseEvent(uid("allday"), "Exhaustive 02 all-day",
            startOffset = day, durationMs = day - 1)
            .copy(isAllDay = true)
        roundTrip(e)
    }

    @Test
    fun `03 multi-day all-day`() {
        // Wed -> Fri (all-day, 3 days)
        val e = baseEvent(uid("multiday"), "Exhaustive 03 multi-day",
            startOffset = 2 * day, durationMs = 3 * day - 1)
            .copy(isAllDay = true)
        roundTrip(e)
    }

    @Test
    fun `04 rich text location description with special chars`() {
        // Tue 14:00
        val e = baseEvent(uid("richtext"), "Exhaustive 04 special chars",
            startOffset = day + 5 * hour).copy(
            location = "Café, 5th Ave; Suite #2 \\ rear",
            description = "Line one\nLine two; with, commas\nand a backslash \\ end",
        )
        val back = roundTrip(e)
        assert(back.description?.contains("Line two") == true) {
            "multiline description lost: ${back.description}"
        }
    }

    @Test
    fun `05 unicode and emoji in title`() {
        // Wed 11:00
        roundTrip(baseEvent(uid("unicode"), "Exhaustive 05 会議 📅 éàü",
            startOffset = 2 * day + 2 * hour))
    }

    @Test
    fun `06 categories color priority`() {
        // Thu 10:00
        val e = baseEvent(uid("meta"), "Exhaustive 06 categories+color",
            startOffset = 3 * day + hour).copy(
            categories = listOf("Work", "Important"),
            color = 0xFF3F51B5.toInt(),
            priority = 1,
        )
        roundTrip(e)
    }

    @Test
    fun `07 transparency and status`() {
        // Thu 16:00
        val e = baseEvent(uid("transp"), "Exhaustive 07 transp+status",
            startOffset = 3 * day + 7 * hour).copy(
            transp = "TRANSPARENT",
            status = "TENTATIVE",
        )
        roundTrip(e)
    }

    @Test
    fun `08 reminders`() {
        // Fri 09:00
        val e = baseEvent(uid("reminders"), "Exhaustive 08 reminders",
            startOffset = 4 * day).copy(
            reminders = listOf("-PT15M", "-PT1H", "-P1D"),
            alarmCount = 3,
        )
        roundTrip(e)
    }

    @Test
    fun `09 non-UTC timezone`() {
        // Fri 15:00
        val e = baseEvent(uid("tz"), "Exhaustive 09 Berlin tz",
            startOffset = 4 * day + 6 * hour).copy(
            timezone = "Europe/Berlin",
            endTimezone = "Europe/Berlin",
        )
        roundTrip(e)
    }

    // ------------------------------------------------------------- attendees

    @Test
    fun `10 organizer with attendees`() {
        // Sat 12:00
        val u = uid("attendees")
        val e = baseEvent(u, "Exhaustive 10 organizer+attendees",
            startOffset = 5 * day + 3 * hour).copy(
            organizerEmail = "organizer.synthetic@example.test",
            organizerName = "Test Organizer",
        )
        val attendees = listOf(
            Attendee(eventId = 0, address = "mailto:alice.synthetic@example.test",
                displayName = "Alice", partstat = "ACCEPTED", role = "REQ-PARTICIPANT"),
            Attendee(eventId = 0, address = "mailto:bob.synthetic@example.test",
                displayName = "Bob", partstat = "NEEDS-ACTION", role = "OPT-PARTICIPANT"),
        )
        roundTrip(e, attendees = attendees)
    }

    // ------------------------------------------------------------- recurring

    @Test
    fun `11 recurring daily COUNT`() {
        val e = baseEvent(uid("daily"), "Exhaustive 11 daily x5").copy(
            rrule = "FREQ=DAILY;COUNT=5",
        )
        roundTrip(e)
    }

    @Test
    fun `12 recurring weekly BYDAY UNTIL`() {
        val e = baseEvent(uid("weekly"), "Exhaustive 12 weekly MWF").copy(
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20261231T235959Z",
        )
        roundTrip(e)
    }

    @Test
    fun `13 recurring monthly with EXDATE`() {
        val e = baseEvent(uid("monthly"), "Exhaustive 13 monthly minus one").copy(
            rrule = "FREQ=MONTHLY;COUNT=6",
            exdate = (base + 30 * day).toString(),
        )
        roundTrip(e)
    }

    // --------------------------------------------------- recurring + exception

    @Test
    fun `14 recurring master with per-occurrence exception VEVENT`() {
        val masterUid = uid("series-exc")
        val master = baseEvent(masterUid, "Exhaustive 14 series").copy(
            rrule = "FREQ=DAILY;COUNT=5",
        )
        // Second occurrence (base + 1 day) moved +2h and retitled — a real
        // RECURRENCE-ID exception VEVENT sharing the master UID.
        val exception = baseEvent(masterUid, "Exhaustive 14 occurrence override",
            startOffset = day + 2 * hour).copy(
            originalInstanceTime = base + day,
        )
        roundTrip(master, exceptions = listOf(exception to emptyList()))
    }

    @Test
    fun `15 recurring master and exception each carrying attendees`() {
        val masterUid = uid("series-exc-att")
        val master = baseEvent(masterUid, "Exhaustive 15 series w attendees").copy(
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=4",
            organizerEmail = "organizer.synthetic@example.test",
            organizerName = "Test Organizer",
        )
        val masterAttendees = listOf(
            Attendee(eventId = 0, address = "mailto:alice.synthetic@example.test",
                displayName = "Alice", partstat = "ACCEPTED", role = "REQ-PARTICIPANT"),
        )
        val exception = baseEvent(masterUid, "Exhaustive 15 occurrence w extra guest",
            startOffset = 7 * day + hour).copy(
            originalInstanceTime = base + 7 * day,
            organizerEmail = "organizer.synthetic@example.test",
            organizerName = "Test Organizer",
        )
        val exceptionAttendees = listOf(
            Attendee(eventId = 0, address = "mailto:alice.synthetic@example.test",
                displayName = "Alice", partstat = "ACCEPTED", role = "REQ-PARTICIPANT"),
            Attendee(eventId = 0, address = "mailto:dave.synthetic@example.test",
                displayName = "Dave", partstat = "NEEDS-ACTION", role = "REQ-PARTICIPANT"),
        )
        roundTrip(
            master,
            attendees = masterAttendees,
            exceptions = listOf(exception to exceptionAttendees),
        )
    }
}
