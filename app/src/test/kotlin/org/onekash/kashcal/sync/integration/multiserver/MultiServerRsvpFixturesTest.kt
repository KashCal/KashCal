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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Probes how each CalDAV server handles the RSVP write path and prints what it observed: the
 * PARTSTAT round trip, Schedule-Tag and Schedule-Status exposure, If-Schedule-Tag-Match
 * enforcement, stale-tag and stale-ETag rejection, the shape of a recurring RSVP, PARTSTAT case,
 * SEQUENCE handling and read-only enforcement on attendee edits. The tests fail only on outcomes
 * outside [OutcomeBands] (see [loudFailIfUnexpected]); the per-server data points are printed, not
 * asserted.
 *
 * `CalDavClient` exposes no `Schedule-Tag` or `Schedule-Status` response header and sends no
 * `If-Schedule-Tag-Match`, so the probes go through a raw OkHttp client ([rawHttp]).
 *
 * Run: `./gradlew :app:testDebugUnitTest -Pintegration --tests
 * '*MultiServerRsvpFixturesTest*'`. A server without credentials or unreachable at runtime skips
 * via `assumeTrue`.
 *
 * Non-`@example.test` addresses are masked ([redactPii]) before an ICS line reaches output, the
 * same pattern as `MultiServerAttendeePersistenceTest`.
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

        // iCalendar media type for PUT bodies (RFC 5545 §8.1).
        internal val ICAL_MEDIA_TYPE = "text/calendar; charset=utf-8".toMediaType()

        // Calendar URL per server. Parameterized builds a new instance per server and test, so
        // without this each of the 8 tests re-runs 3 chained PROPFINDs (about 2-3s each on iCloud).
        private val calendarUrlCache = ConcurrentHashMap<String, String>()
    }

    // Used only for calendar discovery and the cleanup in [cleanup]. Probe traffic goes through
    // [rawHttp], which can read Schedule-Tag, Schedule-Status and ETag and send
    // If-Schedule-Tag-Match.
    private var caldavClient: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private var calendarUrl: String? = null
    // Every URL this run sent a create to, recorded before the request goes out
    // so a lost reply is still cleaned up. Some servers answer a create with no
    // ETag and change it later, so the list holds URLs only.
    private val createdEventUrls = linkedSetOf<String>()

    /**
     * Non-2xx codes each probe accepts as a data point in [loudFailIfUnexpected]. A 5xx or 401
     * fails even if listed, and so does any other non-2xx code outside the band.
     */
    private object OutcomeBands {
        // Stale Schedule-Tag or ETag: 412, or 409 from a server that phrases it that way.
        val STALE_TAG = setOf(412, 409)
        // Attendee's substantive edit refused by server policy.
        val READ_ONLY_REJECT = setOf(403, 409, 422)
        // Malformed input refused, e.g. lowercase PARTSTAT.
        val MALFORMED_INPUT = setOf(400, 422)
        // Attendee PUT that doesn't bump SEQUENCE, refused by server policy.
        val SEQUENCE_POLICY_REJECT = setOf(400, 409, 422)
        // PUT with no precondition header: some servers accept it, others answer 412, 403 or 409.
        val NO_HEADER_PUT = setOf(412, 403, 409)
    }

    /**
     * Sends the probes: it can read `Schedule-Tag` and `Schedule-Status` and send
     * `If-Schedule-Tag-Match`, which `CalDavClient` can't.
     *
     * [setup] rebuilds it with the [DigestAuthenticator] the production client uses once
     * credentials are known. The preemptive Basic header on each request satisfies Basic-auth
     * servers; a Digest-only server (BaikalDigest) answers it with 401 and the authenticator
     * answers the challenge. Without the authenticator every write there 401s.
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
                .authenticator(DigestAuthenticator(pair.second.username, pair.second.password, allowCleartext = true))
                .build()
        }
    }

    @After
    fun cleanup() = runBlocking {
        val c = caldavClient ?: return@runBlocking
        for (url in createdEventUrls.reversed()) {
            try {
                // The server may have changed the ETag since this run last saw it,
                // so delete with a fresh one, and without one if that still fails.
                val deleted = c.deleteEvent(url, c.fetchEtag(url).getOrNull())
                if (!deleted.isSuccess()) c.deleteEvent(url, null)
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
        // Shared across this server's 8 tests; without it iCloud alone spends about 16-24s on
        // repeated discovery.
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

    /**
     * Returns the preemptive Basic header for [rawHttp], from the credentials [caldavClient] uses.
     */
    private fun authHeader(): String =
        Credentials.basic(creds!!.username, creds!!.password)

    /**
     * Masks every email address not ending in `@example.test` before ICS text reaches test output.
     * Some servers (Zoho) rewrite ORGANIZER to the account holder's address, which would otherwise
     * leak into CI output.
     */
    private fun redactPii(text: String): String {
        val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        return emailRegex.replace(text) { match ->
            val email = match.value
            if (email.endsWith("@example.test")) email else "<redacted>@<redacted>"
        }
    }

    /**
     * Prints the response code and any `ETag`, `Schedule-Tag` (RFC 6638 §8.2) and `Schedule-Status`
     * header. RFC 6638 defines no `Schedule-Status` header (delivery status is the SCHEDULE-STATUS
     * parameter, §7.3, with codes in §3.2.9), so this records whether a server sends one.
     */
    private fun observe(label: String, response: Response) {
        val sb = StringBuilder("[${config.name}] $label: code=${response.code}")
        listOf("ETag", "Schedule-Tag", "Schedule-Status").forEach { h ->
            response.header(h)?.let { sb.append(" $h=\"$it\"") }
        }
        println(sb.toString())
    }

    /**
     * Builds the RSVP fixture: ORGANIZER is the authenticated account (so iCloud's iSchedule
     * routing doesn't strip the ATTENDEEs), one ATTENDEE is the account with [partstat], and one is
     * an external `@example.test` attendee who has accepted.
     *
     * @param uid event UID; every test starts it with [UID_PREFIX]
     * @param partstat PARTSTAT on the authenticated account's ATTENDEE line
     * @param rrule RRULE for the recurring-event test, or no RRULE line when null
     * @param organizerOverride ORGANIZER address other than the account, for the read-only probe.
     *   Servers flagged [CalDavServerConfig.stripsAttendeesOnSyntheticOrganizer] strip the
     *   ATTENDEEs then, so that probe skips them.
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

    /** SUMMARY of every fixture; the substantive-edit probe replaces it with [MUTATED_SUMMARY]. */
    private val ORIGINAL_SUMMARY get() = "T2 RSVP fixture on ${config.name}"
    private val MUTATED_SUMMARY get() = "MUTATED-BY-ATTENDEE on ${config.name}"

    /**
     * PUTs [ics] through [rawHttp] with the given preconditions. A create (`If-None-Match: *`)
     * records [eventUrl] for [cleanup] before the request goes out.
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
        if (ifNoneMatch == "*") createdEventUrls += eventUrl
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

    /** Returns the resource URL for [uid] in the discovered calendar. */
    private fun newEventUrl(uid: String): String =
        calendarUrl!!.trimEnd('/') + "/" + uid + ".ics"

    /**
     * Throws an AssertionError for a 5xx, a 401 or any other non-2xx code outside [allowedCodes]:
     * those mean a broken fixture, bad credentials or a server regression, not a data point.
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

        // The create must succeed.
        val createIcs = buildRsvpFixture(uid, partstat = "NEEDS-ACTION", sequence = 0)
        val createResp = rawPut(url, createIcs, ifNoneMatch = "*")
        observe("create", createResp)
        loudFailIfUnexpected("create", createResp, allowedCodes = emptySet())
        val createEtag = createResp.header("ETag")?.trim('"')

        // Change the account's PARTSTAT to ACCEPTED and keep SEQUENCE:0: RFC 5546 §2.1.4 says a
        // REPLY must not increment SEQUENCE.
        val updateIcs = buildRsvpFixture(uid, partstat = "ACCEPTED", sequence = 0)
        val updateResp = rawPut(url, updateIcs, ifMatch = createEtag)
        observe("update-partstat", updateResp)
        loudFailIfUnexpected("update-partstat", updateResp, allowedCodes = emptySet())
        val updatedEtag = updateResp.header("ETag")?.trim('"') ?: createEtag

        // Read back and report whether PARTSTAT=ACCEPTED survived.
        val getResp = rawGet(url)
        observe("get-after-update", getResp)
        loudFailIfUnexpected("get-after-update", getResp, allowedCodes = emptySet())
        val body = getResp.body!!.string()
        val unfolded = body.replace(Regex("""\r?\n[ \t]"""), "")
        val selfAddr = creds!!.username
        val selfAttendeeLine = unfolded.lines()
            .firstOrNull { it.startsWith("ATTENDEE") && it.contains(selfAddr) }
        if (selfAttendeeLine == null) {
            // iCloud-class servers' iSchedule routing strips the account's own ATTENDEE when the
            // ORGANIZER mailto matches. Log and stop: this probe records behavior, it doesn't
            // enforce it.
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

        // Capture the headers on a PARTSTAT-change PUT, the path an RSVP takes.
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

        // Without a Schedule-Tag there is nothing to match against, so skip.
        assumeTrue(
            "${config.name} doesn't expose Schedule-Tag (skipping If-Schedule-Tag-Match probe)",
            scheduleTag != null
        )

        // (a) Stale tag: 412 or 409 if the server enforces it, 2xx if it doesn't.
        val staleTag = "stale-$scheduleTag"
        val staleResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "ACCEPTED"),
            ifScheduleTagMatch = staleTag
        )
        observe("update-stale-tag", staleResp)
        loudFailIfUnexpected("update-stale-tag", staleResp, allowedCodes = OutcomeBands.STALE_TAG)

        // (b) Current tag: must succeed.
        val currentResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "ACCEPTED"),
            ifScheduleTagMatch = scheduleTag
        )
        observe("update-current-tag", currentResp)
        loudFailIfUnexpected("update-current-tag", currentResp, allowedCodes = emptySet())
        val newEtag = currentResp.header("ETag")?.trim('"') ?: createEtag

        // (c) No precondition header: record what the server does.
        val noHeaderResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "TENTATIVE"),
            // intentionally no If-Match, no If-Schedule-Tag-Match
        )
        observe("update-no-header", noHeaderResp)
        loudFailIfUnexpected("update-no-header", noHeaderResp, allowedCodes = OutcomeBands.NO_HEADER_PUT)
        val finalEtag = noHeaderResp.header("ETag")?.trim('"') ?: newEtag

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

        // Both clients hold initialEtag. Client A succeeds first.
        val clientAResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "ACCEPTED"),
            ifMatch = initialEtag
        )
        observe("client-A-update", clientAResp)
        loudFailIfUnexpected("client-A-update", clientAResp, allowedCodes = emptySet())
        val afterAEtag = clientAResp.header("ETag")?.trim('"') ?: initialEtag

        // Client B sends the now-stale initialEtag: 412 or 409 if enforced, else it overwrites.
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

        val updateResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "ACCEPTED", rrule = "FREQ=WEEKLY;COUNT=3"),
            ifMatch = createEtag
        )
        observe("update-recurring-partstat", updateResp)
        loudFailIfUnexpected("update-recurring-partstat", updateResp, allowedCodes = emptySet())
        val updatedEtag = updateResp.header("ETag")?.trim('"') ?: createEtag

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

        // Uppercase ACCEPTED, the form RFC 5545 spells.
        val createResp = rawPut(url, buildRsvpFixture(uid, partstat = "ACCEPTED"), ifNoneMatch = "*")
        loudFailIfUnexpected("create-uppercase", createResp, allowedCodes = emptySet())
        val createEtag = createResp.header("ETag")?.trim('"')

        val getUpperResp = rawGet(url)
        loudFailIfUnexpected("get-uppercase", getUpperResp, allowedCodes = emptySet())
        val upperBody = getUpperResp.body!!.string()
        val upperPreserved = upperBody.contains("PARTSTAT=ACCEPTED")

        // Lowercase 'accepted': RFC 5545 §2 makes parameter values case-insensitive. Does the
        // server normalize it on write or store it verbatim?
        val lowerIcs = buildRsvpFixture(uid, partstat = "accepted")
        val lowerPutResp = rawPut(url, lowerIcs, ifMatch = createEtag)
        observe("update-lowercase", lowerPutResp)
        loudFailIfUnexpected(
            "update-lowercase",
            lowerPutResp,
            allowedCodes = OutcomeBands.MALFORMED_INPUT
        )
        val updateEtag = lowerPutResp.header("ETag")?.trim('"') ?: createEtag

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

        // RFC 5546 §2.1.4: SEQUENCE MUST NOT be incremented for a REPLY. A server may refuse the
        // PUT, increment SEQUENCE itself or store it as sent.
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
            val getResp = rawGet(url)
            loudFailIfUnexpected("get-no-bump", getResp, allowedCodes = emptySet())
            extractSequence(getResp.body!!.string())
        } else null

        // PUT with SEQUENCE:1, as an organizer's revision would.
        val etagForBump = nonBumpResp.header("ETag")?.trim('"') ?: createEtag
        val bumpResp = rawPut(
            url,
            buildRsvpFixture(uid, partstat = "TENTATIVE", sequence = 1),
            ifMatch = etagForBump
        )
        observe("update-bump", bumpResp)
        loudFailIfUnexpected("update-bump", bumpResp, allowedCodes = emptySet())
        val bumpEtag = bumpResp.header("ETag")?.trim('"') ?: etagForBump
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

        // A synthetic ORGANIZER makes the account an attendee. Servers whose iSchedule routing
        // then strips the ATTENDEEs (iCloud among them) skip: nothing is left to probe.
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

        // Substantive edit by an attendee: SUMMARY changes, PARTSTAT doesn't.
        val mutatedIcs = createIcs.replace(ORIGINAL_SUMMARY, MUTATED_SUMMARY)
        val mutateResp = rawPut(url, mutatedIcs, ifMatch = createEtag)
        observe("attendee-substantive-edit", mutateResp)
        loudFailIfUnexpected(
            "attendee-substantive-edit",
            mutateResp,
            allowedCodes = OutcomeBands.READ_ONLY_REJECT
        )
        val updateEtag = mutateResp.header("ETag")?.trim('"') ?: createEtag

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
