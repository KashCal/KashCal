package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
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
 * Probes, live per server, whether an attendee invited to one occurrence is delivered.
 *
 * A per-occurrence add bundles an exception VEVENT (same UID plus RECURRENCE-ID) carrying an
 * ATTENDEE the master doesn't have. RFC 5546 §3.2.2 allows a per-instance REQUEST
 * (RECURRENCE-ID "Only if referring to an instance"), but nothing promises a server delivers
 * a bundled, exception-only attendee. Only a live run answers that per server.
 *
 * [ServerSideSchedulingProbeTest] pins the master-level disposition (a plain PUT with a
 * matched ORGANIZER and one master ATTENDEE). This probe asks the harder question: when the
 * invitee is only on the exception VEVENT, does the scheduling pipeline still deliver to it?
 *
 * Classification follows the master probe so the two compare directly. The only positive
 * signals are a SCHEDULE-STATUS receipt on the exception's invitee ATTENDEE, or that invitee
 * routed out of the stored exception. Keeping the ATTENDEE line verbatim isn't delivery: a
 * server can store it and email no one, as the master probe observed on Mailbox (OX).
 *
 * It prints each server's disposition and asserts it against the baseline pinned in
 * `EXPECTED`, so a change either way fails: a server stops delivering exception-only invites,
 * or one that needed client iTIP starts delivering. A server with no baseline records and
 * passes. Which servers get per-occurrence attendee editing is decided from this baseline, not
 * enforced by it.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*ExceptionAttendeeDeliveryProbeTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExceptionAttendeeDeliveryProbeTest(
    private val config: CalDavServerConfig
) {
    /** What the server does with an attendee that exists only on the exception. */
    enum class Disposition {
        /**
         * The server stamps SCHEDULE-AGENT=CLIENT on the exception's invitee: it won't deliver,
         * so the client must send the iTIP.
         */
        CLIENT_MUST_DELIVER,

        /**
         * SCHEDULE-STATUS is stamped on the exception's invitee, or the invitee was routed out
         * of the stored exception: the exception-only attendee was delivered.
         */
        SERVER_SCHEDULES,

        /**
         * The exception's invitee is stored with no delivery signal. Nothing shows the server
         * will deliver, so per-occurrence invites need a client-side iTIP POST.
         */
        NEEDS_CLIENT_ITIP,

        /**
         * The stored resource has no exception VEVENT: the server collapsed the bundle into the
         * master. Per-occurrence attendees don't survive the round trip here.
         */
        DROPPED,

        /**
         * No email-shaped address was discovered or given as username, so there is no ORGANIZER
         * to schedule with (a probe limit, not a server stance).
         */
        NO_ORGANIZER,

        /**
         * The create of the bundled resource failed, for example a server that rejects an
         * exception whose RECURRENCE-ID it can't reconcile.
         */
        OVERRIDE_REJECTED,
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val DAY_MS = 86_400_000L
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 28) * DAY_MS + 9 * 3_600_000L

        /**
         * Pinned disposition per server name for an exception-only attendee, probed live
         * 2026-06-16. A server with no entry (Cyrus, Xandikos, the proxied Radicale copies)
         * records and passes; an entry fails the probe on any change.
         *
         * - SERVER_SCHEDULES, a SCHEDULE-STATUS receipt on the exception's invitee: iCloud
         *   (5.1), Fastmail (1.1). Per-occurrence invites are delivered implicitly.
         * - NEEDS_CLIENT_ITIP, the invitee stored verbatim with no receipt, so a bundled PUT
         *   sends nothing for the per-instance add: Stalwart, Baikal, BaikalDigest, Nextcloud,
         *   SOGo, Mailbox.
         * - DROPPED, the bundle collapsed and the exception VEVENT discarded: Zoho, which also
         *   stamps SCHEDULE-AGENT=CLIENT on the master.
         * - NO_ORGANIZER, a bare container with no email on the principal: Radicale.
         *
         * Against the master probe ([ServerSideSchedulingProbeTest]): Baikal, BaikalDigest and
         * Nextcloud schedule at the master level but need client iTIP for an exception-only
         * attendee, so implicit delivery covers a whole-series attendee change but not a
         * per-occurrence add. Zoho goes from CLIENT_MUST_DELIVER to DROPPED. Only iCloud and
         * Fastmail deliver an exception-only invite implicitly. So implicit-PUT delivery alone
         * can't carry per-occurrence invites: every NEEDS_CLIENT_ITIP or CLIENT_MUST_DELIVER
         * server needs the client-side outbox iTIP, and a DROPPED server (Zoho) can't hold
         * per-occurrence attendees without a different representation.
         */
        private val EXPECTED: Map<String, Disposition> = mapOf(
            "iCloud" to Disposition.SERVER_SCHEDULES,
            "Stalwart" to Disposition.NEEDS_CLIENT_ITIP,
            "Baikal" to Disposition.NEEDS_CLIENT_ITIP,
            "BaikalDigest" to Disposition.NEEDS_CLIENT_ITIP,
            "Radicale" to Disposition.NO_ORGANIZER,
            "Nextcloud" to Disposition.NEEDS_CLIENT_ITIP,
            "Zoho" to Disposition.DROPPED,
            "SOGo" to Disposition.NEEDS_CLIENT_ITIP,
            "Mailbox" to Disposition.NEEDS_CLIENT_ITIP,
            "Fastmail" to Disposition.SERVER_SCHEDULES,
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

    /**
     * Prints [actual] and asserts it against the server's `EXPECTED` baseline. A server with
     * no baseline only prints, so a new server surfaces its behavior instead of failing.
     */
    private fun verdict(actual: Disposition, detail: String) {
        println("  VERDICT: $actual${if (detail.isNotEmpty()) " ($detail)" else ""}")
        val expected = EXPECTED[config.name]
        if (expected == null) {
            println("  (no recorded baseline for ${config.name} — recording $actual)")
            return
        }
        org.junit.Assert.assertEquals(
            "${config.name} exception-attendee disposition changed from recorded baseline",
            expected, actual
        )
    }

    @Test
    fun `bundled exception-only attendee delivery disposition`() = runBlocking {
        assumeReady()
        val c = client!!
        println("\n=== EXCEPTION-ATTENDEE PROBE: ${config.name} ===")

        val caldavRoot = resolveCaldavRoot()
        val principal = c.discoverPrincipal(caldavRoot).getOrNull()
        assumeTrue("${config.name}: principal discovery failed", principal != null)

        val addrResult = c.discoverCalendarUserAddresses(principal!!)
        val discovered = (addrResult as? CalDavResult.Success)?.data.orEmpty()
        val organizer = discovered.map { it.substringAfter("mailto:") }
            .firstOrNull { it.contains("@") }
            ?: creds!!.username.takeIf { it.contains("@") }
        println("  calendar-user-address-set: $discovered")
        println("  ORGANIZER to use: ${organizer ?: "(none — account not mailto-schedulable)"}")

        if (organizer == null) {
            verdict(Disposition.NO_ORGANIZER, "no email-shaped address")
            return@runBlocking
        }
        val organizerAddr: String = organizer

        val calendarUrl = discoverCalendar(principal)
        assumeTrue("${config.name}: no calendar found", calendarUrl != null)
        println("  calendar: $calendarUrl")

        // The master invitee is on every occurrence; the probed invitee is only on the
        // exception.
        val masterAttendee = "kashcal-master-invitee@example.test"
        val overrideAttendee = "kashcal-occurrence-invitee@example.test"
        val uid = "kashcal-exc-att-probe-${config.name.lowercase()}-${UUID.randomUUID()}@kashcal.test"

        // The exception is occurrence index 2 (master start plus 2 days), moved 8 hours earlier:
        // a per-occurrence edit shape (RFC 5545 §3.8.4.4, the exception shares the UID and adds
        // RECURRENCE-ID). It carries both the master invitee and the new invitee, a superset
        // add.
        val occMs = START_MS + 2L * DAY_MS
        val recurrenceId = utc(occMs)
        val excStart = utc(occMs - 8L * 3_600_000L)
        val excEnd = utc(occMs - 8L * 3_600_000L + 3_600_000L)

        // PUT the master and the exception in one resource, as the app's push serializes a
        // recurring event with an exception (`PushStrategy.serializeEventWithExceptions`).
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//Exception-Attendee Probe//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${utc(START_MS)}
            DTSTART:${utc(START_MS)}
            DTEND:${utc(START_MS + 3_600_000L)}
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:KashCal Exception-Attendee Probe
            ORGANIZER:mailto:$organizerAddr
            ATTENDEE;CN=Master Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$masterAttendee
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${utc(START_MS)}
            DTSTART:$excStart
            DTEND:$excEnd
            RECURRENCE-ID:$recurrenceId
            SUMMARY:KashCal Exception-Attendee Probe (occ 2)
            ORGANIZER:mailto:$organizerAddr
            ATTENDEE;CN=Master Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$masterAttendee
            ATTENDEE;CN=Occurrence Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$overrideAttendee
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val createResult = c.createEvent(calendarUrl!!, uid, ics)
        if (!createResult.isSuccess()) {
            // A failed create of the bundle is recorded as its own disposition, not skipped: it
            // is a real "no per-occurrence here" signal.
            println("  create failed: ${(createResult as? CalDavResult.Error)?.message}")
            verdict(Disposition.OVERRIDE_REJECTED, "create rejected the bundled override")
            return@runBlocking
        }
        val (createdUrl, createEtag) = createResult.getOrNull()!!
        var eventUrl = createdUrl
        var etagForDelete = createEtag

        try {
            println("  created at: $eventUrl")
            val direct = c.fetchEvent(eventUrl)
            if (direct.isError()) println("  direct GET: ${(direct as CalDavResult.Error).code}")
            val stored = direct.getOrNull() ?: run {
                val all = c.fetchAllEtags(calendarUrl).getOrNull().orEmpty()
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
            assumeTrue("${config.name}: re-fetch returned nothing (cannot inspect)", stored != null)

            val body = unfold(stored!!.icalData)
            etagForDelete = stored.etag?.ifEmpty { createEtag } ?: createEtag

            // Split into VEVENT blocks and pick the exception, the block with RECURRENCE-ID, so
            // the exception's invitee is classified and not the master's. A
            // substringAfter("RECURRENCE-ID") misclassifies when a server puts an ATTENDEE
            // before RECURRENCE-ID in the block, or emits the exception before the master.
            val veventBlocks = Regex("""BEGIN:VEVENT(.*?)END:VEVENT""", RegexOption.DOT_MATCHES_ALL)
                .findAll(body).map { it.groupValues[1] }.toList()
            val veventCount = veventBlocks.size
            val overrideVevent = veventBlocks.firstOrNull { it.contains("RECURRENCE-ID") }
            // The probed invitee's ATTENDEE line, looked up only within the exception block.
            val overrideInviteeLine = overrideVevent?.lines()
                ?.filter { it.startsWith("ATTENDEE") }
                ?.firstOrNull { it.contains(overrideAttendee, ignoreCase = true) }
            val overrideSurvived = overrideInviteeLine != null

            println("  VEVENTs stored: $veventCount")
            println("  override VEVENT retained: ${overrideVevent != null}")
            println("  override-only invitee present in override block: $overrideSurvived")
            body.lines().filter { it.startsWith("ATTENDEE") }.forEach { println("    $it") }

            val scheduleAgent = overrideInviteeLine?.let {
                Regex("""SCHEDULE-AGENT=([A-Z]+)""", RegexOption.IGNORE_CASE)
                    .find(it)?.groupValues?.get(1)?.uppercase()
            }
            val scheduleStatus = overrideInviteeLine?.let {
                Regex("""SCHEDULE-STATUS=([0-9.]+)""").find(it)?.groupValues?.get(1)
            }

            val actual: Disposition
            val detail: String
            when {
                // No exception VEVENT survived: the server collapsed the bundle into the master.
                overrideVevent == null -> {
                    actual = Disposition.DROPPED
                    detail = "override VEVENT (RECURRENCE-ID) not retained ($veventCount VEVENT)"
                }
                scheduleAgent == "CLIENT" -> {
                    actual = Disposition.CLIENT_MUST_DELIVER
                    detail = "SCHEDULE-AGENT=CLIENT on override invitee"
                }
                scheduleStatus != null -> {
                    actual = Disposition.SERVER_SCHEDULES
                    detail = "SCHEDULE-STATUS=$scheduleStatus on override invitee"
                }
                // The exception VEVENT is kept but the probed invitee is gone from it: the server
                // routed that attendee out as a scheduling action (as iCloud does), matching the
                // master probe's routed-out rule.
                !overrideSurvived -> {
                    actual = Disposition.SERVER_SCHEDULES
                    detail = "override invitee routed out of the retained override"
                }
                else -> {
                    actual = Disposition.NEEDS_CLIENT_ITIP
                    detail = "override invitee stored, no SCHEDULE-STATUS receipt"
                }
            }
            verdict(actual, detail)
        } finally {
            val del = c.deleteEvent(eventUrl, etagForDelete)
            if (del.isError()) c.deleteEvent(eventUrl, "")
            println("  cleanup delete: ${if (del.isSuccess()) "ok" else "attempted"}")
        }
    }
}
