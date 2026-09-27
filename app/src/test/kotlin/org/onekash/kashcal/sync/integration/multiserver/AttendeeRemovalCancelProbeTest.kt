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
 * Probes, live per server, how an attendee removal (an iTIP CANCEL) must be delivered.
 *
 * Hypothesis (RFC 6638 §3.2.1.2 and sabre/dav docs): the server schedules implicitly. The
 * organizer PUTs the event without the dropped ATTENDEE, and the server compares the original
 * and modified ATTENDEE sets per instance and sends the CANCEL itself. The Scheduling Outbox
 * is for busy-time requests (§2.1), not CANCELs. A client-side CANCEL POST is needed only for
 * servers that don't self-schedule (the same Zoho class that needed a client-side REQUEST for
 * adds).
 *
 * The probe removes one of two guests on a PUT and asks whether the server signals it owned
 * the CANCEL ([Disposition.SERVER_SCHEDULES]) or silently accepts the shrink
 * ([Disposition.NEEDS_CLIENT_ITIP]: the client must POST the CANCEL).
 *
 * Shaped like [ServerSideSchedulingProbeTest] (master-level add) and
 * [ExceptionAttendeeDeliveryProbeTest] (per-occurrence add) so the three compare directly.
 * It prints each server's disposition and asserts it only against a baseline pinned in
 * `EXPECTED`, so a pinned server fails on a change in either direction. With no baseline it
 * records and passes.
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
        /**
         * The server owned the cancellation: a SCHEDULE-STATUS on the removed attendee's
         * retained line, or on the surviving attendee once the removed one is gone. The shrunk
         * PUT alone uninvites the guest.
         */
        SERVER_SCHEDULES,

        /**
         * The server stamps SCHEDULE-AGENT=CLIENT on the surviving attendee: it won't schedule, so
         * the client must POST the CANCEL.
         */
        CLIENT_MUST_DELIVER,

        /**
         * The server stored the shrunk ATTENDEE set with no scheduling signal. Nothing shows it
         * emailed the dropped guest, so only a client-side outbox CANCEL makes sure the uninvite
         * arrives.
         */
        NEEDS_CLIENT_ITIP,

        /**
         * The server rejected the shrinking PUT, or kept the removed attendee on the stored
         * resource without a cancel signal.
         */
        REMOVAL_REJECTED,

        /**
         * No email-shaped address was discovered or given as username, so there is no ORGANIZER to
         * schedule with (a probe limit, not a server stance).
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
         * Pinned disposition per server name for a removed attendee. Empty: no server is pinned
         * yet, so every run only records. Pin a server here after a live run so a later change
         * fails the probe.
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

        // Two guests; the modify PUT drops the second.
        val keptAttendee = "kashcal-kept-invitee@example.test"
        val removedAttendee = "kashcal-removed-invitee@example.test"
        val uid = "kashcal-removal-probe-${config.name.lowercase()}-${UUID.randomUUID()}@kashcal.test"

        // Step 1: PUT a plain event with two attendees (SEQUENCE 0).
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
            // Re-fetch for the server's stored etag to send as If-Match.
            c.fetchEvent(eventUrl).getOrNull()?.let { etag = it.etag?.ifEmpty { etag } ?: etag }

            // Step 2: PUT the shrunk event without removedAttendee. SEQUENCE is bumped because
            // RFC 5546 §2.1.4 requires it for every CANCEL. This is the wire shape of an
            // all-events removal.
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

            // Step 3: re-fetch and inspect. A server that owns the CANCEL leaves a scheduling
            // signal; one that only stores the shrunk set leaves none (the client must POST).
            val stored = c.fetchEvent(eventUrl).getOrNull()
            assumeTrue("${config.name}: re-fetch after shrink returned nothing", stored != null)
            val body = unfold(stored!!.icalData)
            etag = stored.etag?.ifEmpty { etag } ?: etag

            val attendeeLines = body.lines().filter { it.startsWith("ATTENDEE") }
            println("  stored ATTENDEE lines after shrink:")
            attendeeLines.forEach { println("    $it") }

            val removedStillPresent = attendeeLines.any { it.contains(removedAttendee, ignoreCase = true) }
            val keptLine = attendeeLines.firstOrNull { it.contains(keptAttendee, ignoreCase = true) }
            // A CANCEL receipt may be stamped on the removed attendee's line, if the server
            // keeps it with a status, or signalled by SCHEDULE-AGENT on the surviving attendee.
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
                // The removed attendee is gone. A SCHEDULE-STATUS on the surviving attendee shows
                // the scheduling pipeline ran. Without it, nothing proves the dropped guest was
                // emailed, so the probe records that the client iTIP is needed.
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
