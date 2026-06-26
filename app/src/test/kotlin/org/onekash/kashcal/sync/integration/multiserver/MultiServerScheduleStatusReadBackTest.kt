package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.domain.scheduling.DeliveryAction
import org.onekash.kashcal.domain.scheduling.DeliveryState
import org.onekash.kashcal.domain.scheduling.classifyDelivery
import org.onekash.kashcal.domain.scheduling.routeDelivery
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Live regression for the post-PUT SCHEDULE-STATUS read-back (RFC 6638 §3.2.1):
 * after the app PUTs an organizer event carrying an attendee, it re-fetches the
 * stored resource and captures the server's delivery decision.
 * This drives the real create + re-fetch against each server and asserts the
 * app's own parse-and-classify path ([ICalEventMapper.toAttendeeRows] +
 * [classifyDelivery]) reproduces the per-server receipt the audit recorded —
 * the same end-state [org.onekash.kashcal.sync.strategy.PushStrategy] persists.
 *
 * Timing: this also observes the immediate-GET-vs-next-pull behavior. A server
 * that stamps asynchronously may not show a receipt on the immediate re-fetch;
 * that captures on a later pull and is an EXPECTED PASS here (we only assert the
 * delivery CLASS the audit recorded, and re-fetch with a short bounded retry to
 * give async stampers a chance — a remaining absence on an async stamper is not
 * a failure). The only failure is a server whose recorded class regresses.
 *
 * The invitee is a reserved-TLD `@example.test` address (RFC 6761) — undeliverable,
 * so no human is ever contacted. Any non-`@example.test` address (e.g. a server
 * that rewrites ORGANIZER to the real account holder) is redacted before it can
 * reach a failure message or CI log.
 *
 * Skips (never fails) on unreachable / no-credential / no-organizer servers.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerScheduleStatusReadBackTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerScheduleStatusReadBackTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        // 28 days out, 09:00 UTC — same horizon as the sibling probe.
        private const val DAY_MS = 86_400_000L
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 28) * DAY_MS + 9 * 3_600_000L

        /**
         * The delivery CLASS the app must reproduce for the invitee receipt,
         * per server, from the recorded audit disposition (2026-06-08/09):
         *   - SERVER_SCHEDULES servers stamp SCHEDULE-STATUS (1.x/2.x/5.x) ->
         *     ServerOwnsDelivery.
         *   - Zoho stamps SCHEDULE-AGENT=CLIENT -> ClientMustDeliver.
         *   - Stalwart/SOGo/Mailbox store the attendee inertly (no receipt) ->
         *     NoReceipt (a plain PUT sends nothing; the outbox fallback or the
         *     no-remedy path would handle it).
         * iCloud delivers implicitly and stamps SCHEDULE-STATUS (5.x for an
         * undeliverable @example.test recipient) -> ServerOwnsDelivery.
         *
         * Nextcloud and Radicale are intentionally absent: their bare test
         * containers have no email on the principal, so the app emits no
         * ORGANIZER (the NO_ORGANIZER artifact), there is nothing to schedule,
         * and the organizer guard below skips them. They are exercised by
         * ServerSideSchedulingProbeTest instead.
         */
        private val EXPECTED_RECEIPT: Map<String, DeliveryState> = mapOf(
            "iCloud" to DeliveryState.ServerOwnsDelivery,
            "Baikal" to DeliveryState.ServerOwnsDelivery,
            "BaikalDigest" to DeliveryState.ServerOwnsDelivery,
            // Fastmail (Cyrus): implicit PUT stamps SCHEDULE-STATUS=1.1
            // (message sent, store-and-forward iMIP) — verified live 2026-06-10.
            "Fastmail" to DeliveryState.ServerOwnsDelivery,
            "Zoho" to DeliveryState.ClientMustDeliver,
            "Stalwart" to DeliveryState.NoReceipt,
            "SOGo" to DeliveryState.NoReceipt,
            "Mailbox" to DeliveryState.NoReceipt,
        )

        /**
         * The client routing ACTION each server's live DeliveryState must map to
         * through the production [routeDelivery], given its actual outbox-URL
         * availability. The pin that catches a routing-rule regression:
         * - ServerOwnsDelivery → ServerHandles (iCloud/Baikal/Nextcloud/Fastmail).
         * - ClientMustDeliver + advertised outbox → ClientOutboxPost (Zoho).
         * - NoReceipt → NoRemedy regardless of an advertised outbox (SOGo
         *   advertises one yet a plain PUT delivered nothing; Stalwart/Mailbox).
         * Note SOGo lands NoRemedy even though it advertises an outbox — the
         * routing keys off the runtime NoReceipt signal, not the (lying)
         * capability flag, which is the whole point of the read-back.
         */
        private val EXPECTED_ACTION: Map<String, DeliveryAction> = mapOf(
            "iCloud" to DeliveryAction.ServerHandles,
            "Baikal" to DeliveryAction.ServerHandles,
            "BaikalDigest" to DeliveryAction.ServerHandles,
            "Fastmail" to DeliveryAction.ServerHandles,
            "Zoho" to DeliveryAction.ClientOutboxPost,
            "Stalwart" to DeliveryAction.NoRemedy,
            "SOGo" to DeliveryAction.NoRemedy,
            "Mailbox" to DeliveryAction.NoRemedy,
        )

        /** Bounded re-fetch attempts, to give async stampers a chance. */
        private const val REFETCH_ATTEMPTS = 3
        private const val REFETCH_DELAY_MS = 1_500L
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val parser = ICalParser()

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

    private fun utc(ms: Long): String {
        val z = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC)
        return String.format(
            "%04d%02d%02dT%02d%02d%02dZ",
            z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute, z.second
        )
    }

    /** Mask any address that is not a reserved-TLD test address (S4). */
    private fun redactPii(text: String): String =
        Regex("""[\w.+-]+@[\w.-]+""").replace(text) { m ->
            if (m.value.endsWith("@example.test")) m.value else "<redacted>@<redacted>"
        }

    @Test
    fun `read-back captures the recorded per-server delivery receipt`() = runBlocking {
        assumeReady()
        val expected = EXPECTED_RECEIPT[config.name]
        assumeTrue("No read-back receipt baseline recorded for ${config.name}", expected != null)

        val c = client!!
        println("\n=== READ-BACK: ${config.name} (expect $expected) ===")

        val principal = c.discoverPrincipal(resolveCaldavRoot()).getOrNull()
        assumeTrue("${config.name}: principal discovery failed", principal != null)

        // Organizer = the account's authoritative mailto: address (S2). No
        // email-shaped address -> the app emits no ORGANIZER, so there is
        // nothing to schedule; skip (handled by the probe's NO_ORGANIZER path).
        val discovered = (c.discoverCalendarUserAddresses(principal!!) as? CalDavResult.Success)
            ?.data.orEmpty()
        val organizer = discovered.map { it.substringAfter("mailto:") }
            .firstOrNull { it.contains("@") }
            ?: creds!!.username.takeIf { it.contains("@") }
        assumeTrue("${config.name}: account not mailto-schedulable (no ORGANIZER)", organizer != null)

        val calendarUrl = discoverCalendar(principal)
        assumeTrue("${config.name}: no calendar found", calendarUrl != null)

        val attendee = "kashcal-readback-invitee@example.test"
        val uid = "kashcal-readback-${config.name.lowercase()}-${UUID.randomUUID()}@kashcal.test"
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//ReadBack//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${utc(START_MS)}
            DTSTART:${utc(START_MS)}
            DTEND:${utc(START_MS + 3_600_000L)}
            SUMMARY:KashCal Read-Back Regression
            ORGANIZER:mailto:$organizer
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
        var etagForDelete = createEtag

        try {
            // Re-fetch with a short bounded retry: this is exactly the
            // immediate-GET-vs-async-stamp observation. A server that stamps
            // synchronously shows the receipt on attempt 1; an async stamper
            // may need a moment (still a PASS as long as the CLASS matches).
            var actual: DeliveryState = DeliveryState.NoReceipt
            var detail = "no receipt"
            for (attempt in 1..REFETCH_ATTEMPTS) {
                val stored = c.fetchEvent(createdUrl).getOrNull() ?: continue
                etagForDelete = stored.etag?.ifEmpty { createEtag } ?: createEtag

                val parsed = parser.parseAllEvents(stored.icalData).getOrNull().orEmpty()
                val master = parsed.firstOrNull { it.recurrenceId == null } ?: continue
                // Drive the app's actual capture path, not a bespoke regex.
                val rows = ICalEventMapper.toAttendeeRows(master, eventId = 0L)
                val inviteeRow = rows.firstOrNull { it.address.contains(attendee, ignoreCase = true) }

                actual = when {
                    // Invitee routed out (iSchedule) -> the server owns delivery.
                    inviteeRow == null && rows.isNotEmpty() -> DeliveryState.ServerOwnsDelivery
                    else -> classifyDelivery(inviteeRow?.scheduleStatus, inviteeRow?.scheduleAgent)
                }
                detail = "status=${inviteeRow?.scheduleStatus} agent=${inviteeRow?.scheduleAgent}"
                println("  attempt $attempt: $actual ($detail)")
                if (actual == expected) break
                if (attempt < REFETCH_ATTEMPTS) Thread.sleep(REFETCH_DELAY_MS)
            }

            assertEquals(
                redactPii("${config.name} read-back delivery receipt changed from recorded baseline ($detail)"),
                expected, actual
            )

            // Routing-rule regression pin: map the live-classified
            // DeliveryState through the SAME production routeDelivery the push
            // path uses, with the account's actual outbox-URL availability, and
            // assert the action matches the recorded baseline. This catches a
            // drift in the routing rule itself (not just the server signal) —
            // e.g. a NoReceipt server wrongly routing to an outbox POST, or a
            // ClientMustDeliver server no longer routing to one. Uses the
            // production function, not a copy (single home).
            val expectedAction = EXPECTED_ACTION[config.name]
            if (expectedAction != null) {
                val outboxAdvertised =
                    client!!.discoverScheduleOutboxUrl(principal!!).getOrNull() != null
                val actualAction = routeDelivery(actual, hasOutboxUrl = outboxAdvertised)
                println("  ROUTE: $actualAction (state=$actual, outbox=$outboxAdvertised)")
                assertEquals(
                    "${config.name} routing action changed from recorded baseline",
                    expectedAction, actualAction
                )
            }
        } finally {
            val del = c.deleteEvent(createdUrl, etagForDelete)
            if (del.isError()) c.deleteEvent(createdUrl, "")
        }
    }
}
