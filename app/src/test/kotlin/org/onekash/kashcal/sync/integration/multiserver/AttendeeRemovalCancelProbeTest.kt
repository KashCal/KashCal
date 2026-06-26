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
 * Live delivery probe for attendee REMOVAL (uninvite = iTIP CANCEL) — the gate
 * that decides HOW the removal feature must deliver the cancellation per server.
 *
 * The hypothesis under test (from RFC 6638 §3.2.1.2 + sabre/dav docs): the
 * dominant model is server-side implicit scheduling — the organizer client just
 * PUTs the event with the dropped ATTENDEE absent, and the server compares the
 * original vs. modified ATTENDEE sets and sends the CANCEL to the dropped guest
 * itself. The Scheduling Outbox is for free/busy, NOT for sending CANCELs
 * (§2.1). A client-side CANCEL POST is the EXCEPTION, needed only for the
 * minority of servers that decline to self-schedule (the same Zoho-class that
 * needed the client-side REQUEST for adds).
 *
 * This probe answers, per server: when we shrink the ATTENDEE set on a PUT
 * (remove one of two guests), does the server signal it OWNED the CANCEL
 * (SERVER_SCHEDULES) or does it silently accept the shrink with no scheduling
 * signal (NEEDS_CLIENT_ITIP — we must POST the CANCEL ourselves)?
 *
 * Mirrors [ServerSideSchedulingProbeTest] (master-level add) and
 * [ExceptionAttendeeDeliveryProbeTest] (per-occurrence add) so the three are
 * directly comparable. It is a research/baseline probe: it RECORDS the observed
 * per-server disposition and asserts only against the recorded baseline, so a
 * regression in either direction is caught. It does not gate the build on any
 * particular server.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*AttendeeRemovalCancelProbeTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AttendeeRemovalCancelProbeTest(
    private val config: CalDavServerConfig
) {
    /** What the server does with an attendee dropped from the ATTENDEE set on a PUT. */
    enum class Disposition {
        /** Positive signal: the dropped attendee was routed out as a scheduling
         *  action — a CANCEL receipt (SCHEDULE-STATUS) appears, or the server
         *  otherwise evidences it owned the cancellation. The shrunk PUT alone
         *  uninvites the guest; no client-side CANCEL needed. */
        SERVER_SCHEDULES,

        /** Server stamps SCHEDULE-AGENT=CLIENT on the surviving attendees — it
         *  explicitly will not schedule; the client must POST the CANCEL. */
        CLIENT_MUST_DELIVER,

        /** Server accepted the shrunk ATTENDEE set with no scheduling signal at
         *  all. The removal persisted but there's no evidence the server emailed
         *  the dropped guest — the client-side outbox CANCEL is required to be
         *  sure the uninvite reaches them. */
        NEEDS_CLIENT_ITIP,

        /** Server REJECTED the shrinking PUT, or ignored it and kept the removed
         *  attendee on the stored resource (removal didn't even take). */
        REMOVAL_REJECTED,

        /** App emits no ORGANIZER because the account exposes no mailto:
         *  address; nothing to schedule (app-side limit, not a server stance). */
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
         * Observed disposition per server for a REMOVED attendee (dropped from
         * the ATTENDEE set on a shrinking PUT). Empty on first run — the probe's
         * job is to surface the behaviour; baselines get pinned here after the
         * first live run so a later regression fails the probe.
         */
        private val EXPECTED: Map<String, Disposition> = emptyMap()
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

    private fun verdict(actual: Disposition, detail: String) {
        println("  VERDICT: $actual${if (detail.isNotEmpty()) " ($detail)" else ""}")
        val expected = EXPECTED[config.name]
        if (expected == null) {
            println("  (no recorded baseline for ${config.name} — recording $actual)")
            return
        }
        org.junit.Assert.assertEquals(
            "${config.name} attendee-removal disposition changed from recorded baseline",
            expected, actual
        )
    }

    @Test
    fun `attendee removal CANCEL delivery disposition`() = runBlocking {
        assumeReady()
        val c = client!!
        println("\n=== ATTENDEE-REMOVAL PROBE: ${config.name} ===")

        val caldavRoot = resolveCaldavRoot()
        val principal = c.discoverPrincipal(caldavRoot).getOrNull()
        assumeTrue("${config.name}: principal discovery failed", principal != null)

        val discovered = (c.discoverCalendarUserAddresses(principal!!) as? CalDavResult.Success)?.data.orEmpty()
        val organizer = discovered.map { it.substringAfter("mailto:") }
            .firstOrNull { it.contains("@") }
            ?: creds!!.username.takeIf { it.contains("@") }
        println("  ORGANIZER to use: ${organizer ?: "(none)"}")
        if (organizer == null) {
            verdict(Disposition.NO_ORGANIZER, "no email-shaped address")
            return@runBlocking
        }

        val calendarUrl = discoverCalendar(principal)
        assumeTrue("${config.name}: no calendar found", calendarUrl != null)

        // Two guests; we'll drop the second one on the modify PUT.
        val keptAttendee = "kashcal-kept-invitee@example.test"
        val removedAttendee = "kashcal-removed-invitee@example.test"
        val uid = "kashcal-removal-probe-${config.name.lowercase()}-${UUID.randomUUID()}@kashcal.test"

        // Step 1: PUT a plain event with TWO attendees (SEQUENCE 0).
        val initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//Attendee-Removal Probe//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${utc(START_MS)}
            DTSTART:${utc(START_MS)}
            DTEND:${utc(START_MS + 3_600_000L)}
            SUMMARY:KashCal Attendee-Removal Probe
            ORGANIZER:mailto:$organizer
            ATTENDEE;CN=Kept Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$keptAttendee
            ATTENDEE;CN=Removed Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$removedAttendee
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val createResult = c.createEvent(calendarUrl!!, uid, initialIcs)
        assumeTrue(
            "${config.name}: initial 2-attendee create failed: ${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess()
        )
        var (eventUrl, etag) = createResult.getOrNull()!!
        println("  created (2 attendees) at: $eventUrl")

        try {
            // Re-fetch to get the server's stored etag for the modify If-Match.
            c.fetchEvent(eventUrl).getOrNull()?.let { etag = it.etag?.ifEmpty { etag } ?: etag }

            // Step 2: PUT the SHRUNK event — removedAttendee dropped, SEQUENCE
            // bumped per RFC 5546 §2.1.4 (CANCEL increments SEQUENCE). This is
            // the wire shape KashCal's removal=CANCEL would emit for ALL_EVENTS.
            val shrunkIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//KashCal//Attendee-Removal Probe//EN
                BEGIN:VEVENT
                UID:$uid
                DTSTAMP:${utc(START_MS + 60_000L)}
                DTSTART:${utc(START_MS)}
                DTEND:${utc(START_MS + 3_600_000L)}
                SUMMARY:KashCal Attendee-Removal Probe
                ORGANIZER:mailto:$organizer
                ATTENDEE;CN=Kept Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$keptAttendee
                SEQUENCE:1
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val updateResult = c.updateEvent(eventUrl, shrunkIcs, etag)
            if (!updateResult.isSuccess()) {
                println("  shrink PUT failed: ${(updateResult as? CalDavResult.Error)?.message}")
                verdict(Disposition.REMOVAL_REJECTED, "server rejected the shrinking PUT")
                return@runBlocking
            }
            etag = updateResult.getOrNull()!!

            // Step 3: re-fetch and inspect. A server that owns the CANCEL routes
            // the removed attendee out and/or leaves a scheduling signal; one
            // that just stores the shrunk set gives no signal (client must POST).
            val stored = c.fetchEvent(eventUrl).getOrNull()
            assumeTrue("${config.name}: re-fetch after shrink returned nothing", stored != null)
            val body = unfold(stored!!.icalData)
            etag = stored.etag?.ifEmpty { etag } ?: etag

            val attendeeLines = body.lines().filter { it.startsWith("ATTENDEE") }
            println("  stored ATTENDEE lines after shrink:")
            attendeeLines.forEach { println("    $it") }

            val removedStillPresent = attendeeLines.any { it.contains(removedAttendee, ignoreCase = true) }
            val keptLine = attendeeLines.firstOrNull { it.contains(keptAttendee, ignoreCase = true) }
            // A CANCEL receipt may be stamped on the removed attendee's line (if
            // the server retains it transiently with a status) or signalled via
            // SCHEDULE-AGENT on the surviving set.
            val removedLine = attendeeLines.firstOrNull { it.contains(removedAttendee, ignoreCase = true) }
            val removedScheduleStatus = removedLine?.let {
                Regex("""SCHEDULE-STATUS=([0-9.]+)""").find(it)?.groupValues?.get(1)
            }
            val keptScheduleAgent = keptLine?.let {
                Regex("""SCHEDULE-AGENT=([A-Z]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.uppercase()
            }

            val actual: Disposition
            val detail: String
            when {
                removedStillPresent && removedScheduleStatus != null -> {
                    actual = Disposition.SERVER_SCHEDULES
                    detail = "removed attendee retained with SCHEDULE-STATUS=$removedScheduleStatus (CANCEL receipt)"
                }
                removedStillPresent -> {
                    actual = Disposition.REMOVAL_REJECTED
                    detail = "removed attendee still on the stored ATTENDEE set with no cancel signal"
                }
                keptScheduleAgent == "CLIENT" -> {
                    actual = Disposition.CLIENT_MUST_DELIVER
                    detail = "SCHEDULE-AGENT=CLIENT on surviving attendee — client must POST CANCEL"
                }
                // Removed attendee gone from the stored set. The strong positive
                // signal (server owned the CANCEL) is a SCHEDULE-STATUS stamped on
                // the SURVIVING attendee indicating the scheduling pipeline ran,
                // OR the server is a known implicit scheduler. Absent any signal,
                // we can't prove the dropped guest was emailed — record as needing
                // the client iTIP to be safe.
                keptLine?.contains("SCHEDULE-STATUS", ignoreCase = true) == true -> {
                    val s = Regex("""SCHEDULE-STATUS=([0-9.]+)""").find(keptLine).let { it?.groupValues?.get(1) }
                    actual = Disposition.SERVER_SCHEDULES
                    detail = "removed attendee routed out; scheduling pipeline active (kept SCHEDULE-STATUS=$s)"
                }
                else -> {
                    actual = Disposition.NEEDS_CLIENT_ITIP
                    detail = "shrunk set accepted, removed attendee gone, but no scheduling signal observed"
                }
            }
            verdict(actual, detail)
        } finally {
            val del = c.deleteEvent(eventUrl, etag)
            if (del.isError()) c.deleteEvent(eventUrl, "")
            println("  cleanup delete: ${if (del.isSuccess()) "ok" else "attempted"}")
        }
    }
}
