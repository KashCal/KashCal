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
 * SPIKE (not a regression gate): does the PUT SHAPE change whether a server's
 * implicit scheduling pipeline delivers an invite to an attendee that exists
 * ONLY on an override (per-occurrence) VEVENT?
 *
 * Motivation: the companion [ExceptionAttendeeDeliveryProbeTest] PUTs the master
 * and the override bundled in ONE resource, in one operation. With that shape,
 * sabre-backed servers (Baikal, Nextcloud) deliver the whole-series attendee but
 * NOT the override-only attendee. RFC 6638 implicit scheduling triggers on the
 * PUT as a Create/Modify operation, so the open question is whether a server
 * that ignores the override-only attendee on an initial bundled CREATE would
 * deliver it if it instead arrives as a later MODIFY of an already-stored series
 * (the shape a real KashCal per-occurrence edit produces: the series already
 * exists on the server, then the user edits one instance, then we PUT again).
 *
 * Two shapes are compared per server:
 *   - Shape A (CREATE-bundled): one PUT of master + override together (a fresh
 *     resource). This reproduces the existing probe's negative result.
 *   - Shape B (MODIFY-add): PUT the master ALONE first (server stores + schedules
 *     the series), THEN a second PUT of master + override that ADDS the
 *     override-only attendee — a discrete modify on the existing resource, which
 *     is what an actual per-occurrence edit emits.
 *
 * If Shape B delivers where Shape A doesn't, per-occurrence editing can ship on
 * the existing implicit path for those servers (just by ordering the writes),
 * with NO client-side iTIP extension needed for them. If both shapes are silent,
 * those servers genuinely need the client-side outbox send extended to exception
 * attendees.
 *
 * RESULT (2026-06-16): both shapes were IDENTICAL on every server — iCloud and
 * Fastmail deliver under both; the other schedulable servers (Stalwart, Baikal,
 * Nextcloud, SOGo, Mailbox) show no receipt under either; Zoho declines under
 * both (though MODIFY-add retains the override where bundled-CREATE drops it).
 * The write ordering does not change delivery, so the client-side send must be
 * extended to exception attendees for the non-iCloud/Fastmail fleet.
 *
 * Records both dispositions per server and prints a comparison. Never fails on
 * disposition (it's exploratory); only skips on unreachable/no-credential.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*ExceptionAttendeePutShapeSpikeTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExceptionAttendeePutShapeSpikeTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val DAY_MS = 86_400_000L
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 35) * DAY_MS + 9 * 3_600_000L
    }

    /** The override-only attendee's delivery signal under one PUT shape. */
    private enum class Signal {
        /** SCHEDULE-STATUS stamped on the override invitee, or it was routed out
         *  of a retained override — the server delivered. */
        DELIVERED,
        /** Override invitee stored verbatim, no receipt — no delivery evidence. */
        NO_RECEIPT,
        /** SCHEDULE-AGENT=CLIENT — server explicitly declines. */
        CLIENT_DECLINED,
        /** The override VEVENT didn't survive at all. */
        OVERRIDE_DROPPED,
        /** Could not run this shape (PUT rejected / nothing to inspect). */
        INCONCLUSIVE,
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

    private fun masterOnlyIcs(uid: String, organizer: String, masterAttendee: String): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//KashCal//PUT-Shape Spike//EN
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:${utc(START_MS)}
        DTSTART:${utc(START_MS)}
        DTEND:${utc(START_MS + 3_600_000L)}
        RRULE:FREQ=DAILY;COUNT=5
        SUMMARY:KashCal PUT-Shape Spike
        ORGANIZER:mailto:$organizer
        ATTENDEE;CN=Master Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$masterAttendee
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private fun masterPlusOverrideIcs(
        uid: String,
        organizer: String,
        masterAttendee: String,
        overrideAttendee: String,
    ): String {
        val occMs = START_MS + 2L * DAY_MS
        val recurrenceId = utc(occMs)
        val excStart = utc(occMs - 8L * 3_600_000L)
        val excEnd = utc(occMs - 8L * 3_600_000L + 3_600_000L)
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//PUT-Shape Spike//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${utc(START_MS)}
            DTSTART:${utc(START_MS)}
            DTEND:${utc(START_MS + 3_600_000L)}
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:KashCal PUT-Shape Spike
            ORGANIZER:mailto:$organizer
            ATTENDEE;CN=Master Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$masterAttendee
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${utc(START_MS)}
            DTSTART:$excStart
            DTEND:$excEnd
            RECURRENCE-ID:$recurrenceId
            SUMMARY:KashCal PUT-Shape Spike (occ 2)
            ORGANIZER:mailto:$organizer
            ATTENDEE;CN=Master Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$masterAttendee
            ATTENDEE;CN=Occurrence Invitee;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$overrideAttendee
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }

    /** Classify the override-only invitee's delivery signal in a stored body. */
    private fun classify(body: String, overrideAttendee: String): Pair<Signal, String> {
        val unfolded = unfold(body)
        val blocks = Regex("""BEGIN:VEVENT(.*?)END:VEVENT""", RegexOption.DOT_MATCHES_ALL)
            .findAll(unfolded).map { it.groupValues[1] }.toList()
        val override = blocks.firstOrNull { it.contains("RECURRENCE-ID") }
            ?: return Signal.OVERRIDE_DROPPED to "no override VEVENT retained (${blocks.size} VEVENT)"
        val inviteeLine = override.lines()
            .filter { it.startsWith("ATTENDEE") }
            .firstOrNull { it.contains(overrideAttendee, ignoreCase = true) }
        if (inviteeLine == null) return Signal.DELIVERED to "override invitee routed out of retained override"
        val agent = Regex("""SCHEDULE-AGENT=([A-Z]+)""", RegexOption.IGNORE_CASE)
            .find(inviteeLine)?.groupValues?.get(1)?.uppercase()
        if (agent == "CLIENT") return Signal.CLIENT_DECLINED to "SCHEDULE-AGENT=CLIENT"
        val status = Regex("""SCHEDULE-STATUS=([0-9.]+)""").find(inviteeLine)?.groupValues?.get(1)
        if (status != null) return Signal.DELIVERED to "SCHEDULE-STATUS=$status"
        return Signal.NO_RECEIPT to "stored verbatim, no receipt"
    }

    private suspend fun fetchStored(
        c: CalDavClient,
        eventUrl: String,
        calendarUrl: String,
        uid: String,
    ): String? {
        c.fetchEvent(eventUrl).getOrNull()?.let { return it.icalData }
        val all = c.fetchAllEtags(calendarUrl).getOrNull().orEmpty()
        val uidPrefix = uid.substringBefore('@')
        return all.mapNotNull { (href, _) ->
            val full = if (href.startsWith("http")) href else creds!!.serverUrl.trimEnd('/') + href
            c.fetchEvent(full).getOrNull()
        }.firstOrNull { it.icalData.contains(uidPrefix) }?.icalData
    }

    @Test
    fun `compare CREATE-bundled vs MODIFY-add override delivery`() = runBlocking {
        assumeReady()
        val c = client!!
        println("\n=== PUT-SHAPE SPIKE: ${config.name} ===")

        val caldavRoot = resolveCaldavRoot()
        val principal = c.discoverPrincipal(caldavRoot).getOrNull()
        assumeTrue("${config.name}: principal discovery failed", principal != null)

        val discovered = (c.discoverCalendarUserAddresses(principal!!) as? CalDavResult.Success)?.data.orEmpty()
        val organizer = discovered.map { it.substringAfter("mailto:") }
            .firstOrNull { it.contains("@") }
            ?: creds!!.username.takeIf { it.contains("@") }
        if (organizer == null) {
            println("  SKIP: no email-shaped ORGANIZER (account not mailto-schedulable)")
            return@runBlocking
        }
        val calendarUrl = discoverCalendar(principal)
        assumeTrue("${config.name}: no calendar found", calendarUrl != null)

        val masterAttendee = "kashcal-master-invitee@example.test"
        val overrideAttendee = "kashcal-occurrence-invitee@example.test"

        // ---- Shape A: CREATE-bundled (master + override in one fresh PUT) ----
        val uidA = "kashcal-shape-a-${config.name.lowercase()}-${UUID.randomUUID()}@kashcal.test"
        var shapeA: Pair<Signal, String> = Signal.INCONCLUSIVE to "not run"
        var urlA: String? = null
        var etagA = ""
        try {
            val res = c.createEvent(calendarUrl!!, uidA, masterPlusOverrideIcs(uidA, organizer, masterAttendee, overrideAttendee))
            if (res.isSuccess()) {
                val (u, e) = res.getOrNull()!!
                urlA = u; etagA = e
                val stored = fetchStored(c, u, calendarUrl, uidA)
                shapeA = stored?.let { classify(it, overrideAttendee) } ?: (Signal.INCONCLUSIVE to "re-fetch null")
            } else {
                shapeA = Signal.INCONCLUSIVE to "create rejected: ${(res as? CalDavResult.Error)?.message}"
            }
        } catch (e: Exception) {
            shapeA = Signal.INCONCLUSIVE to "exception: ${e.message}"
        }
        println("  Shape A (CREATE-bundled): ${shapeA.first} (${shapeA.second})")

        // ---- Shape B: MODIFY-add (master alone, THEN add override) ----
        val uidB = "kashcal-shape-b-${config.name.lowercase()}-${UUID.randomUUID()}@kashcal.test"
        var shapeB: Pair<Signal, String> = Signal.INCONCLUSIVE to "not run"
        var urlB: String? = null
        var etagB = ""
        try {
            val create = c.createEvent(calendarUrl!!, uidB, masterOnlyIcs(uidB, organizer, masterAttendee))
            if (create.isSuccess()) {
                val (u, e) = create.getOrNull()!!
                urlB = u; etagB = e
                // Second PUT: add the override (and its override-only attendee) as
                // a discrete MODIFY of the now-stored series.
                val update = c.updateEvent(u, masterPlusOverrideIcs(uidB, organizer, masterAttendee, overrideAttendee), e)
                if (update.isSuccess()) {
                    etagB = update.getOrNull()!!
                    val stored = fetchStored(c, u, calendarUrl, uidB)
                    shapeB = stored?.let { classify(it, overrideAttendee) } ?: (Signal.INCONCLUSIVE to "re-fetch null")
                } else {
                    shapeB = Signal.INCONCLUSIVE to "modify rejected: ${(update as? CalDavResult.Error)?.message}"
                }
            } else {
                shapeB = Signal.INCONCLUSIVE to "master create rejected: ${(create as? CalDavResult.Error)?.message}"
            }
        } catch (e: Exception) {
            shapeB = Signal.INCONCLUSIVE to "exception: ${e.message}"
        }
        println("  Shape B (MODIFY-add):     ${shapeB.first} (${shapeB.second})")

        val verdict = when {
            shapeB.first == Signal.DELIVERED && shapeA.first != Signal.DELIVERED ->
                "*** MODIFY-add DELIVERS where CREATE-bundled did not — ordering the writes fixes ${config.name} ***"
            shapeA.first == Signal.DELIVERED && shapeB.first == Signal.DELIVERED ->
                "both shapes deliver"
            shapeA.first == Signal.DELIVERED ->
                "CREATE-bundled already delivers (no change needed)"
            else ->
                "neither shape delivers — ${config.name} needs client-side iTIP extended to exceptions"
        }
        println("  VERDICT: $verdict")

        // Cleanup both resources.
        urlA?.let { val d = c.deleteEvent(it, etagA); if (d.isError()) c.deleteEvent(it, "") }
        urlB?.let { val d = c.deleteEvent(it, etagB); if (d.isError()) c.deleteEvent(it, "") }
        Unit
    }
}
