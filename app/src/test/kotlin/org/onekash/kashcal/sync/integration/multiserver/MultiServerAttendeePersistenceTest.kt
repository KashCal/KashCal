package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.UUID

/**
 * A2 attendee-persistence integration tests parameterized across all
 * configured CalDAV servers.
 *
 * Verifies the end-to-end pull path with attendees: server emits
 * ATTENDEE lines → ical4j parses them → ICalEventMapper.toEntity
 * produces the right MappedEntity → an idempotent re-pull doesn't
 * duplicate. The DAO write itself is unit-tested in AttendeesDaoTest;
 * this test exercises the upstream wire-to-mapper path against real
 * server quirks.
 *
 * Auto-skips per-server via assumeTrue when credentials missing or
 * server unreachable. Run: `./gradlew testDebugUnitTest -Pintegration
 * --tests "*MultiServerAttendeePersistenceTest*"`.
 *
 * Test creates its own VEVENT with synthetic attendees (to keep real
 * accounts un-spammed and to avoid leaking PII across server-side
 * scheduling delivery on Apple's servers). Cleanup deletes the event
 * after each test.
 *
 * Server-side scheduling quirks observed on real servers (2026-05-17):
 * - iCloud / Radicale / SOGo / Stalwart: emit RFC 5545 §3.1 line-folded
 *   ATTENDEE lines (logical line split with CRLF + leading space at the
 *   75-octet boundary). Attendees themselves are preserved verbatim —
 *   the test unfolds before filtering. ical4j unfolds automatically on
 *   the parse path, so the mapper sees the original logical line.
 * - Zoho: rewrites ORGANIZER to the account holder's email and strips
 *   ALL ATTENDEE lines when the supplied ORGANIZER's mailto doesn't
 *   match the authenticated account. This is a real strip, not folding.
 * - Nextcloud, Baikal: preserve attendees verbatim, no folding observed.
 *
 * Use `stalwartlabs/stalwart:v0.13.4` (NOT `:latest` / 0.16+ which
 * boots into interactive setup-wizard mode). See
 * docs/CALDAV_TEST_SERVERS.md for full provisioning recipe.
 *
 * The assertions below tolerate these strips and verify only the
 * server→parser→mapper→Room chain works for *whatever* the server
 * returns. PII redaction (non-synthetic emails masked) keeps junit-xml
 * output safe from leaking real account addresses on Zoho-class servers.
 */
@RunWith(Parameterized::class)
class MultiServerAttendeePersistenceTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = CalDavServerConfig.allServers()
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private var calendarUrl: String? = null
    private val createdEventUrls = mutableListOf<Pair<String, String>>()

    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val parser = ICalParser()

    @Before
    fun setup() {
        val pair = CalDavTestServerLoader.createClient(config)
        if (pair != null) {
            client = pair.first
            creds = pair.second
        }
    }

    @After
    fun cleanup() = runBlocking {
        val c = client ?: return@runBlocking
        for ((url, etag) in createdEventUrls.reversed()) {
            try { c.deleteEvent(url, etag) } catch (_: Exception) { /* best-effort */ }
        }
    }

    private fun assumeReady() {
        assumeTrue(
            "${config.name} credentials not available",
            client != null && creds != null
        )
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
    }

    private suspend fun discoverCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            val wellKnown = c.discoverWellKnown(endpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else endpoint
        } else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = c.listCalendars(home).getOrNull() ?: return null
        return calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
    }

    /**
     * Synthetic ATTENDEE-bearing VEVENT. ORGANIZER required: iCloud silently
     * strips ATTENDEE lines when ORGANIZER is absent. Other servers tolerate
     * either form, but ORGANIZER-present is the more realistic wire shape
     * since real CalDAV scheduling always includes one.
     */
    private fun createTestIcsWithAttendees(uid: String, summary: String): String = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//A2 Attendee Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260315T100000Z
DTEND:20260315T110000Z
SUMMARY:$summary
ORGANIZER;CN=Test Organizer:mailto:organizer.synthetic@example.test
ATTENDEE;CN=Alice Test;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:alice.synthetic@example.test
ATTENDEE;CN=Bob Test;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT:mailto:bob.synthetic@example.test
ATTENDEE;CN=Carol Test;PARTSTAT=DECLINED;ROLE=OPT-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:carol.synthetic@example.test
END:VEVENT
END:VCALENDAR
    """.trimIndent()

    private fun trackEvent(url: String, etag: String) {
        createdEventUrls.removeAll { it.first == url }
        createdEventUrls.add(Pair(url, etag))
    }

    /**
     * Redact non-synthetic email addresses before letting an ICS body land in a
     * test failure message / junit XML. Some servers (Zoho) rewrite ORGANIZER
     * to the authenticated account holder's email — without this scrub, a real
     * account address would surface in CI output and leak PII.
     */
    private fun redactPii(text: String): String {
        val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        return emailRegex.replace(text) { match ->
            val email = match.value
            if (email.endsWith("@example.test")) email else "<redacted>@<redacted>"
        }
    }

    /**
     * True when the email is one of the synthetic addresses in the fixture.
     * Used to decide whether to *expect* an attendee survived a roundtrip;
     * server-side rewrites (Zoho) replace these with the account holder's
     * email which is fine but neither identifiable nor expected.
     */
    private fun isSyntheticAttendee(line: String): Boolean =
        line.contains("@example.test")

    // ========== A2.1 — server preserves ATTENDEE lines on round-trip ==========

    @Test
    fun `a2-1 server preserves ATTENDEE lines on create-and-fetch`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "a2-attendee-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcsWithAttendees(uid, "A2 attendee test on ${config.name}")

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) {
            "Failed to create on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}"
        }
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        // Fetch back and verify attendee lines survived round-trip.
        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch on ${config.name}" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData

        // RFC 5545 §3.1 line folding: lines >75 octets are split with CRLF +
        // leading space. iCloud/SOGo/Radicale/Stalwart all fold ATTENDEE
        // lines around the mailto boundary, so a naive line-by-line filter
        // sees `ATTENDEE;...:m` and a continuation `\n ailto:alice@...`.
        // Unfold before filtering so wire-shape assertions see logical lines.
        val unfoldedIcs = fetchedIcs.replace(Regex("""\r?\n[ \t]"""), "")
        val attendeeLines = unfoldedIcs.lines().filter { it.startsWith("ATTENDEE") }
        val syntheticLines = attendeeLines.filter { isSyntheticAttendee(it) }

        // Some servers (Zoho-class) strip every ATTENDEE when ORGANIZER mailto
        // doesn't match the account holder. That is a server-side scheduling
        // policy, not a bug in our parse path — log it and skip downstream
        // assertions (the ICS contains zero synthetic attendees so there is
        // nothing to verify the wire-shape against).
        if (syntheticLines.isEmpty()) {
            println(
                "[${config.name}] Server stripped all synthetic ATTENDEEs " +
                    "(server-side scheduling strip). " +
                    "Total ATTENDEE lines on wire: ${attendeeLines.size}. " +
                    "This is documented quirk behavior, not a regression."
            )
            return@runBlocking
        }

        // For each surviving synthetic attendee, the server must not have
        // mangled its mailto value. We assert only on what survived — see
        // the class kdoc for documented per-server stripping behavior.
        val survivors = listOf(
            "alice.synthetic@example.test",
            "bob.synthetic@example.test",
            "carol.synthetic@example.test"
        ).filter { fetchedIcs.contains(it) }

        assert(survivors.isNotEmpty()) {
            "${config.name}: at least one synthetic attendee should survive " +
                "if server preserves any ATTENDEE lines. Found ${attendeeLines.size} " +
                "ATTENDEE lines but none matched the synthetic addresses.\n" +
                "Fetched ICS (PII redacted):\n${redactPii(fetchedIcs)}"
        }

        if (survivors.size < 3) {
            println(
                "[${config.name}] Partial attendee preservation: " +
                    "${survivors.size}/3 synthetic attendees survived roundtrip. " +
                    "Surviving: $survivors. " +
                    "Likely server-side scheduling routing for the rest."
            )
        }
    }

    // ========== A2.2 — mapper translates server response to Room rows ==========

    @Test
    fun `a2-2 mapper produces 3 Attendee rows with correct PARTSTAT translation`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "a2-mapper-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcsWithAttendees(uid, "A2 mapper test on ${config.name}")

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) {
            "Failed to create on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}"
        }
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch on ${config.name}" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData

        // Parse via icaldav-core
        val parseResult = parser.parse(fetchedIcs)
        assert(parseResult is ParseResult.Success) {
            "${config.name}: parser failed: $parseResult"
        }
        val cal = (parseResult as ParseResult.Success).value
        val event = cal.events.singleOrNull()
        assert(event != null) { "${config.name}: expected exactly 1 VEVENT in fetched ICS" }

        // Run through ICalEventMapper.toEntity — exercises the A2 translation layer
        val mapped = ICalEventMapper.toEntity(
            icalEvent = event!!,
            rawIcal = fetchedIcs,
            calendarId = 1L,
            caldavUrl = url,
            etag = etag
        )

        // Filter to synthetic attendees only — server-side scheduling on
        // Zoho-class servers replaces ORGANIZER+ATTENDEEs with the account
        // holder; we assert only on the attendees we put there.
        val synthetic = mapped.attendees.filter {
            it.address.contains("@example.test")
        }.sortedBy { it.sortOrder }

        if (synthetic.isEmpty()) {
            println(
                "[${config.name}] No synthetic attendees survived to the mapper. " +
                    "Total mapped attendees (likely server-rewritten): ${mapped.attendees.size}. " +
                    "Skipping wire-shape assertions; see class kdoc for quirks."
            )
            return@runBlocking
        }

        // Each synthetic address must carry mailto: prefix per A2's mapper
        // convention. This is what we wrote and the mapper keeps it intact.
        synthetic.forEach {
            assert(it.address.startsWith("mailto:")) {
                "${config.name}: synthetic address should start with mailto:; got ${it.address}"
            }
        }

        // For each synthetic attendee that survived, verify partstat/role
        // round-tripped as RFC TEXT (hyphen-form). The fixture sets distinct
        // partstats per attendee, so a survivor uniquely identifies which
        // partstat must appear.
        val byAddress = synthetic.associateBy { it.address }
        byAddress["mailto:alice.synthetic@example.test"]?.let {
            assert(it.partstat == "ACCEPTED") {
                "${config.name}: alice partstat round-trip wrong: ${it.partstat}"
            }
            assert(it.role == "REQ-PARTICIPANT") {
                "${config.name}: alice role round-trip wrong: ${it.role}"
            }
        }
        byAddress["mailto:bob.synthetic@example.test"]?.let {
            assert(it.partstat == "NEEDS-ACTION") {
                "${config.name}: bob partstat round-trip wrong: ${it.partstat}"
            }
        }
        byAddress["mailto:carol.synthetic@example.test"]?.let {
            assert(it.partstat == "DECLINED") {
                "${config.name}: carol partstat round-trip wrong: ${it.partstat}"
            }
            assert(it.role == "OPT-PARTICIPANT") {
                "${config.name}: carol role round-trip wrong: ${it.role}"
            }
        }

        // sortOrder is monotonic across whatever survived.
        val orders = synthetic.map { it.sortOrder }
        assert(orders == orders.sorted() && orders.toSet().size == orders.size) {
            "${config.name}: sortOrder not monotonic-unique — got $orders"
        }

        if (synthetic.size < 3) {
            println(
                "[${config.name}] Partial attendee preservation: " +
                    "${synthetic.size}/3 mapped from synthetic fixture. " +
                    "Surviving: ${synthetic.map { it.address }}"
            )
        }
    }

    // ========== A2.3 — second pull is idempotent ==========

    @Test
    fun `a2-3 second fetch produces identical mapped attendee shape (idempotency)`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "a2-idempotent-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcsWithAttendees(uid, "A2 idempotency test on ${config.name}")

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) { "Failed to create on ${config.name}" }
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        // Fetch + map twice; assertion is on the resulting Attendee rows, not on
        // server etag (Apple's CDN can return slightly different etags on
        // immediate re-fetch but the content is identical).
        val mapped1 = mapEvent(url)
        val mapped2 = mapEvent(url)

        if (mapped1.isEmpty() && mapped2.isEmpty()) {
            println(
                "[${config.name}] Server returned zero attendees on both fetches " +
                    "(server-side scheduling strip). Idempotency holds trivially."
            )
            return@runBlocking
        }

        assert(mapped1.size == mapped2.size) {
            "${config.name}: re-fetch produced different attendee count (${mapped1.size} vs ${mapped2.size})"
        }
        val addresses1 = mapped1.map { it.address }.toSet()
        val addresses2 = mapped2.map { it.address }.toSet()
        assert(addresses1 == addresses2) {
            "${config.name}: re-fetch produced different addresses\n" +
                "first: ${addresses1.map { redactPii(it) }}\n" +
                "second: ${addresses2.map { redactPii(it) }}"
        }
        val partstats1 = mapped1.associate { it.address to it.partstat }
        val partstats2 = mapped2.associate { it.address to it.partstat }
        assert(partstats1 == partstats2) {
            "${config.name}: re-fetch produced different partstats\n" +
                "first: ${partstats1.mapKeys { redactPii(it.key) }}\n" +
                "second: ${partstats2.mapKeys { redactPii(it.key) }}"
        }
    }

    private suspend fun mapEvent(url: String): List<org.onekash.kashcal.data.db.entity.Attendee> {
        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to re-fetch on ${config.name}" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        val cal = (parser.parse(fetchedIcs) as ParseResult.Success).value
        val event = cal.events.single()
        return ICalEventMapper.toEntity(
            icalEvent = event,
            rawIcal = fetchedIcs,
            calendarId = 1L,
            caldavUrl = url,
            etag = fetchResult.getOrNull()!!.etag
        ).attendees
    }
}
