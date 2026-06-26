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
 * Live end-to-end oracle for the explicit client-side iTIP delivery channel
 * (RFC 6638 §6 schedule-outbox POST), confirmed working on Zoho.
 *
 * It POSTs a `METHOD:REQUEST` built by the real [ITipBuilder] to Zoho's
 * discovered `schedule-outbox-URL` and asserts the server accepts it with
 * `request-status 2.0` ("Success" — "Event Invitation mail has been
 * successfully sent"). This is the live half of the delivery contract; the
 * offline structural half is `ITipOutboxPayloadContractTest` in icaldav-core.
 *
 * TWO ORACLES IN THIS FILE: the first test POSTs inline (raw OkHttp) exactly as
 * the original investigation did by hand — the independent server-accepted-bytes
 * check that does not depend on the in-app client. The second drives the in-app
 * [CalDavClient.postToOutbox] primitive, confirming the production code path
 * produces equivalent, server-accepted bytes against the live server.
 *
 * OUTWARD-FACING SIDE EFFECT: running this (only under `-Pintegration`, only
 * when `ZOHO_*` creds are present) makes the real Zoho account emit an invite.
 * The recipient is a reserved-TLD `@example.test` address (RFC 6761 — no real
 * mailbox), so the invite is undeliverable and no human is contacted. Merely
 * committing the file sends nothing; it is inert until explicitly run.
 *
 * PII: ORGANIZER is the account's own discovered calendar-user-address (which
 * may be a real address, per RFC 6638 §2 and §6). Any non-`@example.test`
 * address is redacted before it can reach a failure message / junit-xml, so CI
 * logs never capture the account holder's email.
 *
 * Run: `./gradlew :app:testDebugUnitTest -Pintegration --tests "*ZohoOutboxITipDeliveryTest*"`
 */
class ZohoOutboxITipDeliveryTest {

    private lateinit var client: CalDavClient
    private var serverUrl: String? = null
    private var davEndpoint: String? = null
    private var username: String? = null
    private var password: String? = null
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
        // Zoho's CalDAV entry point is /caldav — a PROPFIND on the bare root
        // returns 501. Discovery and the principal PROPFIND must target the DAV
        // endpoint, matching the ZOHO server config's davEndpointSuffix.
        davEndpoint = serverUrl!!.trimEnd('/') + "/caldav"
        val quirks = DefaultQuirks(serverUrl!!)
        client = factory.createClient(
            Credentials(username = username!!, password = password!!, serverUrl = davEndpoint!!),
            quirks
        )
    }

    @Test
    fun `Zoho outbox accepts ITipBuilder REQUEST and reports request-status 2_0`() = runBlocking {
        // 1. Discover the account principal (against the /caldav DAV endpoint).
        val principalResult = client.discoverPrincipal(davEndpoint!!)
        assumeTrue("Zoho principal discovery failed", principalResult.isSuccess())
        val principalUrl = principalResult.getOrNull()!!

        // 2. Resolve the account's own calendar-user-address (ORGANIZER per RFC 6638 §6:
        //    the ORGANIZER mailto MUST match the authenticated account or Zoho
        //    rewrites/strips it).
        val cuasResult = client.discoverCalendarUserAddresses(principalUrl)
        val organizerAddress = cuasResult.getOrNull()
            ?.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
            ?.removePrefix("mailto:")
            ?.removePrefix("MAILTO:")
        assumeTrue("No mailto: calendar-user-address discovered for Zoho", organizerAddress != null)

        // 3. Discover the schedule-outbox-URL on the principal (the channel the
        //    in-app outbox primitive will later use).
        val outboxUrl = discoverScheduleOutboxUrl(principalUrl)
        assumeTrue("Zoho did not advertise a schedule-outbox-URL", outboxUrl != null)

        // 4. Build the REQUEST with the REAL ITipBuilder. Synthetic, reserved-TLD
        //    recipient so the emitted invite is undeliverable.
        val recipient = "kashcal-outbox-oracle@example.test"
        val ics = builder.createRequest(
            event = oracleEvent(organizerAddress!!),
            attendees = listOf(
                Attendee(
                    email = recipient,
                    name = "Outbox Oracle",
                    partStat = PartStat.NEEDS_ACTION,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = true
                )
            )
        )

        // 5. POST to the outbox with the RFC 6638 iMIP envelope headers.
        val (httpCode, responseBody) = postToOutbox(
            outboxUrl = absoluteUrl(outboxUrl!!),
            originator = organizerAddress,
            recipient = recipient,
            icsBody = ics
        )

        val safeBody = redactPii(responseBody.orEmpty())
        assertTrue(
            "Zoho outbox POST expected HTTP 200, got $httpCode. Response: $safeBody",
            httpCode == 200
        )
        // request-status 2.x = success (2.0 Success on Zoho). Tolerate whitespace
        // and surrounding XML around the code.
        assertTrue(
            "Zoho outbox did not report a 2.x request-status. Response: $safeBody",
            Regex("""request-status>\s*2\.\d""", RegexOption.IGNORE_CASE).containsMatchIn(safeBody)
        )
    }

    @Test
    fun `in-app postToOutbox primitive delivers ITipBuilder REQUEST and reports request-status 2_x`() = runBlocking {
        // Same chain as above, but drives the PRODUCTION CalDavClient.postToOutbox
        // primitive (not the inline hand-rolled POST) — the in-app code path that
        // PushStrategy uses. This is the oracle that the productionized primitive
        // is coded against (the inline version above stays as the independent
        // server-accepted-bytes check).
        val principalResult = client.discoverPrincipal(davEndpoint!!)
        assumeTrue("Zoho principal discovery failed", principalResult.isSuccess())
        val principalUrl = principalResult.getOrNull()!!

        val cuasResult = client.discoverCalendarUserAddresses(principalUrl)
        val organizerAddress = cuasResult.getOrNull()
            ?.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
            ?.removePrefix("mailto:")
            ?.removePrefix("MAILTO:")
        assumeTrue("No mailto: calendar-user-address discovered for Zoho", organizerAddress != null)

        // Discover the outbox via the production client method, not the
        // throwaway raw PROPFIND.
        val outboxUrl = client.discoverScheduleOutboxUrl(principalUrl).getOrNull()
        assumeTrue("Zoho did not advertise a schedule-outbox-URL", outboxUrl != null)

        val recipient = "kashcal-inapp-outbox-oracle@example.test"
        val ics = builder.createRequest(
            event = oracleEvent(organizerAddress!!),
            attendees = listOf(
                Attendee(
                    email = recipient,
                    name = "In-App Outbox Oracle",
                    partStat = PartStat.NEEDS_ACTION,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = true
                )
            )
        )

        // Bare addresses in; the primitive prepends mailto: on the wire.
        val result = client.postToOutbox(
            outboxUrl = outboxUrl!!,
            originator = organizerAddress,
            recipients = listOf(recipient),
            icalData = ics
        )

        assertTrue(
            "In-app postToOutbox should succeed against Zoho (got $result)",
            result.isSuccess()
        )
        val response = result.getOrNull()!!
        val statuses = response.recipients.map { it.requestStatus }
        val safeStatuses = redactPii(statuses.joinToString())
        assertTrue(
            "In-app postToOutbox did not report a 2.x request-status. Statuses: $safeStatuses",
            response.recipients.any { rs ->
                rs.requestStatus?.let { Regex("""^\s*2\.\d""").containsMatchIn(it) } == true
            }
        )
    }

    private fun oracleEvent(organizerAddress: String): ICalEvent = ICalEvent(
        uid = "kashcal-outbox-oracle-${UUID.randomUUID()}@example.test",
        importId = "kashcal-outbox-oracle@example.test",
        summary = "KashCal outbox delivery oracle",
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
        // schedule-outbox-URL wraps a single <href>; pull the first href inside it.
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

    /** Resolve a possibly-relative DAV href against the server origin. */
    private fun absoluteUrl(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http", ignoreCase = true)) return pathOrUrl
        val origin = Regex("""^(https?://[^/]+)""").find(serverUrl!!)?.groupValues?.get(1)
            ?: serverUrl!!.trimEnd('/')
        return origin + (if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl")
    }

    /**
     * Mask every address that is not on the reserved `@example.test` TLD before
     * it can reach an assertion message — keeps the account holder's real
     * address out of junit-xml / CI logs (S4).
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
                }
            }
        }
    }
}
