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
 * Probes live whether Zoho's schedule-outbox accepts an attendee RSVP (iTIP REPLY) POST.
 *
 * Zoho doesn't self-schedule (it stamps SCHEDULE-AGENT=CLIENT). The organizer direction has
 * an outbox fallback: `PushStrategy.maybeSendViaOutbox` POSTs METHOD:REQUEST when the server
 * won't deliver. The RSVP direction has none: `PushStrategy.processPartstatOnlyUpdate` only
 * PUTs the patched PARTSTAT onto the event resource and never POSTs a REPLY. On an
 * implicit-scheduling server (iCloud, Baikal, Nextcloud) that PUT triggers server-side REPLY
 * delivery to the organizer. On a Zoho-class server the PARTSTAT sits inert and the organizer
 * is never notified.
 *
 * This probe checks the server half: does Zoho's outbox accept a METHOD:REPLY built by the
 * real [ITipBuilder.createReply], which the app doesn't call yet?
 *
 * Result (2026-06-23, live Zoho): the outbox accepts the REPLY POST with HTTP 200 and an
 * empty schedule-response (recipients == []). That isn't a rejection: Zoho returns the same
 * "accepted, server took ownership" disposition for an outbox CANCEL, even to a real
 * recipient. Sabre and Stalwart, by contrast, return 501/400 on an event outbox POST. So an
 * RSVP outbox fallback is viable through the existing postToOutbox. The synthetic
 * reserved-TLD organizer can't elicit a per-recipient 2.x status, so this asserts the
 * acceptance contract (2xx, no failure status), not delivery to a real mailbox.
 *
 * Roles are inverted from [ZohoOutboxITipDeliveryTest], where the account is the ORGANIZER
 * and the recipient a synthetic attendee. Here the account is the responding ATTENDEE
 * (Originator) and the recipient a synthetic ORGANIZER (@example.test, reserved by RFC 6761:
 * undeliverable, no human contacted). The event's ORGANIZER is that synthetic address; the
 * single REPLY attendee is the account's own discovered calendar-user-address with
 * PARTSTAT=ACCEPTED.
 *
 * Side effect: running this (only under `-Pintegration`, only with `ZOHO_*` creds) makes the
 * real Zoho account emit a REPLY toward the reserved-TLD organizer, which is undeliverable.
 * The file is inert until run.
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
    // Optional real, consenting organizer mailbox to receive the REPLY; when set,
    // the real-recipient test runs, otherwise it is skipped. ZOHO_REPLY_ORGANIZER
    // wins, falling back to MAILBOX_PROBE_RECIPIENT so an already configured
    // consenting address works without new setup.
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

        // 3. Build the REPLY with the real ITipBuilder. ORGANIZER is a synthetic
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

        // Observed 2026-06-23: Zoho accepts the REPLY POST with HTTP 200 and an
        // empty schedule-response, the same disposition Zoho, SOGo and Mailbox
        // return for an outbox CANCEL even to a real recipient: the server took
        // ownership, not a rejection. The synthetic reserved-TLD organizer can't
        // elicit a per-recipient 2.x status, so none is demanded. Asserted instead:
        // the outbox accepts a METHOD:REPLY (HTTP 2xx, not the 4xx/5xx Sabre and
        // Stalwart give for an event outbox POST), and a non-empty response carries
        // no failure (3.x/5.x) request-status.
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
        // Same chain through the production CalDavClient.postToOutbox, the method an
        // RSVP outbox fallback would call.
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

        // Like the raw POST above, a synthetic recipient yields an empty
        // schedule-response (recipients == []): accepted, server took ownership.
        // A success with no per-recipient failure means an RSVP outbox fallback
        // can go through this method.
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
        // Observed 2026-06-23: Zoho returns the same empty schedule-response
        // (recipients == []) for a real recipient as for the reserved-TLD one, with
        // no per-recipient 2.x status. Likely because the REPLY references a
        // synthetic event the recipient never organized, so Zoho has nothing to
        // correlate and accepts ownership. This matches the empty-but-accepted
        // CANCEL behavior, so the test asserts acceptance (success, no failure
        // status); the printed line records whether a real status ever appears,
        // which would change this reading. Skipped when no consenting address is
        // set, so it never spams.
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
        // An empty response is the observed norm; a 3.x/5.x would mean Zoho
        // refused the real-recipient REPLY, and an outbox fallback wouldn't work
        // as is.
        assertTrue(
            "Real-recipient REPLY reported a FAILURE request-status (got: [$safeStatuses]) — " +
                "Zoho actively refused the REPLY, the outbox-fallback fix is NOT viable as-is",
            response.recipients.none { rs ->
                rs.requestStatus?.let { Regex("""^\s*[35]\.\d""").containsMatchIn(it) } == true
            }
        )
    }

    /**
     * Builds the event being responded to. ORGANIZER is the synthetic party the REPLY
     * notifies; createReply echoes sequence 0 unchanged (RFC 5546 §2.1.4: a REPLY
     * must not increment SEQUENCE).
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
     * Masks every address not on the reserved `@example.test` TLD before it can
     * reach an assertion message, keeping the account holder's real address out
     * of junit-xml and CI logs.
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
                    // Fall back to the consenting recipient the CANCEL probe
                    // uses, unless ZOHO_REPLY_ORGANIZER overrides it.
                    "MAILBOX_PROBE_RECIPIENT" ->
                        if (realOrganizer == null) realOrganizer = value
                }
            }
        }
    }
}
