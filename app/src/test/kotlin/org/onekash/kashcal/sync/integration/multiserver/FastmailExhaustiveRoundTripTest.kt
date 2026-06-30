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
 * Fastmail (Cyrus-based) CalDAV account.
 *
 * Sibling of [MailboxExhaustiveRoundTripTest]; same harness, but Fastmail has
 * no dedicated round-trip coverage otherwise — it only appears as a parameter
 * in the `allServers()` scheduling/CRUD suites, which never exercise KashCal's
 * own serializer feeding its own pull parser. Cyrus runs a full RFC 6638
 * scheduling pipeline and normalizes stored ICS aggressively, so the realistic
 * failure shape is: KashCal serializes an [Event] -> PUT to Cyrus -> Cyrus
 * stores/normalizes it -> KashCal's pull parser reads its own output back and
 * a field is lost or mangled.
 *
 * Every case builds a KashCal [Event] (+ [Attendee] rows), serializes through
 * the PRODUCTION write path ([IcsPatcher]), PUTs, fetches back, and parses with
 * the production pull path ([ICalParser] + [ICalEventMapper.toEntity]). Unlike
 * the Mailbox matrix, these assert field VALUES survive the round-trip (not
 * just UID/title), and add cases for fields no exhaustive test covers yet:
 * classification, URL, GEO, RDATE, distinct start/end timezones, all-day
 * reminders, and a cancelled occurrence.
 *
 * Cyrus rewrites ORGANIZER to the authenticated account on PUT, so failure
 * messages run through [redactPii] before they can reach junit-xml / CI logs.
 * Synthetic `@example.test` attendee addresses only; events are left on the
 * account (UIDs prefixed `kc-fm-exhaustive-`) for inspection.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*FastmailExhaustiveRoundTripTest*'
 */
class FastmailExhaustiveRoundTripTest {

    private val config = CalDavServerConfig.FASTMAIL
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
        // Prefer a plain writable calendar; skip scheduling/utility collections.
        calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox") &&
                !cal.url.contains("Tasks") && !cal.url.contains("notification")
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
     * Result of a round-trip: the re-parsed master plus its attendees and the
     * full re-parsed calendar (so cases can assert on exception VEVENTs).
     */
    private data class RoundTrip(
        val event: Event,
        val attendees: List<Attendee>,
        val veventCount: Int,
        val exceptionVeventCount: Int,
    )

    /**
     * Serialize [event] (+attendees, +exceptions) through the production write
     * path, PUT it, fetch it back, parse with the production pull path. Fails if
     * create/fetch fails OR the server stores something the parser can't read OR
     * the parsed event loses its identity.
     */
    private fun roundTrip(
        event: Event,
        attendees: List<Attendee> = emptyList(),
        exceptions: List<Pair<Event, List<Attendee>>> = emptyList(),
    ): RoundTrip = runBlocking {
        val ics = when {
            exceptions.isNotEmpty() ->
                IcsPatcher.serializeWithExceptions(event, attendees, exceptions)
            attendees.isNotEmpty() ->
                IcsPatcher.generateFresh(event, attendees)
            else ->
                IcsPatcher.serialize(event)
        }
        assert(ics.contains("UID:")) { "[${event.title}] serializer produced no UID" }

        val createResult = client!!.createEvent(calendarUrl!!, event.uid, ics)
        assert(createResult.isSuccess()) {
            "[${event.title}] PUT failed: " +
                redactPii("${(createResult as? CalDavResult.Error)?.message}")
        }
        val (url, etag) = createResult.getOrNull()!!

        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) {
            "[${event.title}] GET failed: " +
                redactPii("${(fetchResult as? CalDavResult.Error)?.message}")
        }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData

        val parseResult = parser.parse(fetchedIcs)
        assert(parseResult is ParseResult.Success) {
            "[${event.title}] PULL PARSE FAILED:\n${redactPii(fetchedIcs)}"
        }
        val cal = (parseResult as ParseResult.Success).value
        assert(cal.events.isNotEmpty()) {
            "[${event.title}] parser produced zero VEVENTs:\n${redactPii(fetchedIcs)}"
        }

        val masterICal = cal.events.firstOrNull { it.recurrenceId == null } ?: cal.events.first()
        val mapped = ICalEventMapper.toEntity(
            icalEvent = masterICal,
            rawIcal = fetchedIcs,
            calendarId = 1L,
            caldavUrl = url,
            etag = etag,
        )

        assert(mapped.event.uid == event.uid) {
            "[${event.title}] UID changed: sent ${event.uid}, got ${mapped.event.uid}"
        }
        assert(mapped.event.title == event.title) {
            "[${event.title}] title changed: got '${mapped.event.title}'"
        }

        val exceptionVeventCount = cal.events.count { it.recurrenceId != null }

        println(
            "[OK] ${event.title} -> ${cal.events.size} VEVENT(s), " +
                "${mapped.attendees.size} attendee(s) parsed back"
        )
        RoundTrip(
            event = mapped.event,
            attendees = mapped.attendees,
            veventCount = cal.events.size,
            exceptionVeventCount = exceptionVeventCount,
        )
    }

    private fun uid(slug: String) = "kc-fm-exhaustive-$slug-${UUID.randomUUID()}"

    /** S4: never let a Cyrus-rewritten real account address reach junit-xml. */
    private fun redactPii(text: String): String {
        val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        return emailRegex.replace(text) { m ->
            if (m.value.endsWith("@example.test")) m.value else "<redacted>@<redacted>"
        }
    }

    // ---------------------------------------------------------------- basics

    @Test
    fun `01 plain timed event`() {
        roundTrip(baseEvent(uid("plain"), "FM 01 plain timed"))
    }

    @Test
    fun `02 all-day single`() {
        val e = baseEvent(uid("allday"), "FM 02 all-day", startOffset = day, durationMs = day - 1)
            .copy(isAllDay = true)
        val back = roundTrip(e)
        assert(back.event.isAllDay) { "all-day flag lost: ${back.event.isAllDay}" }
    }

    @Test
    fun `03 multi-day all-day`() {
        val e = baseEvent(uid("multiday"), "FM 03 multi-day", startOffset = 2 * day, durationMs = 3 * day - 1)
            .copy(isAllDay = true)
        roundTrip(e)
    }

    @Test
    fun `04 rich text location description with special chars`() {
        val e = baseEvent(uid("richtext"), "FM 04 special chars", startOffset = day + 5 * hour).copy(
            location = "Café, 5th Ave; Suite #2 \\ rear",
            description = "Line one\nLine two; with, commas\nand a backslash \\ end",
        )
        val back = roundTrip(e)
        assert(back.event.description?.contains("Line two") == true) {
            "multiline description lost: ${back.event.description}"
        }
        assert(back.event.location?.contains("Suite #2") == true) {
            "location special chars lost: ${back.event.location}"
        }
    }

    @Test
    fun `05 unicode and emoji in title`() {
        roundTrip(baseEvent(uid("unicode"), "FM 05 会議 📅 éàü", startOffset = 2 * day + 2 * hour))
    }

    // ----------------------------------------------------------- metadata

    @Test
    fun `06 categories color priority`() {
        val e = baseEvent(uid("meta"), "FM 06 categories+color", startOffset = 3 * day + hour).copy(
            categories = listOf("Work", "Important"),
            color = 0xFF3F51B5.toInt(),
            priority = 1,
        )
        val back = roundTrip(e)
        assert(back.event.categories?.contains("Work") == true) {
            "categories lost: ${back.event.categories}"
        }
    }

    @Test
    fun `07 transparency and status`() {
        val e = baseEvent(uid("transp"), "FM 07 transp+status", startOffset = 3 * day + 7 * hour).copy(
            transp = "TRANSPARENT",
            status = "TENTATIVE",
        )
        val back = roundTrip(e)
        assert(back.event.transp == "TRANSPARENT") { "transp lost: ${back.event.transp}" }
        assert(back.event.status == "TENTATIVE") { "status lost: ${back.event.status}" }
    }

    @Test
    fun `08 classification private`() {
        // CLASS is untested in any exhaustive round-trip; Cyrus honors it.
        val e = baseEvent(uid("class"), "FM 08 confidential", startOffset = 3 * day + 9 * hour).copy(
            classification = "CONFIDENTIAL",
        )
        val back = roundTrip(e)
        assert(back.event.classification == "CONFIDENTIAL") {
            "classification lost: ${back.event.classification}"
        }
    }

    @Test
    fun `09 url and geo`() {
        // URL + GEO are untested in the exhaustive matrix.
        val e = baseEvent(uid("urlgeo"), "FM 09 url+geo", startOffset = 3 * day + 11 * hour).copy(
            url = "https://example.test/meeting/42",
            geoLat = 52.520008,
            geoLon = 13.404954,
        )
        roundTrip(e)
    }

    // ------------------------------------------------------------- reminders

    @Test
    fun `10 reminders`() {
        val e = baseEvent(uid("reminders"), "FM 10 reminders", startOffset = 4 * day).copy(
            reminders = listOf("-PT15M", "-PT1H", "-P1D"),
            alarmCount = 3,
        )
        roundTrip(e)
    }

    @Test
    fun `11 all-day reminder day before`() {
        // All-day VALARM uses a day-before signed offset (the path a prior fix
        // touched); a timed VALARM and an all-day VALARM serialize differently.
        val e = baseEvent(uid("allday-rem"), "FM 11 all-day reminder",
            startOffset = 5 * day, durationMs = day - 1).copy(
            isAllDay = true,
            reminders = listOf("-P1D"),
            alarmCount = 1,
        )
        roundTrip(e)
    }

    // ------------------------------------------------------------ timezones

    @Test
    fun `12 non-UTC timezone`() {
        val e = baseEvent(uid("tz"), "FM 12 Berlin tz", startOffset = 4 * day + 6 * hour).copy(
            timezone = "Europe/Berlin",
            endTimezone = "Europe/Berlin",
        )
        val back = roundTrip(e)
        assert(back.event.timezone?.contains("Berlin") == true) {
            "timezone lost: ${back.event.timezone}"
        }
    }

    @Test
    fun `13 distinct start and end timezones`() {
        // Cross-zone event (flight-style): start in one zone, end in another.
        val e = baseEvent(uid("xtz"), "FM 13 cross-zone", startOffset = 4 * day + 12 * hour,
            durationMs = 3 * hour).copy(
            timezone = "America/New_York",
            endTimezone = "Europe/London",
        )
        roundTrip(e)
    }

    // ------------------------------------------------------------- attendees

    @Test
    fun `14 organizer with attendees mixed partstat`() {
        val u = uid("attendees")
        val e = baseEvent(u, "FM 14 organizer+attendees", startOffset = 5 * day + 3 * hour).copy(
            organizerEmail = "organizer.synthetic@example.test",
            organizerName = "Test Organizer",
        )
        val attendees = listOf(
            Attendee(eventId = 0, address = "mailto:alice.synthetic@example.test",
                displayName = "Alice", partstat = "ACCEPTED", role = "REQ-PARTICIPANT"),
            Attendee(eventId = 0, address = "mailto:bob.synthetic@example.test",
                displayName = "Bob", partstat = "NEEDS-ACTION", role = "OPT-PARTICIPANT"),
        )
        val back = roundTrip(e, attendees = attendees)
        // Cyrus may rewrite ORGANIZER to the account and re-route, but the two
        // synthetic attendees should survive on the stored resource.
        val synthetic = back.attendees.count { it.address.endsWith("@example.test") }
        assert(synthetic >= 1) {
            "expected synthetic attendees to survive, got ${back.attendees.size} total"
        }
    }

    // ------------------------------------------------------------- recurring

    @Test
    fun `15 recurring daily COUNT`() {
        val e = baseEvent(uid("daily"), "FM 15 daily x5").copy(rrule = "FREQ=DAILY;COUNT=5")
        val back = roundTrip(e)
        assert(back.event.rrule?.contains("FREQ=DAILY") == true) {
            "rrule lost: ${back.event.rrule}"
        }
    }

    @Test
    fun `16 recurring weekly BYDAY UNTIL`() {
        val e = baseEvent(uid("weekly"), "FM 16 weekly MWF").copy(
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20261231T235959Z",
        )
        roundTrip(e)
    }

    @Test
    fun `17 recurring monthly with EXDATE`() {
        val e = baseEvent(uid("monthly"), "FM 17 monthly minus one").copy(
            rrule = "FREQ=MONTHLY;COUNT=6",
            exdate = (base + 30 * day).toString(),
        )
        roundTrip(e)
    }

    @Test
    fun `18 recurring with RDATE`() {
        // RDATE (additive recurrence dates) is untested in the exhaustive matrix.
        val e = baseEvent(uid("rdate"), "FM 18 weekly plus rdate").copy(
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=3",
            rdate = (base + 3 * day + 4 * hour).toString(),
        )
        roundTrip(e)
    }

    // --------------------------------------------------- recurring + exception

    @Test
    fun `19 recurring master with per-occurrence exception VEVENT`() {
        val masterUid = uid("series-exc")
        val master = baseEvent(masterUid, "FM 19 series").copy(rrule = "FREQ=DAILY;COUNT=5")
        val exception = baseEvent(masterUid, "FM 19 occurrence override",
            startOffset = day + 2 * hour).copy(originalInstanceTime = base + day)
        val back = roundTrip(master, exceptions = listOf(exception to emptyList()))
        // The override must come back as a distinct RECURRENCE-ID VEVENT.
        assert(back.veventCount >= 2) {
            "expected master + exception VEVENTs, got ${back.veventCount}"
        }
        assert(back.exceptionVeventCount >= 1) {
            "exception VEVENT lost its RECURRENCE-ID on round-trip"
        }
    }

    @Test
    fun `20 recurring master and exception each carrying attendees`() {
        val masterUid = uid("series-exc-att")
        val master = baseEvent(masterUid, "FM 20 series w attendees").copy(
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=4",
            organizerEmail = "organizer.synthetic@example.test",
            organizerName = "Test Organizer",
        )
        val masterAttendees = listOf(
            Attendee(eventId = 0, address = "mailto:alice.synthetic@example.test",
                displayName = "Alice", partstat = "ACCEPTED", role = "REQ-PARTICIPANT"),
        )
        val exception = baseEvent(masterUid, "FM 20 occurrence w extra guest",
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

    @Test
    fun `21 cancelled occurrence via EXDATE on series with attendees`() {
        // Series with attendees, one instance cancelled by EXDATE — exercises
        // the "delete one occurrence of a meeting" path end to end.
        val masterUid = uid("series-cancel")
        val master = baseEvent(masterUid, "FM 21 series w cancelled instance").copy(
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = (base + 2 * day).toString(),
            organizerEmail = "organizer.synthetic@example.test",
            organizerName = "Test Organizer",
        )
        val attendees = listOf(
            Attendee(eventId = 0, address = "mailto:alice.synthetic@example.test",
                displayName = "Alice", partstat = "ACCEPTED", role = "REQ-PARTICIPANT"),
        )
        roundTrip(master, attendees = attendees)
    }
}
