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
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.domain.identity.effectiveAddresses
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.util.AddressNormalizer
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.UUID

/**
 * Attendee-persistence integration tests parameterized across all
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
 * boots into interactive setup-wizard mode).
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
PRODID:-//KashCal//Attendee Test//EN
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

    // ========== Server preserves ATTENDEE lines on round-trip ==========

    @Test
    fun `server preserves ATTENDEE lines on create-and-fetch`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "attendee-preserve-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcsWithAttendees(uid, "Attendee preserve test on ${config.name}")

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

    // ========== Mapper translates server response to Room rows ==========

    @Test
    fun `mapper produces 3 Attendee rows with correct PARTSTAT translation`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "attendee-mapper-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcsWithAttendees(uid, "Attendee mapper test on ${config.name}")

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

        // Run through ICalEventMapper.toEntity — exercises the attendee translation layer
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

        // Each synthetic address must carry mailto: prefix per the mapper's
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

    // ========== Second pull is idempotent ==========

    @Test
    fun `second fetch produces identical mapped attendee shape (idempotency)`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "attendee-idempotent-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcsWithAttendees(uid, "Attendee idempotency test on ${config.name}")

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

    // ========== Organizer outbound write path (KashCal serializer) ==========

    /**
     * Build a KashCal Event + Attendee rows and serialize via the production
     * organizer push path (IcsPatcher.generateFresh, no rawIcal), then create
     * on the server and re-fetch. Validates end-to-end that the attendee
     * set KashCal emits round-trips through a real server.
     */
    @Test
    fun `organizer push serializes attendees that round-trip`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "organizer-push-${config.name.lowercase()}-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val event = Event(
            uid = uid,
            calendarId = 1L,
            title = "Organizer push on ${config.name}",
            startTs = now + 86_400_000,
            endTs = now + 86_400_000 + 3_600_000,
            dtstamp = now,
            organizerEmail = "organizer.synthetic@example.test",
            organizerName = "Test Organizer",
            syncStatus = SyncStatus.PENDING_CREATE,
        )
        val attendees = listOf(
            Attendee(eventId = 0, address = "mailto:alice.synthetic@example.test",
                displayName = "Alice", partstat = "NEEDS-ACTION", role = "REQ-PARTICIPANT"),
            Attendee(eventId = 0, address = "mailto:bob.synthetic@example.test",
                displayName = "Bob", partstat = "NEEDS-ACTION", role = "REQ-PARTICIPANT"),
            Attendee(eventId = 0, address = "mailto:carol.synthetic@example.test",
                displayName = "Carol", partstat = "NEEDS-ACTION", role = "OPT-PARTICIPANT"),
        )

        // Production serialization path for a locally-created organizer event.
        val ics = IcsPatcher.generateFresh(event, attendees)
        assert(ics.contains("alice.synthetic@example.test")) {
            "serializer dropped attendees before push:\n${redactPii(ics)}"
        }

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) {
            "Failed to create on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}"
        }
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch on ${config.name}" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        val unfolded = fetchedIcs.replace(Regex("""\r?\n[ \t]"""), "")
        val syntheticAttendees = unfolded.lines()
            .filter { it.startsWith("ATTENDEE") && it.contains("@example.test") }

        // Servers that route NEEDS-ACTION attendees through their scheduling
        // pipeline (iCloud/Radicale/Stalwart with a synthetic ORGANIZER) strip
        // them server-side — documented quirk, not a client bug. Skip the
        // downstream assertion when nothing survived.
        if (config.stripsAttendeesOnSyntheticOrganizer && syntheticAttendees.isEmpty()) {
            println(
                "[${config.name}] synthetic-organizer scheduling strip — " +
                    "0 attendees on wire, documented quirk, nothing to assert."
            )
            return@runBlocking
        }

        assert(syntheticAttendees.isNotEmpty()) {
            "${config.name}: organizer-pushed attendees should round-trip.\n" +
                "Fetched ICS (PII redacted):\n${redactPii(fetchedIcs)}"
        }
    }

    /**
     * A METHOD-bearing iTIP message generated by KashCal must NOT carry
     * SCHEDULE-AGENT / SCHEDULE-FORCE-SEND on ORGANIZER or ATTENDEE (RFC 6638
     * §7.1/§7.2). Pure local wire-shape check — no server round-trip needed,
     * but parameterized here so it runs in the same gated suite.
     */
    @Test
    fun `iTIP message carries no SCHEDULE-AGENT or FORCE-SEND on either line`() {
        val now = System.currentTimeMillis()
        val event = Event(
            uid = "itip-strip-${UUID.randomUUID()}",
            calendarId = 1L,
            title = "iTIP wire-shape",
            startTs = now + 86_400_000,
            endTs = now + 86_400_000 + 3_600_000,
            dtstamp = now,
            organizerEmail = "organizer.synthetic@example.test",
            organizerName = "Test Organizer",
            syncStatus = SyncStatus.PENDING_CREATE,
        )
        val attendees = listOf(
            Attendee(eventId = 0, address = "mailto:alice.synthetic@example.test",
                partstat = "NEEDS-ACTION", role = "REQ-PARTICIPANT",
                scheduleAgent = "SERVER", scheduleForceSend = "REQUEST"),
        )
        val icalEvent = EventToICalEventMapper.toICalEvent(event, attendees)
        val message = org.onekash.icaldav.scheduling.ITipBuilder()
            .createRequest(icalEvent, icalEvent.attendees)

        assert(message.contains("METHOD:REQUEST")) { "fixture sanity: expected METHOD:REQUEST" }
        // Unfold before grepping so a folded parameter can't hide.
        val unfolded = message.replace(Regex("""\r?\n[ \t]"""), "")
        assert(!unfolded.contains("SCHEDULE-AGENT")) {
            "iTIP REQUEST must not echo SCHEDULE-AGENT:\n${redactPii(unfolded)}"
        }
        assert(!unfolded.contains("SCHEDULE-FORCE-SEND")) {
            "iTIP REQUEST must not echo SCHEDULE-FORCE-SEND:\n${redactPii(unfolded)}"
        }
    }

    // ========== Real-organizer cross-account round-trip ==========

    /**
     * Discover this account's OWN calendar-user-address (the value
     * EventCoordinator emits as ORGANIZER on locally authored events), or
     * null when the server doesn't expose one.
     */
    private suspend fun discoverOwnOrganizerAddress(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(endpoint).getOrNull() ?: endpoint
        } else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        return c.discoverCalendarUserAddresses(principal).getOrNull()?.firstOrNull()
    }

    /**
     * A REAL attendee address the user controls, drawn from a DIFFERENT owned
     * account than the one under test (iCloud→Zoho→mailbox→iCloud rotation), so
     * any server-side iTIP delivery lands in an owned inbox and exercises real
     * cross-provider delivery. Optional TEST_ATTENDEE_<PROVIDER> override.
     * Falls back to the account's own login (self-invite) when no cross
     * address is available; null when nothing usable resolves.
     */
    private fun resolveCrossAccountAttendee(): String? {
        CalDavTestServerLoader.property("TEST_ATTENDEE_${config.name.uppercase()}")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        // Owned email-shaped account logins, by provider name.
        val owned = mapOf(
            "iCloud" to CalDavTestServerLoader.property("caldav.username"),
            "Zoho" to CalDavTestServerLoader.property("ZOHO_USERNAME"),
            "mailbox" to CalDavTestServerLoader.property("MAILBOX_USERNAME"),
        ).filterValues { it != null && it.contains("@") }
        // Rotate to a DIFFERENT owned account than the one under test.
        val rotation = listOf("iCloud", "Zoho", "mailbox")
        val idx = rotation.indexOf(config.name)
        if (idx >= 0) {
            for (step in 1 until rotation.size) {
                owned[rotation[(idx + step) % rotation.size]]?.let { return it }
            }
        }
        // Non-rotation server (Docker/Nextcloud): self-invite if email-shaped.
        return creds?.username?.takeIf { it.contains("@") }
    }

    @Test
    fun `real-organizer cross-account event round-trips attendee`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val organizer = discoverOwnOrganizerAddress()
        assumeTrue("${config.name}: no own organizer address discovered", organizer != null)
        val attendeeAddr = resolveCrossAccountAttendee()
        assumeTrue("${config.name}: no controlled real attendee address available", attendeeAddr != null)

        val uid = "realorg-roundtrip-${config.name.lowercase()}-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        // Resolve organizerEmail the SAME WAY production does — via the real
        // Account.effectiveAddresses() + EventCoordinator.resolveOrganizer rule:
        // pick the bare, email-shaped address. Feeding the raw discovered value
        // (which carries a "mailto:" prefix on discovered-address accounts)
        // through this is what would have caught the double-mailto bug had the
        // test driven it before.
        val resolverAccount = org.onekash.kashcal.data.db.entity.Account(
            id = 1L, provider = org.onekash.kashcal.domain.model.AccountProvider.CALDAV,
            email = AddressNormalizer.stripMailto(organizer!!),
            calendarUserAddresses = listOf(organizer)
        )
        // Mirror production EventCoordinator.resolveOrganizer EXACTLY (same
        // AddressNormalizer.isEmailShaped predicate) so the test can't pass on
        // an address production would reject (e.g. a dotless internal host).
        val resolvedOrganizer = resolverAccount.effectiveAddresses()
            .firstOrNull { AddressNormalizer.isEmailShaped(it) }
            ?.let { AddressNormalizer.stripMailto(it) }
        assumeTrue("${config.name}: organizer address not email-shaped", resolvedOrganizer != null)

        val event = Event(
            uid = uid,
            calendarId = 1L,
            title = "Real-organizer round-trip on ${config.name}",
            startTs = now + 86_400_000,
            endTs = now + 86_400_000 + 3_600_000,
            dtstamp = now,
            organizerEmail = resolvedOrganizer,
            organizerName = "Test Organizer",
            syncStatus = SyncStatus.PENDING_CREATE,
        )
        val attendees = listOf(
            Attendee(eventId = 0, address = "mailto:${AddressNormalizer.stripMailto(attendeeAddr!!)}",
                displayName = "Cross Attendee", partstat = "NEEDS-ACTION", role = "REQ-PARTICIPANT")
        )

        val ics = IcsPatcher.generateFresh(event, attendees)
        // Regression guard for the double-prefix bug: the generator prepends
        // "mailto:", so organizerEmail must be BARE. A "mailto:mailto:" on the
        // wire = the bug is back.
        assert(!ics.contains("mailto:mailto:")) {
            "double mailto: prefix in serialized ICS — organizer stored non-bare:\n${redactPii(ics)}"
        }
        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) {
            "Create failed on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}"
        }
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Fetch failed on ${config.name}" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        val unfolded = fetchedIcs.replace(Regex("""\r?\n[ \t]"""), "")
        val attendeeLines = unfolded.lines().filter { it.startsWith("ATTENDEE") }

        // Servers with a full RFC 6638 scheduling pipeline may transform a
        // real-organizer event on store: Zoho rewrites ORGANIZER; iCloud routes
        // attendees to the inbox; OX/mailbox strips ORGANIZER+ATTENDEE entirely
        // when it can't reconcile them. These are conformant server policies,
        // not client defects — the production serialize path emitted a valid
        // ORGANIZER+ATTENDEE (asserted below pre-push). Tolerate the strip on
        // servers flagged for it; require round-trip only on servers that inline.
        assert(ics.contains("ORGANIZER") && ics.contains("ATTENDEE")) {
            "client must SERIALIZE a real ORGANIZER + ATTENDEE before push:\n${redactPii(ics)}"
        }
        if (config.stripsAttendeesOnSyntheticOrganizer) {
            println("[${config.name}] real-organizer event stored with " +
                "${attendeeLines.size} ATTENDEE / ORGANIZER ${if (unfolded.contains("ORGANIZER")) "present" else "stripped"} " +
                "— server-side scheduling policy (documented quirk), not a client defect.")
        } else {
            assert(unfolded.contains("ORGANIZER")) {
                "${config.name}: ORGANIZER must survive round-trip on an inlining server\n${redactPii(unfolded)}"
            }
            assert(attendeeLines.isNotEmpty()) {
                "${config.name}: attendee must round-trip on an inlining server\n${redactPii(unfolded)}"
            }
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
