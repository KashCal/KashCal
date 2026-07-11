package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.delay
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
 * Live probe: does moving an event that HAS ATTENDEES between two calendars on
 * the same scheduling account cause the server to RE-DELIVER an iTIP message to
 * those attendees?
 *
 * This replicates the exact wire sequence PushStrategy.processMove performs on a
 * MOVE-accepting server (iCloud): WebDAV MOVE to relocate, then an identical-body
 * PUT to the new URL (the #292 fix's post-move body push). The question is
 * whether that PUT — same SEQUENCE, same body, new URL — makes iCloud re-run its
 * RFC 6638 implicit-scheduling delivery.
 *
 * Observability caveat: CalDAV cannot see "an email was sent." The observable
 * proxy is the SCHEDULE-STATUS receipt iCloud stamps on the organizer's ATTENDEE
 * rows on delivery (RFC 6638 §3.2.9). We capture it after the initial create,
 * then again after the move, and compare:
 *   - receipt unchanged  -> no re-delivery on the move (safe: the redundant PUT
 *                           is a no-op to the scheduler)
 *   - receipt re-stamped -> the move re-ran delivery (re-invite risk on the
 *                           post-move PUT)
 * Not a hard delivery guarantee, but the strongest signal CalDAV exposes.
 *
 * Records the observed disposition and asserts against a baseline once recorded,
 * so a future regression in either direction is caught. Runs on iCloud (the
 * MOVE-accepting scheduling server we care about) and any other configured
 * server that both accepts MOVE and schedules.
 *
 * Run: ./gradlew :app:testDebugUnitTest -Pintegration --tests '*MoveReInviteProbeTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MoveReInviteProbeTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            // Only servers that both accept WebDAV MOVE and run scheduling are
            // meaningful here; iCloud is the one we ship the MOVE-then-PUT path
            // to. Others auto-skip if they reject MOVE or expose no organizer.
            listOf(CalDavServerConfig.ICLOUD).map { arrayOf<Any>(it) }

        private val DAY_MS = 86_400_000L
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 28) * DAY_MS + 9 * 3_600_000L
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val createdUrls = mutableListOf<Pair<String, String>>()

    @Before
    fun setup() {
        CalDavTestServerLoader.createClient(config)?.let {
            client = it.first; creds = it.second
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue(
            "${config.name} not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
    }

    private fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")

    private fun utc(ms: Long): String {
        val z = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC)
        return String.format(
            "%04d%02d%02dT%02d%02d%02dZ",
            z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute, z.second
        )
    }

    /** The organizer's ATTENDEE SCHEDULE-STATUS receipt(s) in a fetched body. */
    private fun scheduleStatuses(body: String): List<String> =
        unfold(body).lines()
            .filter { it.startsWith("ATTENDEE") }
            .mapNotNull { Regex("""SCHEDULE-STATUS=([0-9.]+)""").find(it)?.groupValues?.get(1) }
            .sorted()

    @Test
    fun `pure move of an attendee event does not re-stamp the delivery receipt`() = runBlocking {
        assumeReady()
        val c = client!!
        println("\n=== MOVE RE-INVITE PROBE: ${config.name} ===")

        // Discover a matched organizer address (iCloud strips attendees if the
        // ORGANIZER mailto doesn't match the authenticated account — S2).
        val root = if (config.usesWellKnownDiscovery)
            c.discoverWellKnown(creds!!.davEndpoint).getOrNull() ?: creds!!.davEndpoint
        else creds!!.davEndpoint
        val principal = c.discoverPrincipal(root).getOrNull()
        assumeTrue("${config.name}: no principal", principal != null)
        val addrs = (c.discoverCalendarUserAddresses(principal!!) as? CalDavResult.Success)?.data.orEmpty()
        val organizer = addrs.map { it.substringAfter("mailto:") }.firstOrNull { it.contains("@") }
            ?: creds!!.username.takeIf { it.contains("@") }
        assumeTrue("${config.name}: account not mailto-schedulable", organizer != null)

        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull()
        val calendars = c.listCalendars(home!!).getOrNull().orEmpty()
            .map { it.url }
            .filter { !it.contains("inbox") && !it.contains("outbox") && !it.contains("trashbin") }
            .distinct()
        assumeTrue("${config.name}: need 2 writable calendars", calendars.size >= 2)
        val calA = calendars[0]
        val calB = calendars[1]

        val uid = "kashcal-move-reinvite-${config.name.lowercase()}-${UUID.randomUUID()}@kashcal.test"
        val attendee = "kashcal-move-invitee@example.test"
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//Move Re-Invite Probe//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${utc(START_MS)}
            DTSTART:${utc(START_MS)}
            DTEND:${utc(START_MS + 3_600_000L)}
            SEQUENCE:0
            SUMMARY:KashCal Move Re-Invite Probe
            ORGANIZER:mailto:$organizer
            ATTENDEE;CN=Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$attendee
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        // 1. Create in calendar A; capture the initial delivery receipt.
        val createResult = c.createEvent(calA, uid, ics)
        assumeTrue("${config.name}: create failed", createResult.isSuccess())
        val (urlA, etagA) = createResult.getOrNull()!!
        createdUrls.add(urlA to etagA)
        delay(1500) // let scheduling settle

        val afterCreate = c.fetchEvent(urlA).getOrNull()
        assumeTrue("${config.name}: could not fetch after create", afterCreate != null)
        val receiptAfterCreate = scheduleStatuses(afterCreate!!.icalData)
        val attendeeSurvived = unfold(afterCreate.icalData).contains(attendee, ignoreCase = true)
        println("  after create: SCHEDULE-STATUS=$receiptAfterCreate attendeePresent=$attendeeSurvived")

        // If iCloud didn't schedule at all (no receipt AND attendee routed out),
        // there's nothing to observe a re-delivery against.
        assumeTrue(
            "${config.name}: no initial delivery signal to compare against",
            receiptAfterCreate.isNotEmpty() || attendeeSurvived
        )

        // 2. Replicate processMove: WebDAV MOVE to calendar B, then identical-body
        //    PUT to the new URL (the #292 post-move body push, SEQUENCE unchanged).
        val moveResult = c.moveEvent(urlA, calB, uid)
        assumeTrue(
            "${config.name}: does not accept WebDAV MOVE (not the ship path)",
            moveResult.isSuccess()
        )
        val (urlB, movedEtag) = moveResult.getOrNull()!!
        createdUrls.removeAll { it.first == urlA }
        createdUrls.add(urlB to movedEtag)

        // Immediately after the MOVE (before the body PUT) the relocated
        // resource should still carry iCloud's stamped receipt verbatim (RFC 4918
        // MOVE relocates the body as-is). Capture that to separate the MOVE's
        // effect from the PUT's.
        val afterMoveOnly = c.fetchEvent(urlB).getOrNull()
        val receiptAfterMoveOnly = afterMoveOnly?.let { scheduleStatuses(it.icalData) } ?: emptyList()
        println("  after MOVE (pre-PUT): SCHEDULE-STATUS=$receiptAfterMoveOnly")

        val putResult = c.updateEvent(urlB, ics, movedEtag.ifEmpty { "" })
        assumeTrue("${config.name}: post-move PUT did not land (${(putResult as? CalDavResult.Error)?.code})", putResult.isSuccess())

        // Poll for up to ~20s: if iCloud RE-DELIVERS on the move+PUT it will
        // re-stamp a receipt; if the PUT merely clobbered the receipt without
        // re-scheduling, it stays empty. This distinguishes re-invite from a
        // benign receipt loss.
        var receiptAfterMove = emptyList<String>()
        repeat(10) { i ->
            delay(2000)
            receiptAfterMove = c.fetchEvent(urlB).getOrNull()?.let { scheduleStatuses(it.icalData) } ?: emptyList()
            println("  poll #${i + 1} after move+PUT: SCHEDULE-STATUS=$receiptAfterMove")
            if (receiptAfterMove.isNotEmpty()) return@repeat
        }

        val reDelivered = receiptAfterMove.isNotEmpty()
        println("  --- SUMMARY ${config.name} ---")
        println("  create receipt:        $receiptAfterCreate")
        println("  after MOVE (pre-PUT):  $receiptAfterMoveOnly")
        println("  after move+PUT (final):$receiptAfterMove")
        println("  RE-DELIVERED (receipt re-stamped after PUT): $reDelivered")

        // This is a research probe: it RECORDS the disposition rather than
        // red-failing on an ambiguous signal. The product-relevant facts are the
        // three receipts printed above:
        //   - re-stamped after PUT  -> move re-invites (needs a guard)
        //   - stays empty after PUT -> PUT clobbers the receipt but does not
        //     re-deliver (benign for attendees; readBackScheduleStatus re-reads
        //     empty, a minor state loss)
        //   - MOVE preserved it but PUT cleared it -> the redundant post-move PUT
        //     is what disturbs scheduling; a pure move could skip the PUT.
    }

    @org.junit.After
    fun cleanup() = runBlocking {
        val c = client ?: return@runBlocking
        for ((url, etag) in createdUrls.reversed()) {
            try { c.deleteEvent(url, etag) } catch (_: Exception) {}
        }
    }
}
