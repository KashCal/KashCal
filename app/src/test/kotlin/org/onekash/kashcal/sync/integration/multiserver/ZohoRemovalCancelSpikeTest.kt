package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.scheduling.ITipBuilder
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Exploratory spike — Zoho attendee REMOVAL, exercising the live client-side
 * CANCEL outbox-POST path (the shipped removal=CANCEL delivery surface) against
 * real servers. Parameterized over the fleet so it's comparative, but the open
 * questions are Zoho-centric. The CANCEL body is built through the production
 * path (EventToICalEventMapper + ITipBuilder.createCancel) so server responses
 * reflect real behaviour, not a hand-rolled body.
 *
 * Questions probed, live:
 *  - all-events removal: shrink a 2-attendee master to 1 via PUT, then POST a
 *      METHOD:CANCEL for the dropped guest to the discovered outbox; record the
 *      schedule-response status.
 *  - per-occurrence override survival (MODIFY-add): create master, then add an
 *      override (2nd PUT) that drops a guest for one instance, re-fetch — does
 *      the override VEVENT survive the round-trip, or collapse to the master set?
 *  - per-occurrence pull divergence: does the stored resource still list the
 *      removed guest on that instance (would a pull revert it to "still invited")?
 *  - per-occurrence CANCEL POST: does the per-instance METHOD:CANCEL
 *      (RECURRENCE-ID set) POST to the outbox and get accepted?
 *  - real-recipient CANCEL: repeat the POST with a consenting real recipient
 *      (MAILBOX_PROBE_RECIPIENT) to tell an empty schedule-response apart from a
 *      reserved-TLD artifact.
 *
 * Records observations and prints a per-server report. NEVER fails on a
 * disposition (exploratory); only skips on unreachable/no-credential. Redacts
 * non-@example.test addresses before printing (Zoho rewrites ORGANIZER to the
 * account holder).
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*ZohoRemovalCancelSpikeTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ZohoRemovalCancelSpikeTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val DAY_MS = 86_400_000L
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 30) * DAY_MS + 9 * 3_600_000L
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null

    @Before
    fun setup() {
        CalDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
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
        return if (config.usesWellKnownDiscovery) c.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
    }

    private suspend fun discoverCalendar(principal: String): String? {
        val home = client!!.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return client!!.listCalendars(home).getOrNull()
            ?.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }

    private fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")

    /** Redact any non-@example.test mailto address (Zoho rewrites ORGANIZER to the holder). */
    private fun redact(s: String): String =
        Regex("""mailto:([^\s>;:]+)""").replace(s) { m ->
            if (m.groupValues[1].endsWith("@example.test")) m.value else "mailto:***REDACTED***"
        }

    private fun utc(ms: Long): String {
        val z = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC)
        return String.format("%04d%02d%02dT%02d%02d%02dZ", z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute, z.second)
    }

    private fun veventBlocks(body: String) =
        Regex("""BEGIN:VEVENT(.*?)END:VEVENT""", RegexOption.DOT_MATCHES_ALL).findAll(body).map { it.groupValues[1] }.toList()

    @Test
    fun `zoho removal delivery spike`() = runBlocking {
        assumeReady()
        val c = client!!
        println("\n=== ZOHO-REMOVAL SPIKE: ${config.name} ===")

        val caldavRoot = resolveCaldavRoot()
        val principal = c.discoverPrincipal(caldavRoot).getOrNull()
        assumeTrue("${config.name}: principal discovery failed", principal != null)

        val discovered = (c.discoverCalendarUserAddresses(principal!!) as? CalDavResult.Success)?.data.orEmpty()
        val organizer = discovered.map { it.substringAfter("mailto:") }.firstOrNull { it.contains("@") }
            ?: creds!!.username.takeIf { it.contains("@") }
        if (organizer == null) { println("  SKIP: no email-shaped ORGANIZER"); return@runBlocking }

        val outboxUrl = c.discoverScheduleOutboxUrl(principal).getOrNull()
        println("  outbox: ${if (outboxUrl != null) "discovered" else "(none)"}")

        val calendarUrl = discoverCalendar(principal) ?: run { println("  SKIP: no calendar"); return@runBlocking }
        val kept = "kashcal-kept-invitee@example.test"
        val removed = "kashcal-removed-invitee@example.test"

        // ---------- all-events removal — shrink master, then CANCEL POST ----------
        val uidA = "kashcal-zrm-all-${config.name.lowercase()}-${UUID.randomUUID()}@kashcal.test"
        var q1 = "not run"
        try {
            val ics2 = masterIcs(uidA, organizer, listOf(kept, removed), seq = 0)
            val res = c.createEvent(calendarUrl, uidA, ics2)
            if (res.isSuccess()) {
                var (url, etag) = res.getOrNull()!!
                c.fetchEvent(url).getOrNull()?.let { etag = it.etag?.ifEmpty { etag } ?: etag }
                // Shrink to just the kept guest (SEQUENCE bumped per CANCEL semantics).
                c.updateEvent(url, masterIcs(uidA, organizer, listOf(kept), seq = 1), etag).getOrNull()?.let { etag = it }
                // Now POST the per-attendee CANCEL for the dropped guest (the drain's path).
                q1 = if (outboxUrl != null) {
                    val cancelIcs = cancelIcs(uidA, organizer, removed, recurrenceId = null, seq = 1)
                    postCancelAndDescribe(c, outboxUrl, organizer, removed, cancelIcs)
                } else "no outbox to POST CANCEL"
                c.deleteEvent(url, etag).let { if (it.isError()) c.deleteEvent(url, "") }
            } else q1 = "master create rejected: ${(res as? CalDavResult.Error)?.message}"
        } catch (e: Exception) { q1 = "exception: ${e.message}" }
        println("  [all-events] CANCEL POST: $q1")

        // ---------- REAL-recipient CANCEL (resolves the empty-[] ambiguity) ----------
        // The synthetic @example.test recipient yields an empty schedule-response
        // on Zoho/SOGo/Mailbox — can't tell "accepted, will deliver" from
        // "accepted, did nothing", and the drain classifies empty as TRANSIENT
        // (retry 10x). A consenting REAL recipient forces the server to report a
        // genuine per-recipient request-status. Outbound email to a real inbox,
        // so gated on the explicit consenting-recipient key.
        val realRecipient = CalDavTestServerLoader.property("MAILBOX_PROBE_RECIPIENT")
        if (outboxUrl != null && realRecipient != null) {
            val q5 = postCancelAndDescribe(
                c, outboxUrl, organizer, realRecipient,
                cancelIcs("kashcal-zrm-real-${UUID.randomUUID()}@kashcal.test", organizer, realRecipient, recurrenceId = null, seq = 1)
            )
            println("  [real-recipient] CANCEL POST: $q5")
        } else {
            println("  [real-recipient] CANCEL POST: SKIPPED (no outbox or no MAILBOX_PROBE_RECIPIENT)")
        }

        // ---------- per-occurrence override survival + pull divergence ----------
        val uidB = "kashcal-zrm-occ-${config.name.lowercase()}-${UUID.randomUUID()}@kashcal.test"
        val occMs = START_MS + 2L * DAY_MS
        var q2 = "not run"; var q3 = "not run"; var q4 = "not run"
        try {
            val create = c.createEvent(calendarUrl, uidB, recurringMasterIcs(uidB, organizer, listOf(kept, removed)))
            if (create.isSuccess()) {
                var (url, etag) = create.getOrNull()!!
                c.fetchEvent(url).getOrNull()?.let { etag = it.etag?.ifEmpty { etag } ?: etag }
                // MODIFY-add an override for occ #2 that drops `removed` (per-instance uninvite).
                val bundled = masterPlusShrunkOverrideIcs(uidB, organizer, listOf(kept, removed), listOf(kept), occMs)
                val upd = c.updateEvent(url, bundled, etag)
                if (upd.isSuccess()) {
                    etag = upd.getOrNull()!!
                    val stored = unfold(c.fetchEvent(url).getOrNull()?.icalData ?: "")
                    val blocks = veventBlocks(stored)
                    val overrideBlock = blocks.firstOrNull { it.contains("RECURRENCE-ID") }
                    q2 = when {
                        overrideBlock == null -> "OVERRIDE_DROPPED (server collapsed to ${blocks.size} VEVENT — per-instance state not retained)"
                        else -> "OVERRIDE_RETAINED (${blocks.size} VEVENTs)"
                    }
                    // does the override (or, if dropped, the master fallback) still list the removed guest for that instance?
                    val instanceAttendees = (overrideBlock ?: blocks.firstOrNull() ?: "")
                        .lines().filter { it.startsWith("ATTENDEE") }
                    val removedStillThere = instanceAttendees.any { it.contains(removed, ignoreCase = true) }
                    q3 = if (overrideBlock == null) {
                        "DIVERGENCE: override dropped → instance falls back to master set; removed guest still listed = $removedStillThere (pull would revert to invited)"
                    } else {
                        "override retained; removed guest present in override = $removedStillThere"
                    }
                    // per-occurrence CANCEL POST (RECURRENCE-ID set).
                    q4 = if (outboxUrl != null) {
                        postCancelAndDescribe(c, outboxUrl, organizer, removed, cancelIcs(uidB, organizer, removed, recurrenceId = occMs, seq = 1))
                    } else "no outbox to POST CANCEL"
                } else q2 = "MODIFY-add rejected: ${(upd as? CalDavResult.Error)?.message}"
                c.deleteEvent(url, etag).let { if (it.isError()) c.deleteEvent(url, "") }
            } else q2 = "recurring master create rejected: ${(create as? CalDavResult.Error)?.message}"
        } catch (e: Exception) { q2 = "exception: ${e.message}" }
        println("  [per-occ] override survival: $q2")
        println("  [per-occ] pull divergence:   $q3")
        println("  [per-occ] CANCEL POST:       $q4")
    }

    private suspend fun postCancelAndDescribe(
        c: CalDavClient, outboxUrl: String, organizer: String, recipient: String, ics: String
    ): String = try {
        val res = c.postToOutbox(outboxUrl, organizer, listOf(recipient), ics)
        when (res) {
            is CalDavResult.Success -> {
                val statuses = res.data.recipients.joinToString { redact("${it.recipient}=${it.requestStatus}") }
                "POST ok; schedule-response: [$statuses]"
            }
            is CalDavResult.Error -> "POST error ${res.code}: ${redact(res.message ?: "")}"
            else -> "POST: $res"
        }
    } catch (e: Exception) { "POST exception: ${e.message}" }

    private fun masterIcs(uid: String, organizer: String, attendees: List<String>, seq: Int) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//KashCal//Zoho Removal Spike//EN\r\n")
        append("BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:${utc(START_MS)}\r\nDTSTART:${utc(START_MS)}\r\nDTEND:${utc(START_MS + 3_600_000L)}\r\n")
        append("SUMMARY:Zoho Removal Spike\r\nORGANIZER:mailto:$organizer\r\nSEQUENCE:$seq\r\n")
        attendees.forEach { append("ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$it\r\n") }
        append("END:VEVENT\r\nEND:VCALENDAR")
    }

    private fun recurringMasterIcs(uid: String, organizer: String, attendees: List<String>) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//KashCal//Zoho Removal Spike//EN\r\n")
        append("BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:${utc(START_MS)}\r\nDTSTART:${utc(START_MS)}\r\nDTEND:${utc(START_MS + 3_600_000L)}\r\n")
        append("RRULE:FREQ=DAILY;COUNT=5\r\nSUMMARY:Zoho Removal Spike\r\nORGANIZER:mailto:$organizer\r\nSEQUENCE:0\r\n")
        attendees.forEach { append("ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$it\r\n") }
        append("END:VEVENT\r\nEND:VCALENDAR")
    }

    private fun masterPlusShrunkOverrideIcs(
        uid: String, organizer: String, masterAttendees: List<String>, overrideAttendees: List<String>, occMs: Long
    ) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//KashCal//Zoho Removal Spike//EN\r\n")
        append("BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:${utc(START_MS)}\r\nDTSTART:${utc(START_MS)}\r\nDTEND:${utc(START_MS + 3_600_000L)}\r\n")
        append("RRULE:FREQ=DAILY;COUNT=5\r\nSUMMARY:Zoho Removal Spike\r\nORGANIZER:mailto:$organizer\r\nSEQUENCE:0\r\n")
        masterAttendees.forEach { append("ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$it\r\n") }
        append("END:VEVENT\r\n")
        append("BEGIN:VEVENT\r\nUID:$uid\r\nRECURRENCE-ID:${utc(occMs)}\r\nDTSTAMP:${utc(START_MS)}\r\nDTSTART:${utc(occMs)}\r\nDTEND:${utc(occMs + 3_600_000L)}\r\n")
        append("SUMMARY:Zoho Removal Spike (occ)\r\nORGANIZER:mailto:$organizer\r\nSEQUENCE:1\r\n")
        overrideAttendees.forEach { append("ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$it\r\n") }
        append("END:VEVENT\r\nEND:VCALENDAR")
    }

    /**
     * Build the CANCEL body through the PRODUCTION path — the exact
     * EventToICalEventMapper + ITipBuilder.createCancel that
     * PushStrategy.drainPendingCancels uses — so the server's response is a
     * trustworthy signal about the drain's real behaviour, not an artifact of a
     * hand-rolled body. Mirrors the drain: per-occurrence (recurrenceId set)
     * routes through the exception overload (RECURRENCE-ID + instance DTSTART,
     * no RRULE); series cancel uses the master overload.
     */
    private fun cancelIcs(uid: String, organizer: String, recipient: String, recurrenceId: Long?, seq: Int): String {
        val base = Event(
            uid = uid, calendarId = 1L, title = "Zoho Removal Spike",
            startTs = recurrenceId ?: START_MS, endTs = (recurrenceId ?: START_MS) + 3_600_000L,
            timezone = "UTC", isAllDay = false, status = "CONFIRMED",
            organizerEmail = organizer, dtstamp = START_MS, sequence = seq,
        )
        val cancelRow = Attendee(eventId = 0, address = "mailto:$recipient")
        val icalEvent = if (recurrenceId != null) {
            EventToICalEventMapper.toICalEvent(
                masterUid = uid,
                exception = base.copy(originalInstanceTime = recurrenceId, rrule = null),
                attendees = listOf(cancelRow),
            )
        } else {
            EventToICalEventMapper.toICalEvent(base, listOf(cancelRow))
        }
        return ITipBuilder.default.createCancel(icalEvent, icalEvent.attendees)
    }
}
