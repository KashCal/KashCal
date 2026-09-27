package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Probes each server in [CalDavServerConfig.allServers]: when an event is PUT in the server-side
 * scheduling wire format (matched ORGANIZER + one ATTENDEE, no METHOD, the shape PushStrategy
 * PUTs), does the server take ownership of delivering the invite, or does it store the attendee
 * inertly and expect the client to send the iTIP itself?
 *
 * The app sends through the scheduling outbox only where the read-back after the PUT shows the
 * server won't deliver (`PushStrategy.maybeSendViaOutbox`), so whether a plain PUT invites the
 * attendee is a per-server property the app depends on. This pins the observed disposition so a
 * change is caught in either direction: a server counted on for delivery silently stopping, or a
 * server that needs client iTIP starting to auto-deliver.
 *
 * Classification: only a positive delivery signal counts as "the server delivers". Keeping the
 * ATTENDEE line is not such a signal: a server can store the attendee verbatim and still never
 * email anyone (observed: Mailbox/OX, which has a scheduling outbox but does nothing on a plain
 * PUT).
 *  - SCHEDULE-AGENT=CLIENT on the stored ATTENDEE (RFC 6638 §7.1) -> the server explicitly
 *    declines; the client must send the iTIP. (Zoho.)
 *  - SCHEDULE-STATUS=N.N stamped on the stored ATTENDEE (RFC 6638 §7.3) -> the server took
 *    ownership and reported a delivery result (1.x = sent/queued, 5.x = it tried but the
 *    recipient was undeliverable, which is expected for the @example.test recipient). Positive
 *    signal.
 *  - The ATTENDEE was routed out of the stored resource (the invitee line is gone although the
 *    organizer self-attendee may remain) -> the server's scheduling pipeline took it. Positive
 *    signal. (iCloud-class behavior.)
 *  - ATTENDEE kept verbatim, no SCHEDULE-STATUS, no SCHEDULE-AGENT=CLIENT -> no positive signal.
 *    The server stored it but gave no evidence it will deliver. If the principal advertises a
 *    schedule-outbox-URL, delivery almost certainly requires an explicit outbox POST (client
 *    iTIP). This counts as NEEDS_CLIENT_ITIP, not as server-schedules.
 *  - The app emits no ORGANIZER -> the account's `effectiveAddresses` hold no email-shaped
 *    address (an address set with none, or an empty set and a login that isn't email-shaped),
 *    so `resolveOrganizer` produces nothing and there is nothing to schedule. An app-side
 *    limit, not a server stance. (Bare Radicale or Xandikos test containers with no email.)
 *    The event form's isSchedulable gate keys off the same condition, so the UI blocks adding
 *    an attendee here; the probe exercises the wire path below that gate.
 *
 * Not relied on: Schedule-Tag is absent on a PUT response across this entire fleet, including
 * servers that do schedule (iCloud). So Schedule-Tag isn't a usable delivery signal here;
 * SCHEDULE-STATUS is.
 *
 * Disposition is asserted per server; unreachable, no-credential and can't-inspect cases are
 * skipped, not failed. Each run prints the observed disposition and the evidence
 * (SCHEDULE-STATUS, SCHEDULE-AGENT or routed-out), so when a server's behavior shifts the
 * failure message shows the new signal.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*ServerSideSchedulingProbeTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ServerSideSchedulingProbeTest(
    private val config: CalDavServerConfig
) {
    /** What a server does with a matched-organizer plain PUT. */
    enum class Disposition {
        /** Server stamps SCHEDULE-AGENT=CLIENT: it explicitly won't deliver. */
        CLIENT_MUST_DELIVER,

        /**
         * Positive delivery signal: SCHEDULE-STATUS stamped, or the invitee ATTENDEE was routed
         * out of the stored resource. A plain PUT invites the attendee.
         */
        SERVER_SCHEDULES,

        /**
         * ATTENDEE stored verbatim with no delivery signal. The server keeps the data but gives
         * no evidence it will deliver; with a scheduling outbox present, delivery needs an
         * explicit client-side iTIP POST.
         */
        NEEDS_CLIENT_ITIP,

        /**
         * App emits no ORGANIZER because the account has no email-shaped address; nothing to
         * schedule (app-side limit, not a server stance).
         */
        NO_ORGANIZER,
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val DAY_MS = 86_400_000L
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 28) * DAY_MS + 9 * 3_600_000L

        /**
         * Observed disposition per server, probed live 2026-06-07 under the "positive delivery
         * signal required" rule (keeping the ATTENDEE line isn't proof of delivery). Nextcloud,
         * Fastmail and Cyrus were probed later; see their entries.
         *
         * SERVER_SCHEDULES: stamped a SCHEDULE-STATUS receipt: iCloud (5.1), Baikal (1.1),
         *   BaikalDigest (1.1) on 2026-06-07; Nextcloud, Fastmail and Cyrus since.
         * NEEDS_CLIENT_ITIP: stored the attendee verbatim with no receipt, so a plain PUT sends
         *   nothing; delivery needs a client-side iTIP outbox POST: Stalwart, SOGo, Mailbox/OX
         *   (the last advertises a schedule-outbox-URL but does nothing implicitly).
         * CLIENT_MUST_DELIVER: explicit refusal: Zoho (SCHEDULE-AGENT=CLIENT).
         * NO_ORGANIZER: bare test containers with no email on the principal (Radicale,
         *   Xandikos); a real account with a mailto: would land elsewhere. Nextcloud lands here
         *   too when its principal has no email.
         */
        private val EXPECTED: Map<String, Disposition> = mapOf(
            "iCloud" to Disposition.SERVER_SCHEDULES,
            "Stalwart" to Disposition.NEEDS_CLIENT_ITIP,
            "Baikal" to Disposition.SERVER_SCHEDULES,
            "BaikalDigest" to Disposition.SERVER_SCHEDULES,
            "Radicale" to Disposition.NO_ORGANIZER,
            // Nextcloud delivers implicitly (SCHEDULE-STATUS=5.0) when the principal has an
            // email configured, verified live 2026-06-10. An email-less container is
            // NO_ORGANIZER.
            "Nextcloud" to Disposition.SERVER_SCHEDULES,
            "Zoho" to Disposition.CLIENT_MUST_DELIVER,
            // Xandikos exposes an empty calendar-user-address-set and the test login isn't
            // email-shaped, so the app emits no ORGANIZER: the same bucket as Radicale.
            "Xandikos" to Disposition.NO_ORGANIZER,
            "SOGo" to Disposition.NEEDS_CLIENT_ITIP,
            "Mailbox" to Disposition.NEEDS_CLIENT_ITIP,
            // Fastmail (Cyrus): a plain PUT stamps SCHEDULE-STATUS=1.1 on the invitee, so it
            // schedules server-side; verified live 2026-06-10.
            "Fastmail" to Disposition.SERVER_SCHEDULES,
            // Local Cyrus test container (the engine Fastmail runs): a plain PUT stamps
            // SCHEDULE-STATUS=1.1 on the invitee, the same server-side scheduling as hosted
            // Fastmail; verified live against the kashcal-cyrus container.
            "Cyrus" to Disposition.SERVER_SCHEDULES,
        )
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null

    @Before
    fun setup() {
        CalDavTestServerLoader.createClient(config)?.let {
            client = it.first; creds = it.second
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
    }

    private suspend fun resolveCaldavRoot(): String {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        return if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(endpoint).getOrNull() ?: endpoint
        } else endpoint
    }

    private suspend fun discoverCalendar(principal: String): String? {
        val home = client!!.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return client!!.listCalendars(home).getOrNull()
            ?.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }

    private fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")

    private fun utc(ms: Long): String {
        val z = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC)
        return String.format(
            "%04d%02d%02dT%02d%02d%02dZ",
            z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute, z.second
        )
    }

    private fun verdict(expected: Disposition, actual: Disposition, detail: String) {
        println("  VERDICT: $actual${if (detail.isNotEmpty()) " ($detail)" else ""}")
        assertEquals(
            "${config.name} scheduling disposition changed from recorded baseline",
            expected, actual
        )
    }

    @Test
    fun `matched-organizer PUT keeps the documented scheduling disposition`() = runBlocking {
        assumeReady()
        val c = client!!
        val expected = EXPECTED[config.name]
            ?: throw AssertionError("No expected disposition recorded for ${config.name}")
        println("\n=== SCHEDULING PROBE: ${config.name} (expect $expected) ===")

        val caldavRoot = resolveCaldavRoot()
        val principal = c.discoverPrincipal(caldavRoot).getOrNull()
        assumeTrue("${config.name}: principal discovery failed", principal != null)

        // Approximates what the app would emit as ORGANIZER: the first email-shaped
        // calendar-user-address, else an email-shaped login, else nothing. The app's
        // resolveOrganizer (via effectiveAddresses) tries the login only when the address
        // set is empty.
        val addrResult = c.discoverCalendarUserAddresses(principal!!)
        val discovered = (addrResult as? CalDavResult.Success)?.data.orEmpty()
        val organizer = discovered.map { it.substringAfter("mailto:") }
            .firstOrNull { it.contains("@") }
            ?: creds!!.username.takeIf { it.contains("@") }
        println("  calendar-user-address-set: $discovered")
        println("  ORGANIZER to use: ${organizer ?: "(none — account not mailto-schedulable)"}")

        if (organizer == null) {
            println("  RESULT: app emits NO organizer -> no scheduling possible")
            verdict(expected, Disposition.NO_ORGANIZER, "no email-shaped address")
            return@runBlocking
        }
        // An account that surfaced an address must not be recorded NO_ORGANIZER; if it is,
        // the recorded expectation has drifted.
        assertNotEquals(
            "${config.name} surfaced an address ($organizer) but was recorded NO_ORGANIZER",
            Disposition.NO_ORGANIZER, expected
        )
        val organizerAddr: String = organizer

        val calendarUrl = discoverCalendar(principal)
        assumeTrue("${config.name}: no calendar found", calendarUrl != null)
        println("  calendar: $calendarUrl")

        val attendee = "kashcal-probe-invitee@example.test"
        val uid = "kashcal-sched-probe-${config.name.lowercase()}-${UUID.randomUUID()}@kashcal.test"
        // Server-side scheduling wire format: matched ORGANIZER + ATTENDEE, NO METHOD.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//Sched Probe//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${utc(START_MS)}
            DTSTART:${utc(START_MS)}
            DTEND:${utc(START_MS + 3_600_000L)}
            SUMMARY:KashCal Scheduling Probe
            ORGANIZER:mailto:$organizerAddr
            ATTENDEE;CN=Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$attendee
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val createResult = c.createEvent(calendarUrl!!, uid, ics)
        assumeTrue(
            "${config.name}: create failed: ${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess()
        )
        val (createdUrl, createEtag) = createResult.getOrNull()!!
        var eventUrl = createdUrl
        var etagForDelete = createEtag

        try {
            // Re-fetch what the server stored. Prefer a direct GET on the PUT URL, but some
            // servers (Stalwart) name the resource by their own scheme instead of the PUT path,
            // so the GET 404s. Fall back to scanning the whole calendar (no range filter) and
            // matching by UID.
            println("  created at: $eventUrl")
            val direct = c.fetchEvent(eventUrl)
            if (direct.isError()) println("  direct GET: ${(direct as CalDavResult.Error).code}")
            val stored = direct.getOrNull() ?: run {
                val all = c.fetchAllEtags(calendarUrl).getOrNull().orEmpty()
                // Match by UID prefix: some servers (Stalwart) truncate the stored UID at a
                // length boundary, so an exact contains(uid) misses. The random run-id in the
                // UID keeps the prefix unique.
                val uidPrefix = uid.substringBefore('@')
                all.mapNotNull { (href, _) ->
                    val full = if (href.startsWith("http")) href
                    else creds!!.serverUrl.trimEnd('/') + href
                    c.fetchEvent(full).getOrNull()
                }.firstOrNull { it.icalData.contains(uidPrefix) }?.also {
                    eventUrl = it.url.ifBlank { eventUrl }
                    println("  found via scan at: $eventUrl")
                }
            }

            // Can't inspect what the server stored -> inconclusive, skip.
            assumeTrue("${config.name}: re-fetch returned nothing (cannot inspect)", stored != null)

            val body = unfold(stored!!.icalData)
            etagForDelete = stored.etag?.ifEmpty { createEtag } ?: createEtag

            val orgLine = body.lines().firstOrNull { it.startsWith("ORGANIZER") }
            val attLines = body.lines().filter { it.startsWith("ATTENDEE") }
            val inviteeLine = attLines.firstOrNull { it.contains(attendee, ignoreCase = true) }
            val scheduleAgent = inviteeLine?.let {
                Regex("""SCHEDULE-AGENT=([A-Z]+)""", RegexOption.IGNORE_CASE)
                    .find(it)?.groupValues?.get(1)?.uppercase()
            }
            val scheduleStatus = inviteeLine?.let {
                Regex("""SCHEDULE-STATUS=([0-9.]+)""").find(it)?.groupValues?.get(1)
            }

            println("  server ORGANIZER: ${orgLine ?: "(none)"}")
            println("  server ATTENDEE lines: ${attLines.size}")
            attLines.forEach { println("    $it") }

            val actual: Disposition
            val detail: String
            when {
                scheduleAgent == "CLIENT" -> {
                    actual = Disposition.CLIENT_MUST_DELIVER
                    detail = "SCHEDULE-AGENT=CLIENT"
                }
                scheduleStatus != null -> {
                    // Server reported a delivery result -> it owns scheduling.
                    actual = Disposition.SERVER_SCHEDULES
                    detail = "SCHEDULE-STATUS=$scheduleStatus"
                }
                inviteeLine == null -> {
                    // The server's scheduling pipeline routed the invitee out of the stored
                    // resource, so the server owns delivery.
                    actual = Disposition.SERVER_SCHEDULES
                    detail = "invitee routed out of resource"
                }
                else -> {
                    // Stored verbatim with no delivery signal: no evidence the server will email
                    // anyone, so a plain PUT sends nothing here.
                    actual = Disposition.NEEDS_CLIENT_ITIP
                    detail = "attendee stored, no SCHEDULE-STATUS receipt"
                }
            }
            verdict(expected, actual, detail)
        } finally {
            val del = c.deleteEvent(eventUrl, etagForDelete)
            if (del.isError()) c.deleteEvent(eventUrl, "")
            println("  cleanup delete: ${if (del.isSuccess()) "ok" else "attempted"}")
        }
    }
}
