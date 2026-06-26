package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.scheduling.ITipBuilder
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import java.util.UUID
import okhttp3.Credentials as OkCredentials

/**
 * Re-probe of the Mailbox / OX App Suite scheduling channels, closing the
 * ambiguous result the T4 delivery audit recorded (an empty `<schedule-response/>`
 * for an event REQUEST). The audit left three unresolved hypotheses for that
 * emptiness: (a) it needs a real, deliverable recipient; (b) it needs a
 * per-attendee `Recipient` header / different request shape; (c) OX's outbox is
 * genuinely event-inert. The original spike only ever tried a single
 * reserved-TLD recipient, so (a) and (b) were never exercised.
 *
 * This probe is deliberately empirical and prints every observation; it pins
 * the disposition only via soft assumptions where the result is still
 * environment-dependent, and asserts hard only on the unambiguous control
 * (VFREEBUSY must work if the endpoint is alive at all).
 *
 * OUTWARD-FACING SIDE EFFECT: unlike the reserved-TLD probes, the event-REQUEST
 * leg here POSTs to a REAL, CONSENTING recipient supplied via the
 * `MAILBOX_PROBE_RECIPIENT` local.properties key (the account owner's own
 * mailbox). That address is never hardcoded in source — absent the key the
 * delivering legs skip — and it is redacted from every assertion/log message
 * (only `@example.test` survives the redactor), so it cannot reach junit-xml.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerMailboxOutboxProbeTest*'
 */
class MultiServerMailboxOutboxProbeTest {

    private val config = CalDavServerConfig.allServers().first { it.name == "Mailbox" }
    private val builder = ITipBuilder()
    private val parser = ICalParser()

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null

    private fun ready(): Boolean {
        CalDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
        if (client == null || creds == null) return false
        return CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
    }

    @Test
    fun `re-probe Mailbox OX scheduling channels`() = runBlocking {
        assumeTrue("Mailbox credentials not available / server unreachable", ready())
        val c = client!!

        val principal = c.discoverPrincipal(creds!!.davEndpoint).getOrNull()
        assumeTrue("Mailbox principal discovery failed", principal != null)
        println("\n=== MAILBOX/OX RE-PROBE ===")
        println("  principal: $principal")

        val organizer = c.discoverCalendarUserAddresses(principal!!).getOrNull()
            ?.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
            ?.removePrefix("mailto:")?.removePrefix("MAILTO:")
            ?: creds!!.username.takeIf { it.contains("@") }
        println("  ORGANIZER (account address): ${redact(organizer ?: "(none)")}")

        val outbox = discoverOutbox(principal)
        println("  schedule-outbox-URL: ${outbox ?: "(none advertised)"}")

        // ---- Control: VFREEBUSY REQUEST. The audit saw this return 2.0;Success,
        // proving the endpoint is alive. If it fails, the whole outbox is down
        // and the event-leg result below is meaningless. ----
        if (outbox != null && organizer != null) {
            val (fbCode, fbBody) = postOutbox(
                absolute(outbox), organizer, organizer,
                freeBusyRequest(organizer)
            )
            val fbOk = fbCode == 200 &&
                Regex("""request-status>\s*2\.\d""", RegexOption.IGNORE_CASE).containsMatchIn(fbBody.orEmpty())
            println("  [control] VFREEBUSY outbox POST: HTTP $fbCode, 2.x=$fbOk")
            println("    body: ${redact(fbBody.orEmpty()).take(300)}")
            // Positive control: the endpoint must service free/busy. If this
            // fails, the outbox is down and the event-leg verdict below would be
            // a false 'inert' — so assert the control holds.
            org.junit.Assert.assertTrue(
                "Mailbox/OX VFREEBUSY control failed (HTTP $fbCode) — outbox endpoint not alive; " +
                    "event-inertness verdict would be unreliable",
                fbOk
            )
        }

        // ---- Hypothesis (a)+(b): event REQUEST to a REAL recipient, with the
        // per-attendee Recipient header. Only runs when the consenting recipient
        // key is present. ----
        val realRecipient = CalDavTestServerLoader.property("MAILBOX_PROBE_RECIPIENT")
        if (outbox != null && organizer != null && realRecipient != null) {
            val ics = builder.createRequest(
                event = probeEvent(organizer),
                attendees = listOf(
                    Attendee(
                        email = realRecipient,
                        name = "Probe Recipient",
                        partStat = PartStat.NEEDS_ACTION,
                        role = AttendeeRole.REQ_PARTICIPANT,
                        rsvp = true
                    )
                )
            )
            val (evCode, evBody) = postOutbox(absolute(outbox), organizer, realRecipient, ics)
            // A real per-recipient result is a <CAL:response> ELEMENT with a
            // <recipient>/<request-status> pair. The inert case is a
            // self-closing <schedule-response/> with no response child — so
            // require an OPENING <...response> tag that is NOT the
            // schedule-response wrapper and NOT self-closed.
            val hasResponseChild = Regex(
                """<(?:[A-Za-z]+:)?response[\s>]""", RegexOption.IGNORE_CASE
            ).containsMatchIn(evBody.orEmpty())
            val reqStatus = Regex("""request-status>\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(evBody.orEmpty())?.groupValues?.get(1)
            println("  [event REQUEST -> real recipient] HTTP $evCode")
            println("    has <response> child: $hasResponseChild  request-status: ${reqStatus ?: "(none)"}")
            println("    body: ${redact(evBody.orEmpty()).take(400)}")
            println("  >>> INTERPRETATION: " + when {
                reqStatus?.startsWith("2") == true -> "DELIVERS via outbox with a real recipient (audit hypothesis a CONFIRMED — earlier empty result was the reserved-TLD recipient)"
                hasResponseChild -> "outbox returns a per-recipient status (non-2.x) — request shape matters (hypothesis b)"
                evCode == 200 -> "empty <schedule-response/> even for a REAL recipient — event-inert; no client-drivable CalDAV channel (hypothesis c CONFIRMED, audit 'no remedy' upheld)"
                else -> "outbox rejected the event REQUEST (HTTP $evCode)"
            })

            // Regression pins (the audit's 'no remedy' for Mailbox/OX): the
            // control proves the endpoint is alive, and the event REQUEST to a
            // REAL recipient must NOT yield a per-recipient delivery response.
            // If OX ever starts honoring event REQUESTs via the outbox, this
            // flips and the no-remedy classification must be revisited.
            org.junit.Assert.assertEquals(
                "Mailbox/OX outbox event REQUEST unexpectedly returned HTTP != 200", 200, evCode
            )
            org.junit.Assert.assertFalse(
                "Mailbox/OX outbox now returns a per-recipient response for an event REQUEST — " +
                    "the 'no client-drivable CalDAV channel' classification is stale, revisit the no-remedy path",
                hasResponseChild
            )
        } else {
            println("  [event REQUEST] SKIPPED — no MAILBOX_PROBE_RECIPIENT configured")
        }

        // ---- Implicit PUT leg: create on a real calendar, re-fetch, classify
        // via the app's own read-back parse path. ----
        val calendarUrl = discoverCalendar(principal)
        if (calendarUrl != null && organizer != null) {
            val recipient = CalDavTestServerLoader.property("MAILBOX_PROBE_RECIPIENT")
                ?: "kashcal-implicit-probe@example.test"
            val uid = "kashcal-mailbox-implicit-${UUID.randomUUID()}@kashcal.test"
            val putIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//KashCal//Mailbox Probe//EN
                BEGIN:VEVENT
                UID:$uid
                DTSTAMP:20260615T120000Z
                DTSTART:20260615T140000Z
                DTEND:20260615T150000Z
                SUMMARY:KashCal Mailbox implicit-PUT probe
                ORGANIZER:mailto:$organizer
                ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$recipient
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()
            val created = c.createEvent(calendarUrl, uid, putIcs)
            if (created.isSuccess()) {
                val (url, etag) = created.getOrNull()!!
                try {
                    val stored = c.fetchEvent(url).getOrNull()
                    val master = stored?.let { parser.parseAllEvents(it.icalData).getOrNull() }
                        ?.firstOrNull { it.recurrenceId == null }
                    val att = master?.attendees?.firstOrNull { it.email.contains(recipient.substringBefore('@')) }
                    println("  [implicit PUT] stored ATTENDEE scheduleStatus=${att?.scheduleStatus} scheduleAgent=${att?.scheduleAgent}")
                    println("  >>> implicit PUT " + if (att?.scheduleStatus != null) "STAMPED a receipt (delivers implicitly!)" else "stored inertly (no receipt)")
                } finally {
                    c.deleteEvent(url, etag.ifEmpty { "" })
                }
            } else {
                println("  [implicit PUT] create failed: ${(created as? CalDavResult.Error)?.message?.let { redact(it) }}")
            }
        }

        println("=== END MAILBOX/OX RE-PROBE ===\n")
        // Verdict (re-probe 2026-06-10, against a real consenting recipient):
        // VFREEBUSY works (2.0;Success) but an event REQUEST returns an empty
        // <schedule-response/>, and the implicit PUT stores the attendee with no
        // SCHEDULE-STATUS. So Mailbox/OX has no client-drivable CalDAV
        // scheduling channel — delivery runs through OX's own web/EAS stack.
        // The control + event-REQUEST assertions above pin that disposition.
    }

    private fun discoverOutbox(principal: String): String? {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop><c:schedule-outbox-URL/></d:prop>
            </d:propfind>
        """.trimIndent()
        val req = Request.Builder()
            .url(absolute(principal))
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "0")
            .build()
        val xml = http().newCall(req).execute().use { it.body?.string() } ?: return null
        return Regex("""schedule-outbox-URL[^>]*>\s*<[^>]*href>([^<]+)""", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.trim()
    }

    private suspend fun discoverCalendar(principal: String): String? {
        val home = client!!.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return client!!.listCalendars(home).getOrNull()
            ?.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }

    private fun postOutbox(url: String, originator: String, recipient: String, body: String): Pair<Int, String?> {
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .header("Originator", "mailto:$originator")
            .header("Recipient", "mailto:$recipient")
            .build()
        return http().newCall(req).execute().use { it.code to it.body?.string() }
    }

    private fun probeEvent(organizer: String) = ICalEvent(
        uid = "kashcal-mailbox-outbox-${UUID.randomUUID()}@kashcal.test",
        importId = "kashcal-mailbox-outbox@kashcal.test",
        summary = "KashCal Mailbox outbox re-probe",
        description = null, location = null,
        dtStart = ICalDateTime.parse("20260615T140000Z"),
        dtEnd = ICalDateTime.parse("20260615T150000Z"),
        duration = null, isAllDay = false,
        status = EventStatus.CONFIRMED, sequence = 0,
        rrule = null, exdates = emptyList(), recurrenceId = null,
        alarms = emptyList(), categories = emptyList(),
        organizer = Organizer(email = organizer, name = null, sentBy = null),
        attendees = emptyList(), color = null,
        dtstamp = ICalDateTime.now(), lastModified = null, created = null,
        transparency = Transparency.OPAQUE, url = null, rawProperties = emptyMap()
    )

    private fun freeBusyRequest(organizer: String): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//KashCal//Mailbox Probe//EN
        METHOD:REQUEST
        BEGIN:VFREEBUSY
        UID:kashcal-fb-${UUID.randomUUID()}@kashcal.test
        DTSTAMP:20260615T120000Z
        DTSTART:20260615T000000Z
        DTEND:20260616T000000Z
        ORGANIZER:mailto:$organizer
        ATTENDEE:mailto:$organizer
        END:VFREEBUSY
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    private fun http(): OkHttpClient = OkHttpClient.Builder()
        .authenticator { _, response ->
            response.request.newBuilder()
                .header("Authorization", OkCredentials.basic(creds!!.username, creds!!.password))
                .build()
        }
        .build()

    private fun absolute(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http", ignoreCase = true)) return pathOrUrl
        val origin = Regex("""^(https?://[^/]+)""").find(creds!!.serverUrl)?.groupValues?.get(1)
            ?: creds!!.serverUrl.trimEnd('/')
        return origin + (if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl")
    }

    /** Mask every address that is not the reserved @example.test TLD (S4). */
    private fun redact(text: String): String =
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+""").replace(text) { m ->
            if (m.value.endsWith("@example.test", ignoreCase = true)) m.value else "<redacted-email>"
        }
}
