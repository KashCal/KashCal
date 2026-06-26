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
 * Live delivery probe for PER-OCCURRENCE attendee invites — the gate that
 * decides whether per-occurrence attendee editing can ship.
 *
 * Per-occurrence editing adds an attendee to ONE instance of a recurring
 * series by bundling an override VEVENT (same UID + RECURRENCE-ID) that
 * carries an ATTENDEE the master does NOT have. RFC 5546 permits a per-instance
 * REQUEST (RECURRENCE-ID | 0 or 1 | "Only if referring to an instance"), but it
 * does NOT promise that a given server actually DELIVERS a bundled,
 * exception-only attendee. That delivery question is per-server and can only be
 * answered live — this probe answers it.
 *
 * The companion [ServerSideSchedulingProbeTest] pins the MASTER-level
 * disposition (a plain PUT with a matched ORGANIZER + one master ATTENDEE).
 * This probe asks the strictly harder question: when the invitee appears ONLY
 * on the override VEVENT — never on the master — does the server's scheduling
 * pipeline still notice and deliver to that exception-only attendee?
 *
 * Classification mirrors the master probe so the two are directly comparable;
 * the only positive signals are a SCHEDULE-STATUS receipt stamped on the
 * override's invitee ATTENDEE, or that invitee being routed out of the stored
 * override. Preserving the ATTENDEE line verbatim is NOT delivery (a server can
 * store it and email no one — observed on the master probe for Mailbox/OX).
 *
 * This is a research/baseline probe: it RECORDS the observed per-server
 * disposition and asserts only against that recorded baseline, so a future
 * regression (a server we count on stops delivering exception-only invites, or
 * one that needs client iTIP starts auto-delivering) is caught in either
 * direction. It does not gate the build on any particular server delivering —
 * the product decision of which servers to enable per-occurrence editing for
 * is made from this baseline, not enforced by it.
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
    /** What the server does with an attendee that exists ONLY on the override. */
    enum class Disposition {
        /** Server stamps SCHEDULE-AGENT=CLIENT on the override invitee — it
         *  explicitly will not deliver; the client must send the iTIP. */
        CLIENT_MUST_DELIVER,

        /** Positive signal: SCHEDULE-STATUS stamped on the override invitee, or
         *  the invitee was routed out of the stored override. The bundled
         *  exception-only attendee was delivered. */
        SERVER_SCHEDULES,

        /** Override invitee stored verbatim, no delivery signal. The server kept
         *  the per-instance attendee but gave no evidence it will deliver;
         *  per-occurrence invites would need an explicit client-side iTIP POST. */
        NEEDS_CLIENT_ITIP,

        /** Server dropped the override invitee entirely (the override VEVENT no
         *  longer carries the exception-only attendee, and it wasn't routed out
         *  as a scheduling action). Per-occurrence attendees are not viable
         *  here — the data doesn't even survive the round-trip. */
        DROPPED,

        /** App emits no ORGANIZER because the account exposes no mailto:
         *  address; nothing to schedule (app-side limit, not a server stance). */
        NO_ORGANIZER,

        /** Server refused to store the bundled override at all (some servers
         *  reject an override whose RECURRENCE-ID they can't reconcile). */
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
         * Observed disposition per server for an EXCEPTION-ONLY attendee (an
         * invitee present only on the bundled override VEVENT, never on the
         * master). Probed live 2026-06-16. A missing entry means "record and
         * report, do not fail"; a present entry pins the baseline so a
         * regression in either direction fails the probe.
         *
         * SERVER_SCHEDULES — stamps a SCHEDULE-STATUS receipt on the override
         *   invitee: iCloud (5.1), Fastmail (1.1). Per-occurrence invites are
         *   delivered implicitly here.
         * NEEDS_CLIENT_ITIP — stores the override invitee verbatim with NO
         *   receipt; a plain bundled PUT sends nothing for the per-instance add:
         *   Stalwart, Baikal, BaikalDigest, Nextcloud, SOGo, Mailbox.
         * DROPPED — collapses the bundle and discards the override VEVENT
         *   entirely, so the per-occurrence attendee doesn't even survive the
         *   round-trip: Zoho (also stamps SCHEDULE-AGENT=CLIENT on the master).
         * NO_ORGANIZER — bare container, no email on the principal: Radicale.
         *
         * KEY DIVERGENCE from the MASTER probe (ServerSideSchedulingProbeTest):
         * Baikal/BaikalDigest/Nextcloud are SERVER_SCHEDULES at the master level
         * but DEGRADE to NEEDS_CLIENT_ITIP for an exception-only attendee — i.e.
         * implicit delivery covers a whole-series attendee change but NOT a
         * per-occurrence add. Zoho degrades further (CLIENT_MUST_DELIVER →
         * DROPPED). Only iCloud and Fastmail deliver an exception-only invite
         * implicitly. => Per-occurrence attendee editing CANNOT ship on
         * implicit-PUT delivery alone; it requires the client-side outbox iTIP
         * path for every NEEDS_CLIENT_ITIP/CLIENT_MUST_DELIVER server, and a
         * DROPPED server (Zoho) can't carry per-occurrence attendees at all
         * without a different representation.
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
     * Records the observed disposition. When a baseline is recorded in
     * [EXPECTED], assert against it (catch regressions). When it isn't yet
     * (first run), just print — the probe's job on a fresh server is to surface
     * the behaviour, not to red-fail before any baseline exists.
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

        // Master invitee (on every instance) vs the override-only invitee (the
        // one whose delivery we're probing — present ONLY on the exception).
        val masterAttendee = "kashcal-master-invitee@example.test"
        val overrideAttendee = "kashcal-occurrence-invitee@example.test"
        val uid = "kashcal-exc-att-probe-${config.name.lowercase()}-${UUID.randomUUID()}@kashcal.test"

        // Exception lands on occurrence index 2 (master + 2 days), shifted -8h —
        // a real per-occurrence edit shape (RFC 5545 §3.8.4.4: override shares
        // UID, adds RECURRENCE-ID). The override carries BOTH the master invitee
        // and the new override-only invitee, so it's a legal superset add.
        val occMs = START_MS + 2L * DAY_MS
        val recurrenceId = utc(occMs)
        val excStart = utc(occMs - 8L * 3_600_000L)
        val excEnd = utc(occMs - 8L * 3_600_000L + 3_600_000L)

        // PUT master + bundled override in one resource (how KashCal serializes
        // a recurring event with an exception — serializeEventWithExceptions).
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
            // Some servers reject a create that bundles an override; record that
            // as its own disposition rather than skipping — it's a real "can't
            // do per-occurrence here" signal.
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

            // Split into VEVENT blocks and pick the OVERRIDE — the block that
            // carries RECURRENCE-ID — so we classify the override's invitee, not
            // the master's. A naive substringAfter("RECURRENCE-ID") misclassifies
            // when a server reorders so an ATTENDEE precedes RECURRENCE-ID within
            // the same block, or emits the override before the master.
            val veventBlocks = Regex("""BEGIN:VEVENT(.*?)END:VEVENT""", RegexOption.DOT_MATCHES_ALL)
                .findAll(body).map { it.groupValues[1] }.toList()
            val veventCount = veventBlocks.size
            val overrideVevent = veventBlocks.firstOrNull { it.contains("RECURRENCE-ID") }
            // Find the override invitee's ATTENDEE line WITHIN the override block.
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
                // No override VEVENT survived (server collapsed the bundle back
                // into a single master) — per-occurrence attendees don't survive
                // the round-trip at all.
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
                // Override VEVENT retained but the override-only invitee is gone
                // from it, with no CLIENT/STATUS signal — the server routed that
                // attendee out as a scheduling action. Positive delivery signal
                // (iCloud-class), mirroring the master probe's routed-out rule.
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
