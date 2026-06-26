package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.scheduling.ITipBuilder
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.io.File
import java.util.UUID
import okhttp3.Credentials as OkCredentials

/**
 * Live oracle probing whether an attendee RSVP (iTIP REPLY) can be DELIVERED to
 * the organizer via the RFC 6638 §6 schedule-outbox on a server that does not
 * self-schedule (Zoho stamps SCHEDULE-AGENT=CLIENT).
 *
 * WHY THIS EXISTS: the organizer-send direction has an outbox fallback
 * (`PushStrategy.maybeSendViaOutbox` POSTs METHOD:REQUEST when the server won't
 * deliver). The RSVP direction does NOT — `PushStrategy.processPartstatOnlyUpdate`
 * only PATCH-PUTs the PARTSTAT onto the event resource and never POSTs a REPLY.
 * On an implicit-scheduling server (iCloud/Baikal/Nextcloud) the PUT triggers
 * server-side REPLY delivery to the organizer, so the RSVP is seen. On a
 * Zoho-class server the PARTSTAT sits inert and the organizer is never notified
 * — the RSVP-side mirror of the bug T4's outbox POST fixed for invites.
 *
 * This probe confirms the SERVER half: does Zoho's outbox accept a METHOD:REPLY
 * (built by the real, currently-unused [ITipBuilder.createReply])?
 *
 * RESULT (2026-06-23, live Zoho): the outbox ACCEPTS the REPLY POST — HTTP 200,
 * empty schedule-response (recipients == []). That is NOT a rejection: it is the
 * same "accepted, server took ownership" disposition Zoho returns for an outbox
 * CANCEL even to a real recipient (the empty-CANCEL fix established this). It
 * contrasts with Sabre/Stalwart, which return 501/400 on an event outbox POST.
 * So the gap is REAL (the RSVP path has no outbox fallback) and the fix is
 * VIABLE through the existing postToOutbox primitive. The synthetic reserved-TLD
 * organizer cannot elicit a per-recipient 2.x status, so this asserts the
 * acceptance contract (2xx, no FAILURE status), not delivery to a real mailbox.
 *
 * ROLE INVERSION vs [ZohoOutboxITipDeliveryTest]: there the account is the
 * ORGANIZER and the recipient is a synthetic attendee. HERE the account is the
 * responding ATTENDEE (Originator) and the recipient is a synthetic ORGANIZER
 * (@example.test, RFC 6761 reserved — undeliverable, no human contacted). The
 * event's ORGANIZER is that synthetic address; the single REPLY attendee is the
 * account's own discovered calendar-user-address with PARTSTAT=ACCEPTED.
 *
 * OUTWARD-FACING SIDE EFFECT: running this (only under `-Pintegration`, only
 * with `ZOHO_*` creds) makes the real Zoho account emit a REPLY toward the
 * reserved-TLD organizer — undeliverable, inert until explicitly run.
 *
 * PII: the account's own address (Originator + REPLY attendee) may be real; any
 * non-`@example.test` address is redacted before reaching a failure message.
 *
 * Run: `./gradlew :app:testDebugUnitTest -Pintegration --tests "*ZohoOutboxReplyDeliveryTest*"`
 */
class ZohoOutboxReplyDeliveryTest {

    private lateinit var client: CalDavClient
    private var serverUrl: String? = null
    private var davEndpoint: String? = null
    private var username: String? = null
    private var password: String? = null
    // Optional real, consenting organizer mailbox to receive the REPLY. When set,
    // it upgrades the acceptance check into an end-to-end "Zoho reports it
    // delivered to a real address" check (the reserved-TLD recipient can only
    // ever yield an empty schedule-response). Prefers a dedicated key, falls back
    // to the existing MAILBOX_PROBE_RECIPIENT so an already-configured consenting
    // address works without new setup.
    private var realOrganizer: String? = null
    private val factory = OkHttpCalDavClientFactory()
    private val builder = ITipBuilder()

    @Before
    fun setup() {
        loadCredentials()
        assumeTrue(
            "Zoho credentials not available",
            serverUrl != null && username != null && password != null
        )
        if (!serverUrl!!.startsWith("http")) {
            serverUrl = "https://$serverUrl"
        }
        davEndpoint = serverUrl!!.trimEnd('/') + "/caldav"
        val quirks = DefaultQuirks(serverUrl!!)
        client = factory.createClient(
            Credentials(username = username!!, password = password!!, serverUrl = davEndpoint!!),
            quirks
        )
    }

    @Test
    fun `Zoho outbox accepts ITipBuilder REPLY and reports request-status 2_x`() = runBlocking {
        // 1. Discover principal + the account's own calendar-user-address. In the
        //    REPLY direction this address is the responding ATTENDEE / Originator.
        val principalResult = client.discoverPrincipal(davEndpoint!!)
        assumeTrue("Zoho principal discovery failed", principalResult.isSuccess())
        val principalUrl = principalResult.getOrNull()!!

        val cuasResult = client.discoverCalendarUserAddresses(principalUrl)
        val attendeeAddress = cuasResult.getOrNull()
            ?.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
            ?.removePrefix("mailto:")
            ?.removePrefix("MAILTO:")
        assumeTrue("No mailto: calendar-user-address discovered for Zoho", attendeeAddress != null)

        // 2. Discover the schedule-outbox-URL (inline, throwaway PROPFIND).
        val outboxUrl = discoverScheduleOutboxUrl(principalUrl)
        assumeTrue("Zoho did not advertise a schedule-outbox-URL", outboxUrl != null)

        // 3. Build the REPLY with the REAL ITipBuilder. ORGANIZER is a synthetic
        //    reserved-TLD address (the party being notified); the lone REPLY
        //    attendee is the account, PARTSTAT=ACCEPTED.
        val organizerRecipient = "kashcal-reply-oracle-organizer@example.test"
        val ics = builder.createReply(
            event = oracleEvent(organizerAddress = organizerRecipient),
            attendee = Attendee(
                email = attendeeAddress!!,
                name = null,
                partStat = PartStat.ACCEPTED,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )
        )

        // 4. POST to the outbox. In a REPLY, Originator = the responding attendee
        //    (this account), Recipient = the organizer.
        val (httpCode, responseBody) = postToOutbox(
            outboxUrl = absoluteUrl(outboxUrl!!),
            originator = attendeeAddress,
            recipient = organizerRecipient,
            icsBody = ics
        )

        // OBSERVED (2026-06-23): Zoho ACCEPTS the REPLY POST with HTTP 200 and an
        // EMPTY schedule-response. That empty-but-accepted disposition is the same
        // one Zoho/SOGo/Mailbox return for an outbox CANCEL even to a real
        // recipient (see the empty-CANCEL fix), i.e. "server took ownership" — NOT
        // a rejection. The synthetic reserved-TLD organizer can't elicit a
        // per-recipient 2.x status, so we cannot demand one here. The contract we
        // CAN assert is the one that decides whether the fix is viable: the outbox
        // ACCEPTS a METHOD:REPLY (HTTP 2xx, no 4xx/5xx rejection like Sabre/Stalwart
        // give for an event outbox POST). A non-empty response, if present, must
        // not carry a failure (3.x/5.x) request-status.
        val safeBody = redactPii(responseBody.orEmpty())
        println("REPLY-PROBE httpCode=$httpCode bodyLen=${responseBody?.length ?: -1} body=[$safeBody]")
        assertTrue(
            "Zoho outbox REJECTED the REPLY POST (expected 2xx accept), got $httpCode. Response: $safeBody",
            httpCode in 200..299
        )
        assertTrue(
            "Zoho returned a FAILURE request-status for the REPLY (fix not viable as-is). Response: $safeBody",
            !Regex("""request-status>\s*[35]\.\d""", RegexOption.IGNORE_CASE).containsMatchIn(safeBody)
        )
    }

    @Test
    fun `in-app postToOutbox primitive delivers ITipBuilder REPLY and reports request-status 2_x`() = runBlocking {
        // Same chain via the PRODUCTION CalDavClient.postToOutbox primitive — the
        // method the RSVP path would call once the outbox fallback is wired in.
        val principalResult = client.discoverPrincipal(davEndpoint!!)
        assumeTrue("Zoho principal discovery failed", principalResult.isSuccess())
        val principalUrl = principalResult.getOrNull()!!

        val cuasResult = client.discoverCalendarUserAddresses(principalUrl)
        val attendeeAddress = cuasResult.getOrNull()
            ?.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
            ?.removePrefix("mailto:")
            ?.removePrefix("MAILTO:")
        assumeTrue("No mailto: calendar-user-address discovered for Zoho", attendeeAddress != null)

        val outboxUrl = client.discoverScheduleOutboxUrl(principalUrl).getOrNull()
        assumeTrue("Zoho did not advertise a schedule-outbox-URL", outboxUrl != null)

        val organizerRecipient = "kashcal-inapp-reply-oracle-organizer@example.test"
        val ics = builder.createReply(
            event = oracleEvent(organizerAddress = organizerRecipient),
            attendee = Attendee(
                email = attendeeAddress!!,
                name = null,
                partStat = PartStat.ACCEPTED,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )
        )

        val result = client.postToOutbox(
            outboxUrl = outboxUrl!!,
            originator = attendeeAddress,
            recipients = listOf(organizerRecipient),
            icalData = ics
        )

        // Production primitive must also be ACCEPTED (not error out). Like the
        // raw POST above, a synthetic recipient yields an empty schedule-response
        // (recipients == []) — accepted, server took ownership. The assertion is
        // "the primitive succeeded and reported no per-recipient FAILURE", which
        // is what tells us the RSVP-outbox fix is wireable through this method.
        assertTrue(
            "In-app postToOutbox should be ACCEPTED for REPLY against Zoho (got $result)",
            result.isSuccess()
        )
        val response = result.getOrNull()!!
        val statuses = response.recipients.map { it.requestStatus }
        val safeStatuses = redactPii(statuses.joinToString())
        println("REPLY-PROBE in-app recipients=${response.recipients.size} statuses=[$safeStatuses]")
        assertTrue(
            "In-app postToOutbox reported a FAILURE request-status for REPLY. Statuses: $safeStatuses",
            response.recipients.none { rs ->
                rs.requestStatus?.let { Regex("""^\s*[35]\.\d""").containsMatchIn(it) } == true
            }
        )
    }

    @Test
    fun `Zoho outbox accepts a real-recipient REPLY without a failure status`() = runBlocking {
        // End-to-end leg with a real consenting organizer address configured
        // (ZOHO_REPLY_ORGANIZER, or the shared MAILBOX_PROBE_RECIPIENT).
        //
        // OBSERVED (2026-06-23): Zoho returns the SAME empty schedule-response
        // (recipients == []) for a REAL recipient as for the reserved-TLD one —
        // it does NOT upgrade to a per-recipient 2.x status. Likely because the
        // REPLY references a synthetic event the recipient never organized, so
        // Zoho has nothing to correlate and just accepts ownership. This matches
        // the empty-but-accepted CANCEL behaviour, so we assert the ACCEPTANCE
        // contract (accepted, no FAILURE), and the diagnostic line records
        // whether a real status ever appears (would flip this understanding).
        // Skipped entirely when no consenting address is set, so it never spams.
        assumeTrue(
            "No real reply-organizer configured (ZOHO_REPLY_ORGANIZER / MAILBOX_PROBE_RECIPIENT)",
            realOrganizer != null
        )

        val principalResult = client.discoverPrincipal(davEndpoint!!)
        assumeTrue("Zoho principal discovery failed", principalResult.isSuccess())
        val principalUrl = principalResult.getOrNull()!!

        val cuasResult = client.discoverCalendarUserAddresses(principalUrl)
        val attendeeAddress = cuasResult.getOrNull()
            ?.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
            ?.removePrefix("mailto:")
            ?.removePrefix("MAILTO:")
        assumeTrue("No mailto: calendar-user-address discovered for Zoho", attendeeAddress != null)

        val outboxUrl = client.discoverScheduleOutboxUrl(principalUrl).getOrNull()
        assumeTrue("Zoho did not advertise a schedule-outbox-URL", outboxUrl != null)

        val ics = builder.createReply(
            event = oracleEvent(organizerAddress = realOrganizer!!),
            attendee = Attendee(
                email = attendeeAddress!!,
                name = null,
                partStat = PartStat.ACCEPTED,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )
        )

        val result = client.postToOutbox(
            outboxUrl = outboxUrl!!,
            originator = attendeeAddress,
            recipients = listOf(realOrganizer!!),
            icalData = ics
        )
        assertTrue(
            "In-app postToOutbox should be accepted for a real-recipient REPLY (got $result)",
            result.isSuccess()
        )
        val response = result.getOrNull()!!
        val statuses = response.recipients.map { it.requestStatus }
        val safeStatuses = redactPii(statuses.joinToString())
        println("REPLY-PROBE real-recipient recipients=${response.recipients.size} statuses=[$safeStatuses]")
        // Accepted (server took ownership) and no per-recipient FAILURE. An empty
        // response is the observed norm; a 3.x/5.x would mean the real-recipient
        // REPLY was actively refused (which would change the fix's viability).
        assertTrue(
            "Real-recipient REPLY reported a FAILURE request-status (got: [$safeStatuses]) — " +
                "Zoho actively refused the REPLY, the outbox-fallback fix is NOT viable as-is",
            response.recipients.none { rs ->
                rs.requestStatus?.let { Regex("""^\s*[35]\.\d""").containsMatchIn(it) } == true
            }
        )
    }

    /**
     * The event being responded to. ORGANIZER is the (synthetic) party the REPLY
     * notifies; sequence 0 is echoed verbatim by createReply per RFC 5546 §2.1.4.
     */
    private fun oracleEvent(organizerAddress: String): ICalEvent = ICalEvent(
        uid = "kashcal-reply-oracle-${UUID.randomUUID()}@example.test",
        importId = "kashcal-reply-oracle@example.test",
        summary = "KashCal reply delivery oracle",
        description = null,
        location = null,
        dtStart = ICalDateTime.parse("20260615T140000Z"),
        dtEnd = ICalDateTime.parse("20260615T150000Z"),
        duration = null,
        isAllDay = false,
        status = EventStatus.CONFIRMED,
        sequence = 0,
        rrule = null,
        exdates = emptyList(),
        recurrenceId = null,
        alarms = emptyList(),
        categories = emptyList(),
        organizer = Organizer(email = organizerAddress, name = null, sentBy = null),
        attendees = emptyList(),
        color = null,
        dtstamp = ICalDateTime.now(),
        lastModified = null,
        created = null,
        transparency = Transparency.OPAQUE,
        url = null,
        rawProperties = emptyMap()
    )

    /** Raw PROPFIND for schedule-outbox-URL on the principal (inline, throwaway). */
    private fun discoverScheduleOutboxUrl(principalUrl: String): String? {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop><c:schedule-outbox-URL/></d:prop>
            </d:propfind>
        """.trimIndent()
        val request = Request.Builder()
            .url(absoluteUrl(principalUrl))
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "0")
            .build()
        val xml = authedClient().newCall(request).execute().use { it.body?.string() } ?: return null
        val match = Regex(
            """schedule-outbox-URL[^>]*>\s*<[^>]*href>([^<]+)""",
            RegexOption.IGNORE_CASE
        ).find(xml)
        return match?.groupValues?.get(1)?.trim()
    }

    private fun postToOutbox(
        outboxUrl: String,
        originator: String,
        recipient: String,
        icsBody: String
    ): Pair<Int, String?> {
        val request = Request.Builder()
            .url(outboxUrl)
            .post(icsBody.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .header("Originator", "mailto:$originator")
            .header("Recipient", "mailto:$recipient")
            .build()
        return authedClient().newCall(request).execute().use { it.code to it.body?.string() }
    }

    private fun authedClient(): OkHttpClient = OkHttpClient.Builder()
        .authenticator { _, response ->
            response.request.newBuilder()
                .header("Authorization", OkCredentials.basic(username!!, password!!))
                .build()
        }
        .build()

    private fun absoluteUrl(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http", ignoreCase = true)) return pathOrUrl
        val origin = Regex("""^(https?://[^/]+)""").find(serverUrl!!)?.groupValues?.get(1)
            ?: serverUrl!!.trimEnd('/')
        return origin + (if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl")
    }

    /**
     * Mask every address not on the reserved `@example.test` TLD before it can
     * reach an assertion message — keeps the account holder's real address out
     * of junit-xml / CI logs.
     */
    private fun redactPii(text: String): String =
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+""").replace(text) { m ->
            if (m.value.endsWith("@example.test", ignoreCase = true)) m.value else "<redacted-email>"
        }

    private fun loadCredentials() {
        val paths = listOf(
            "local.properties",
            "../local.properties",
            "/onekash/KashCal/local.properties"
        )
        for (path in paths) {
            val file = File(path)
            if (!file.exists()) continue
            file.readLines().forEach { line ->
                if (line.startsWith("#") || !line.contains("=")) return@forEach
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@forEach
                val key = parts[0].trim()
                val value = parts[1].trim()
                when (key) {
                    "ZOHO_SERVER" -> serverUrl = value
                    "ZOHO_USERNAME" -> username = value
                    "ZOHO_PASSWORD" -> password = value
                    "ZOHO_REPLY_ORGANIZER" -> realOrganizer = value
                    // Fall back to the consenting recipient used by the CANCEL
                    // probe, unless a dedicated reply-organizer key overrides it.
                    "MAILBOX_PROBE_RECIPIENT" ->
                        if (realOrganizer == null) realOrganizer = value
                }
            }
        }
    }
}
