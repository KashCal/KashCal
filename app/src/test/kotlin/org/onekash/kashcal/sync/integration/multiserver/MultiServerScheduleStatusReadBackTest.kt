package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * Checks the post-PUT SCHEDULE-STATUS read-back (RFC 6638 §3.2.1) live on each server: after an
 * organizer event with one attendee is created, the stored resource is re-fetched and the app's
 * own parse and classify path ([ICalEventMapper.toAttendeeRows], then [classifyDelivery], the
 * pair [org.onekash.kashcal.sync.strategy.PushStrategy]'s read-back uses) must give the recorded
 * per-server [DeliveryState], and [routeDelivery] the recorded [DeliveryAction].
 *
 * A server that stamps asynchronously may show no receipt on the immediate re-fetch; the app then
 * captures it on a later pull. The test re-fetches up to [REFETCH_ATTEMPTS] times and fails only
 * when the class still differs from [EXPECTED_RECEIPT] after the last attempt, or the routed
 * action differs from [EXPECTED_ACTION].
 *
 * The invitee is a reserved-TLD `@example.test` address (RFC 6761), undeliverable, so no human is
 * contacted. Any other address (a server may rewrite ORGANIZER to the real account holder) is
 * redacted before it reaches a failure message.
 *
 * Skips, never fails, on a server with no credentials, no baseline, no reachable endpoint,
 * failed discovery, no email-shaped organizer, or a failed create.
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

        // 28 days out at 09:00 UTC, the same start as `ServerSideSchedulingProbeTest`.
        private const val DAY_MS = 86_400_000L
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 28) * DAY_MS + 9 * 3_600_000L

        /**
         * Invitee [DeliveryState] each server must give, recorded live 2026-06-08/09:
         *  - Servers that stamp SCHEDULE-STATUS (1.x, 2.x or 5.x) give ServerOwnsDelivery. iCloud
         *    delivers implicitly and stamps 5.x for the undeliverable `@example.test` invitee.
         *  - Zoho stamps SCHEDULE-AGENT=CLIENT: ClientMustDeliver.
         *  - Stalwart, SOGo and Mailbox store the attendee with no receipt: NoReceipt. A plain PUT
         *    sends nothing there.
         *
         * A server without an entry skips. Nextcloud and Radicale are left out on purpose: their
         * bare test containers have no email on the principal, so the app emits no ORGANIZER and
         * there is nothing to schedule. `ServerSideSchedulingProbeTest` covers them.
         */
        private val EXPECTED_RECEIPT: Map<String, DeliveryState> = mapOf(
            "iCloud" to DeliveryState.ServerOwnsDelivery,
            "Baikal" to DeliveryState.ServerOwnsDelivery,
            "BaikalDigest" to DeliveryState.ServerOwnsDelivery,
            // Fastmail (Cyrus): an implicit PUT stamps SCHEDULE-STATUS=1.1 (sent, store-and-forward
            // iMIP); verified live 2026-06-10.
            "Fastmail" to DeliveryState.ServerOwnsDelivery,
            "Zoho" to DeliveryState.ClientMustDeliver,
            "Stalwart" to DeliveryState.NoReceipt,
            "SOGo" to DeliveryState.NoReceipt,
            "Mailbox" to DeliveryState.NoReceipt,
        )

        /**
         * [DeliveryAction] each server's live [DeliveryState] must map to through [routeDelivery],
         * given whether the account advertises an outbox URL. Catches a change in the routing rule:
         *  - ServerOwnsDelivery gives ServerHandles (iCloud, Baikal, BaikalDigest, Fastmail).
         *  - ClientMustDeliver with an advertised outbox gives ClientOutboxPost (Zoho).
         *  - NoReceipt gives NoRemedy with or without an outbox (Stalwart, SOGo, Mailbox). SOGo
         *    advertises an outbox yet a plain PUT delivered nothing: routing follows the NoReceipt
         *    read back, not the advertised capability.
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

        /** Re-fetches allowed for a server that stamps SCHEDULE-STATUS asynchronously. */
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

    /** Masks every address not ending in `@example.test`. */
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

        // The ORGANIZER is the account's discovered calendar-user address, else an email-shaped
        // username. Without one the app emits no ORGANIZER and there is nothing to schedule, so
        // skip (`ServerSideSchedulingProbeTest` records it as NO_ORGANIZER).
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
            // A server that stamps synchronously shows the receipt on attempt 1; an asynchronous
            // one may need a moment. It passes once the class matches.
            var actual: DeliveryState = DeliveryState.NoReceipt
            var detail = "no receipt"
            for (attempt in 1..REFETCH_ATTEMPTS) {
                val stored = c.fetchEvent(createdUrl).getOrNull() ?: continue
                etagForDelete = stored.etag?.ifEmpty { createEtag } ?: createEtag

                val parsed = parser.parseAllEvents(stored.icalData).getOrNull().orEmpty()
                val master = parsed.firstOrNull { it.recurrenceId == null } ?: continue
                // The app's own capture path, not a test regex.
                val rows = ICalEventMapper.toAttendeeRows(master, eventId = 0L)
                val inviteeRow = rows.firstOrNull { it.address.contains(attendee, ignoreCase = true) }

                actual = when {
                    // Invitee routed out of the stored event (iSchedule): the server owns delivery.
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

            // Routes the live state through the production routeDelivery the push path calls, with
            // the account's real outbox availability. Catches a change in the rule itself, e.g. a
            // NoReceipt server routed to an outbox POST, or a ClientMustDeliver server no longer
            // routed to one.
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
