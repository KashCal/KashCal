package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.DigestAuthenticator
import org.onekash.kashcal.sync.client.model.CalDavResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * T2-fixtures sprint: parameterized integration tests that capture how each
 * supported CalDAV server handles the RSVP write path. Output is the matrix
 * of observed behavior (Schedule-Tag exposure, If-Schedule-Tag-Match
 * enforcement, 412 on stale tags, recurring-RSVP shape, SEQUENCE handling,
 * server-side read-only enforcement). T2 (RSVP write path) plan-review
 * reads the resulting findings doc to design the 412-retry path and
 * `If-Schedule-Tag-Match` wiring against real per-server behavior rather
 * than against the spec alone.
 *
 * The production `CalDavClient` interface doesn't yet expose `Schedule-Tag`
 * / `Schedule-Status` response headers or the `If-Schedule-Tag-Match`
 * request header. This test uses a raw OkHttp client to probe those
 * directly; T2's plan decides which subset production needs.
 *
 * Run: `./gradlew :app:testDebugUnitTest -Pintegration --tests
 * '*MultiServerRsvpFixturesTest*'`. Servers unreachable at runtime skip
 * via `assumeTrue`; minimum-acceptance coverage for T2 plan-review is
 * iCloud + 3 Docker servers.
 *
 * PII redaction follows the same pattern as `MultiServerAttendeePersistenceTest`:
 * non-`@example.test` email addresses are masked before any test failure
 * assertion message reaches junit-xml output.
 */
@RunWith(Parameterized::class)
class MultiServerRsvpFixturesTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = CalDavServerConfig.allServers()

        private val classStartMs = System.currentTimeMillis()
        internal val UID_PREFIX = "t2fix-$classStartMs-"

        // RFC 5545 §8.1 — iCalendar media type for PUT bodies.
        internal val ICAL_MEDIA_TYPE = "text/calendar; charset=utf-8".toMediaType()

        // Per-server calendar URL cache. JUnit Parameterized constructs a
        // new instance per (server × test) pair; without this cache each
        // test re-runs 3 chained PROPFINDs (~2-3s on iCloud × 8 tests).
        private val calendarUrlCache = ConcurrentHashMap<String, String>()
    }

    // CalDavClient is used only for PROPFIND-based calendar discovery and
    // for the per-test deleteEvent cleanup. All RSVP probe traffic uses
    // [rawHttp] directly so we can read Schedule-Tag / Schedule-Status /
    // ETag and set If-Schedule-Tag-Match — none of which the production
    // interface exposes today.
    private var caldavClient: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private var calendarUrl: String? = null
    private val createdEventUrls = mutableListOf<Pair<String, String>>()

    /**
     * Outcome bands for [loudFailIfUnexpected]. Each test passes the band
     * matching its expected data-point outcomes; anything outside the band
     * (5xx, 401, 4xx not in the band) is loud-fail.
     */
    private object OutcomeBands {
        // Stale-tag PUT: 412 (server enforces) or 409 (alternate phrasing).
        val STALE_TAG = setOf(412, 409)
        // Read-only-mode reject (server-enforced policy denial).
        val READ_ONLY_REJECT = setOf(403, 409, 422)
        // Server-side malformed-input rejection (e.g. lowercase PARTSTAT).
        val MALFORMED_INPUT = setOf(400, 422)
        // Server-side policy reject for SEQUENCE non-bump on attendee PUT.
        val SEQUENCE_POLICY_REJECT = setOf(400, 409, 422)
        // No-header PUT race — some servers accept regardless, others 412/403/409.
        val NO_HEADER_PUT = setOf(412, 403, 409)
    }

    /**
     * Raw OkHttp client used for header-level probing of `Schedule-Tag`,
     * `Schedule-Status`, and `If-Schedule-Tag-Match`. The production
     * `CalDavClient` interface doesn't expose these (yet); this is the
     * fixture-collection escape hatch.
     *
     * Built in [setup] once credentials are known so it can carry the same
     * [DigestAuthenticator] the production client uses. The preemptive Basic
     * header on each request satisfies Basic-auth servers directly; on a
     * Digest-only server (BaikalDigest) that header is rejected with 401 and
     * the authenticator answers the challenge — without it, every write 401s.
     */
    private var rawHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setup() {
        val pair = CalDavTestServerLoader.createClient(config)
        if (pair != null) {
            caldavClient = pair.first
            creds = pair.second
            rawHttp = rawHttp.newBuilder()
                .authenticator(DigestAuthenticator(pair.second.username, pair.second.password))
                .build()
        }
    }

    @After
    fun cleanup() = runBlocking {
        val c = caldavClient ?: return@runBlocking
        for ((url, etag) in createdEventUrls.reversed()) {
            try {
                c.deleteEvent(url, etag)
            } catch (_: Exception) {
                // best-effort
            }
        }
    }

    private fun assumeReady() {
        assumeTrue(
            "${config.name} credentials not available",
            caldavClient != null && creds != null
        )
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
    }

    private suspend fun discoverCalendar(): String? {
        // Reuse a previously-discovered calendar URL across the 8 tests
        // for this server. Each test gets a fresh JUnit instance, so
        // without the cache `setup()` would re-run 3 chained PROPFINDs
        // per test — ~16-24s of redundant network on iCloud alone.
        calendarUrlCache[config.name]?.let { return it }

        val c = caldavClient!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            val wellKnown = c.discoverWellKnown(endpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else endpoint
        } else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = c.listCalendars(home).getOrNull() ?: return null
        val url = calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
        if (url != null) calendarUrlCache[config.name] = url
        return url
    }

    private fun trackEvent(url: String, etag: String) {
        createdEventUrls.removeAll { it.first == url }
        createdEventUrls.add(Pair(url, etag))
    }

    /**
     * Auth header for the raw OkHttp client. Reuses the same Basic-auth
     * credentials the production `CalDavClient` factory uses internally.
     */
    private fun authHeader(): String =
        Credentials.basic(creds!!.username, creds!!.password)

    /**
     * Redact non-synthetic email addresses before letting an ICS body land in
     * a test failure message / junit XML. Some servers (Zoho) rewrite
     * ORGANIZER to the authenticated account holder's email — without this
     * scrub, the real account address would surface in CI output and leak PII.
     */
    private fun redactPii(text: String): String {
        val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        return emailRegex.replace(text) { match ->
            val email = match.value
            if (email.endsWith("@example.test")) email else "<redacted>@<redacted>"
        }
    }

    /**
     * RFC 6638 §6.1 — server-emitted iTIP delivery status. `2.0;Success`
     * means the REPLY was queued for delivery; `5.x;...` means the server
     * couldn't route it. Header absence on a 2xx PUT means the server
     * doesn't implement RFC 6638 schedule responses (or doesn't surface them).
     */
    private fun observe(label: String, response: Response) {
        val sb = StringBuilder("[${config.name}] $label: code=${response.code}")
        listOf("ETag", "Schedule-Tag", "Schedule-Status").forEach { h ->
            response.header(h)?.let { sb.append(" $h=\"$it\"") }
        }
        println(sb.toString())
    }

    /**
     * Build the standard RSVP fixture: ORGANIZER mailto matches the
     * authenticated account (so iCloud's iSchedule routing doesn't strip
     * ATTENDEEs — see A2 documented quirk in CALDAV_TEST_SERVERS.md), one
     * ATTENDEE matching the auth account with PARTSTAT=NEEDS-ACTION, one
     * external synthetic ATTENDEE.
     *
     * @param uid event UID; must start with [UID_PREFIX] for the sweep
     * @param partstat PARTSTAT for the authenticated user's ATTENDEE row
     * @param sequence SEQUENCE value for the VEVENT
     * @param rrule optional RRULE for recurring-event tests
     * @param organizerOverride when set, ORGANIZER mailto != auth account
     *   (used by the read-only-mode probe; iSchedule-routing servers
     *   strip ATTENDEEs in this case so they skip via config flag)
     */
    private fun buildRsvpFixture(
        uid: String,
        partstat: String = "NEEDS-ACTION",
        sequence: Int = 0,
        rrule: String? = null,
        organizerOverride: String? = null
    ): String {
        val organizer = organizerOverride ?: creds!!.username
        val rruleLine = rrule?.let { "RRULE:$it\n" } ?: ""
        return """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//T2 RSVP Fixture//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(Date())}
DTSTART:20260615T100000Z
DTEND:20260615T110000Z
SUMMARY:$ORIGINAL_SUMMARY
SEQUENCE:$sequence
ORGANIZER;CN=Self Organizer:mailto:$organizer
ATTENDEE;CN=Self Attendee;PARTSTAT=$partstat;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:${creds!!.username}
ATTENDEE;CN=External;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:external.synthetic@example.test
${rruleLine}END:VEVENT
END:VCALENDAR
        """.trimIndent()
    }

    /** Constant SUMMARY in the fixture; mutation tests `.replace` against this. */
    private val ORIGINAL_SUMMARY get() = "T2 RSVP fixture on ${config.name}"
    private val MUTATED_SUMMARY get() = "MUTATED-BY-ATTENDEE on ${config.name}"

    /**
     * Raw OkHttp PUT — bypasses production CalDavClient so we can inspect
     * Schedule-Tag and Schedule-Status response headers directly.
     */
    private fun rawPut(
        eventUrl: String,
        ics: String,
        ifMatch: String? = null,
        ifNoneMatch: String? = null,
        ifScheduleTagMatch: String? = null
    ): Response {
        val builder = Request.Builder()
            .url(eventUrl)
            .header("Authorization", authHeader())
            .put(ics.toRequestBody(ICAL_MEDIA_TYPE))
        ifMatch?.let { builder.header("If-Match", "\"$it\"") }
        ifNoneMatch?.let { builder.header("If-None-Match", it) }
        ifScheduleTagMatch?.let { builder.header("If-Schedule-Tag-Match", "\"$it\"") }
        return rawHttp.newCall(builder.build()).execute()
    }

    private fun rawGet(eventUrl: String): Response {
        val req = Request.Builder()
            .url(eventUrl)
            .header("Authorization", authHeader())
            .get()
            .build()
        return rawHttp.newCall(req).execute()
    }

    /** PUT to a fresh URL within the discovered calendar. */
    private fun newEventUrl(uid: String): String =
        calendarUrl!!.trimEnd('/') + "/" + uid + ".ics"

    /**
     * Loud-fail guards. Throw an AssertionError for outcomes that indicate
     * a broken test fixture or unrelated server regression — NOT for
     * documented data-point quirks.
     */
    private fun loudFailIfUnexpected(label: String, response: Response, allowedCodes: Set<Int>) {
        val code = response.code
        if (code in 500..599) {
            throw AssertionError(
                "[${config.name}] $label: server returned 5xx ($code). " +
                    "This is a server failure, not a documented quirk."
            )
        }
        if (code == 401) {
            throw AssertionError(
                "[${config.name}] $label: 401 unauthenticated. " +
                    "Credentials problem in local.properties, not a quirk."
            )
        }
        if (code !in allowedCodes && code !in 200..299) {
            throw AssertionError(
                "[${config.name}] $label: unexpected code $code " +
                    "(expected one of: 2xx or $allowedCodes). " +
                    "If this is a documented server policy, add it to allowedCodes."
            )
        }
    }

    @Test
    fun `rsvp_partstat_roundtrip - server preserves PARTSTAT change on PUT-then-GET`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "${UID_PREFIX}roundtrip-${config.name.lowercase()}-${UUID.randomUUID()}"
        val url = newEventUrl(uid)

        // First PUT — must succeed (test fixture creates the event).
        val createIcs = buildRsvpFixture(uid, partstat = "NEEDS-ACTION", sequence = 0)
        val createResp = rawPut(url, createIcs, ifNoneMatch = "*")
        observe("create", createResp)
        loudFailIfUnexpected("create", createResp, allowedCodes = emptySet())
        val createEtag = createResp.header("ETag")?.trim('"')
        if (createEtag != null) trackEvent(url, createEtag)

        // Second PUT — change self's PARTSTAT to ACCEPTED, keep SEQUENCE:0
        // (RFC 5546 §2.1.4 — attendee PARTSTAT-only change shouldn't bump).
        val updateIcs = buildRsvpFixture(uid, partstat = "ACCEPTED", sequence = 0)
        val updateResp = rawPut(url, updateIcs, ifMatch = createEtag)
        observe("update-partstat", updateResp)
        loudFailIfUnexpected("update-partstat", updateResp, allowedCodes = emptySet())
        val updatedEtag = updateResp.header("ETag")?.trim('"') ?: createEtag
        if (updatedEtag != null) trackEvent(url, updatedEtag)

        // GET and inspect — PARTSTAT must be observable as ACCEPTED.
        val getResp = rawGet(url)
        observe("get-after-update", getResp)
        loudFailIfUnexpected("get-after-update", getResp, allowedCodes = emptySet())
        val body = getResp.body!!.string()
        val unfolded = body.replace(Regex("""\r?\n[ \t]"""), "")
        val selfAddr = creds!!.username
        val selfAttendeeLine = unfolded.lines()
            .firstOrNull { it.startsWith("ATTENDEE") && it.contains(selfAddr) }
        if (selfAttendeeLine == null) {
            // Documented quirk on iCloud-class servers (iSchedule routing
            // strips self-as-attendee when ORGANIZER mailto matches). Log
            // and skip — fixture sprint records, doesn't enforce.
            println(
                "[${config.name}] rsvp_partstat_roundtrip: self ATTENDEE row " +
                    "absent on GET (server-side iTIP routing). Documented quirk."
            )
            return@runBlocking
        }
        val partstatPresent = selfAttendeeLine.contains("PARTSTAT=ACCEPTED", ignoreCase = true)
        println(
            "[${config.name}] rsvp_partstat_roundtrip: " +
                if (partstatPresent) "PARTSTAT=ACCEPTED preserved" else "PARTSTAT mismatch — " +
                    redactPii(selfAttendeeLine)
        )
    }

    @Test
    fun `rsvp_schedule_tag_and_status_provided - record optional RFC 6638 headers`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "${UID_PREFIX}schedtag-${config.name.lowercase()}-${UUID.randomUUID()}"
        val url = newEventUrl(uid)

        val createResp = rawPut(url, buildRsvpFixture(uid), ifNoneMatch = "*")
        observe("create", createResp)
        loudFailIfUnexpected("create", createResp, allowedCodes = emptySet())
        val createEtag = createResp.header("ETag")?.trim('"')
        val createScheduleTag = createResp.header("Schedule-Tag")?.trim('"')
        val createScheduleStatus = createResp.header("Schedule-Status")
        if (createEtag != null) trackEvent(url, createEtag)

        // Update to capture headers on a PARTSTAT-change PUT (the actual T2 path).
        val updateResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "ACCEPTED"),
            ifMatch = createEtag
        )
        observe("update-partstat", updateResp)
        loudFailIfUnexpected("update-partstat", updateResp, allowedCodes = emptySet())
        val updateScheduleTag = updateResp.header("Schedule-Tag")?.trim('"')
        val updateScheduleStatus = updateResp.header("Schedule-Status")
        val updateEtag = updateResp.header("ETag")?.trim('"') ?: createEtag
        if (updateEtag != null) trackEvent(url, updateEtag)

        println(
            "[${config.name}] rsvp_schedule_tag_and_status_provided: " +
                "create.scheduleTag=${createScheduleTag ?: "<absent>"} " +
                "create.scheduleStatus=${createScheduleStatus ?: "<absent>"} " +
                "update.scheduleTag=${updateScheduleTag ?: "<absent>"} " +
                "update.scheduleStatus=${updateScheduleStatus ?: "<absent>"}"
        )
    }

    @Test
    fun `rsvp_if_schedule_tag_match_honored - does server enforce on stale tag`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "${UID_PREFIX}schedtagmatch-${config.name.lowercase()}-${UUID.randomUUID()}"
        val url = newEventUrl(uid)

        val createResp = rawPut(url, buildRsvpFixture(uid), ifNoneMatch = "*")
        loudFailIfUnexpected("create", createResp, allowedCodes = emptySet())
        observe("create", createResp)
        val scheduleTag = createResp.header("Schedule-Tag")?.trim('"')
        val createEtag = createResp.header("ETag")?.trim('"')
        if (createEtag != null) trackEvent(url, createEtag)

        // Skip if server doesn't expose Schedule-Tag — graceful degradation.
        assumeTrue(
            "${config.name} doesn't expose Schedule-Tag (skipping If-Schedule-Tag-Match probe)",
            scheduleTag != null
        )

        // (a) Stale tag — expect 412 if server enforces, 2xx if forgiving.
        val staleTag = "stale-$scheduleTag"
        val staleResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "ACCEPTED"),
            ifScheduleTagMatch = staleTag
        )
        observe("update-stale-tag", staleResp)
        loudFailIfUnexpected("update-stale-tag", staleResp, allowedCodes = OutcomeBands.STALE_TAG)

        // (b) Current tag — must succeed.
        val currentResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "ACCEPTED"),
            ifScheduleTagMatch = scheduleTag
        )
        observe("update-current-tag", currentResp)
        loudFailIfUnexpected("update-current-tag", currentResp, allowedCodes = emptySet())
        val newEtag = currentResp.header("ETag")?.trim('"') ?: createEtag
        if (newEtag != null) trackEvent(url, newEtag)

        // (c) No header at all — record what server does.
        val noHeaderResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "TENTATIVE"),
            // intentionally no If-Match, no If-Schedule-Tag-Match
        )
        observe("update-no-header", noHeaderResp)
        loudFailIfUnexpected("update-no-header", noHeaderResp, allowedCodes = OutcomeBands.NO_HEADER_PUT)
        val finalEtag = noHeaderResp.header("ETag")?.trim('"') ?: newEtag
        if (finalEtag != null) trackEvent(url, finalEtag)

        println(
            "[${config.name}] rsvp_if_schedule_tag_match_honored: " +
                "stale=${staleResp.code} current=${currentResp.code} no-header=${noHeaderResp.code}"
        )
    }

    @Test
    fun `rsvp_412_on_concurrent_edit - does server detect overlapping ETag-based PUTs`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "${UID_PREFIX}concurrent-${config.name.lowercase()}-${UUID.randomUUID()}"
        val url = newEventUrl(uid)

        // Initial create.
        val createResp = rawPut(url, buildRsvpFixture(uid), ifNoneMatch = "*")
        loudFailIfUnexpected("create", createResp, allowedCodes = emptySet())
        val initialEtag = createResp.header("ETag")?.trim('"')
        if (initialEtag != null) trackEvent(url, initialEtag)

        // Both clients hold initialEtag. Client A succeeds first.
        val clientAResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "ACCEPTED"),
            ifMatch = initialEtag
        )
        observe("client-A-update", clientAResp)
        loudFailIfUnexpected("client-A-update", clientAResp, allowedCodes = emptySet())
        val afterAEtag = clientAResp.header("ETag")?.trim('"') ?: initialEtag
        if (afterAEtag != null) trackEvent(url, afterAEtag)

        // Client B uses the now-stale initialEtag. Either 412 or silent overwrite.
        val clientBResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "DECLINED"),
            ifMatch = initialEtag
        )
        observe("client-B-update-stale", clientBResp)
        loudFailIfUnexpected(
            "client-B-update-stale",
            clientBResp,
            allowedCodes = OutcomeBands.STALE_TAG
        )

        println(
            "[${config.name}] rsvp_412_on_concurrent_edit: " +
                "clientA=${clientAResp.code} clientB-stale=${clientBResp.code} " +
                if (clientBResp.code == 412) "(server enforced If-Match)" else "(server allowed silent overwrite)"
        )
    }

    @Test
    fun `rsvp_recurring_series_level - does server preserve series-level PARTSTAT`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "${UID_PREFIX}recurring-${config.name.lowercase()}-${UUID.randomUUID()}"
        val url = newEventUrl(uid)

        val createIcs = buildRsvpFixture(
            uid,
            partstat = "NEEDS-ACTION",
            rrule = "FREQ=WEEKLY;COUNT=3"
        )
        val createResp = rawPut(url, createIcs, ifNoneMatch = "*")
        observe("create-recurring", createResp)
        loudFailIfUnexpected("create-recurring", createResp, allowedCodes = emptySet())
        val createEtag = createResp.header("ETag")?.trim('"')
        if (createEtag != null) trackEvent(url, createEtag)

        val updateResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "ACCEPTED", rrule = "FREQ=WEEKLY;COUNT=3"),
            ifMatch = createEtag
        )
        observe("update-recurring-partstat", updateResp)
        loudFailIfUnexpected("update-recurring-partstat", updateResp, allowedCodes = emptySet())
        val updatedEtag = updateResp.header("ETag")?.trim('"') ?: createEtag
        if (updatedEtag != null) trackEvent(url, updatedEtag)

        val getResp = rawGet(url)
        loudFailIfUnexpected("get-recurring", getResp, allowedCodes = emptySet())
        val body = getResp.body!!.string()
        val veventCount = Regex("BEGIN:VEVENT").findAll(body).count()
        val hasRrule = body.contains("RRULE:")
        val hasRecurrenceId = body.contains("RECURRENCE-ID")
        println(
            "[${config.name}] rsvp_recurring_series_level: " +
                "veventCount=$veventCount hasRrule=$hasRrule hasRecurrenceId=$hasRecurrenceId " +
                if (veventCount == 1 && hasRrule && !hasRecurrenceId) "(series-level preserved)"
                else "(server expanded into per-instance shape)"
        )
    }

    @Test
    fun `rsvp_partstat_normalization - does server normalize PARTSTAT case`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "${UID_PREFIX}normalize-${config.name.lowercase()}-${UUID.randomUUID()}"
        val url = newEventUrl(uid)

        // Uppercase ACCEPTED — RFC-canonical form.
        val createResp = rawPut(url, buildRsvpFixture(uid, partstat = "ACCEPTED"), ifNoneMatch = "*")
        loudFailIfUnexpected("create-uppercase", createResp, allowedCodes = emptySet())
        val createEtag = createResp.header("ETag")?.trim('"')
        if (createEtag != null) trackEvent(url, createEtag)

        val getUpperResp = rawGet(url)
        loudFailIfUnexpected("get-uppercase", getUpperResp, allowedCodes = emptySet())
        val upperBody = getUpperResp.body!!.string()
        val upperPreserved = upperBody.contains("PARTSTAT=ACCEPTED")

        // Lowercase 'accepted' — RFC 5545 says case-insensitive on read,
        // but does the server normalize on write or pass through verbatim?
        val lowerIcs = buildRsvpFixture(uid, partstat = "accepted")
        val lowerPutResp = rawPut(url, lowerIcs, ifMatch = createEtag)
        observe("update-lowercase", lowerPutResp)
        loudFailIfUnexpected(
            "update-lowercase",
            lowerPutResp,
            allowedCodes = OutcomeBands.MALFORMED_INPUT
        )
        val updateEtag = lowerPutResp.header("ETag")?.trim('"') ?: createEtag
        if (updateEtag != null) trackEvent(url, updateEtag)

        val lowerOnWire = if (lowerPutResp.isSuccessful) {
            val getLowerResp = rawGet(url)
            loudFailIfUnexpected("get-lowercase", getLowerResp, allowedCodes = emptySet())
            getLowerResp.body!!.string().let {
                when {
                    it.contains("PARTSTAT=ACCEPTED") -> "uppercase (server normalized)"
                    it.contains("PARTSTAT=accepted") -> "lowercase (server passed through verbatim)"
                    else -> "neither (server may have stripped or rewritten attendee)"
                }
            }
        } else {
            "PUT rejected with ${lowerPutResp.code}"
        }

        println(
            "[${config.name}] rsvp_partstat_normalization: " +
                "uppercase-preserved=$upperPreserved lowercase-result=$lowerOnWire"
        )
    }

    @Test
    fun `rsvp_sequence_handling - does server tolerate attendee SEQUENCE non-bump`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "${UID_PREFIX}sequence-${config.name.lowercase()}-${UUID.randomUUID()}"
        val url = newEventUrl(uid)

        // Create at SEQUENCE:0
        val createResp = rawPut(url, buildRsvpFixture(uid, sequence = 0), ifNoneMatch = "*")
        loudFailIfUnexpected("create", createResp, allowedCodes = emptySet())
        val createEtag = createResp.header("ETag")?.trim('"')
        if (createEtag != null) trackEvent(url, createEtag)

        // RFC 5546 §2.1.4: attendee PARTSTAT change MUST NOT bump SEQUENCE.
        // Some servers may reject; some may auto-increment; some may pass through.
        val nonBumpResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "ACCEPTED", sequence = 0),
            ifMatch = createEtag
        )
        observe("update-no-bump", nonBumpResp)
        loudFailIfUnexpected(
            "update-no-bump",
            nonBumpResp,
            allowedCodes = OutcomeBands.SEQUENCE_POLICY_REJECT
        )

        val nonBumpResultSequence = if (nonBumpResp.isSuccessful) {
            val etagAfter = nonBumpResp.header("ETag")?.trim('"') ?: createEtag
            if (etagAfter != null) trackEvent(url, etagAfter)
            val getResp = rawGet(url)
            loudFailIfUnexpected("get-no-bump", getResp, allowedCodes = emptySet())
            extractSequence(getResp.body!!.string())
        } else null

        // Now PUT with explicit SEQUENCE:1 (organizer-side increment).
        val etagForBump = nonBumpResp.header("ETag")?.trim('"') ?: createEtag
        val bumpResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "TENTATIVE", sequence = 1),
            ifMatch = etagForBump
        )
        observe("update-bump", bumpResp)
        loudFailIfUnexpected("update-bump", bumpResp, allowedCodes = emptySet())
        val bumpEtag = bumpResp.header("ETag")?.trim('"') ?: etagForBump
        if (bumpEtag != null) trackEvent(url, bumpEtag)
        val getBumpResp = rawGet(url)
        loudFailIfUnexpected("get-bump", getBumpResp, allowedCodes = emptySet())
        val bumpResultSequence = extractSequence(getBumpResp.body!!.string())

        println(
            "[${config.name}] rsvp_sequence_handling: " +
                "non-bump.code=${nonBumpResp.code} non-bump.sequence=$nonBumpResultSequence " +
                "bump.code=${bumpResp.code} bump.sequence=$bumpResultSequence"
        )
    }

    @Test
    fun `rsvp_attendee_substantive_edit - does server enforce read-only-mode on attendees`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        // Use a synthetic ORGANIZER (not the auth account) — making the
        // authenticated user an attendee, not the organizer. iCloud may
        // strip ATTENDEEs via iSchedule routing in this case (documented
        // A2 quirk); skip the test on iCloud rather than fail.
        assumeFalse(
            "${config.name} strips ATTENDEEs when ORGANIZER mailto != auth account (iSchedule routing)",
            config.stripsAttendeesOnSyntheticOrganizer
        )

        val uid = "${UID_PREFIX}readonly-${config.name.lowercase()}-${UUID.randomUUID()}"
        val url = newEventUrl(uid)

        val createIcs = buildRsvpFixture(
            uid,
            partstat = "ACCEPTED",
            organizerOverride = "external.organizer.synthetic@example.test"
        )
        val createResp = rawPut(url, createIcs, ifNoneMatch = "*")
        loudFailIfUnexpected("create", createResp, allowedCodes = emptySet())
        val createEtag = createResp.header("ETag")?.trim('"')
        if (createEtag != null) trackEvent(url, createEtag)

        // Substantive edit by an attendee (non-PARTSTAT change to SUMMARY).
        val mutatedIcs = createIcs.replace(ORIGINAL_SUMMARY, MUTATED_SUMMARY)
        val mutateResp = rawPut(url, mutatedIcs, ifMatch = createEtag)
        observe("attendee-substantive-edit", mutateResp)
        loudFailIfUnexpected(
            "attendee-substantive-edit",
            mutateResp,
            allowedCodes = OutcomeBands.READ_ONLY_REJECT
        )
        val updateEtag = mutateResp.header("ETag")?.trim('"') ?: createEtag
        if (updateEtag != null) trackEvent(url, updateEtag)

        val finalState = if (mutateResp.isSuccessful) {
            val getResp = rawGet(url)
            loudFailIfUnexpected("get-after-mutate", getResp, allowedCodes = emptySet())
            val body = getResp.body!!.string()
            when {
                body.contains(MUTATED_SUMMARY) -> "accepted (server didn't enforce read-only)"
                body.contains(ORIGINAL_SUMMARY) -> "silently-ignored (server kept original SUMMARY)"
                else -> "rewritten (server replaced SUMMARY with neither original nor mutated)"
            }
        } else {
            "rejected (${mutateResp.code})"
        }
        println(
            "[${config.name}] rsvp_attendee_substantive_edit: $finalState"
        )
    }

    private fun extractSequence(ics: String): Int? {
        val unfolded = ics.replace(Regex("""\r?\n[ \t]"""), "")
        val match = Regex("""(?m)^SEQUENCE:(\d+)""").find(unfolded)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
}
