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
 * Probes the Mailbox (OX App Suite) scheduling channels to explain an empty
 * `<schedule-response/>` for an event REQUEST POSTed to its outbox. Three hypotheses for the
 * emptiness: (a) it needs a real, deliverable recipient; (b) it needs a per-attendee
 * `Recipient` header or another request shape; (c) OX's outbox ignores event REQUESTs. A
 * lone reserved-TLD recipient exercises neither (a) nor (b), so this probe sends to a real
 * recipient with the per-attendee header.
 *
 * Every observation is printed. The VFREEBUSY control is asserted (it must work if the
 * outbox is alive at all), and when a real recipient is configured the event REQUEST is
 * pinned to an HTTP 200 with no per-recipient response. The implicit-PUT leg only prints.
 *
 * Outward-facing side effect: unlike the reserved-TLD probes, the event-REQUEST leg POSTs
 * to a real, consenting recipient from the `MAILBOX_PROBE_RECIPIENT` local.properties key
 * (the account owner's own mailbox). That address is never hardcoded in source; without the
 * key the event-REQUEST leg skips and the implicit-PUT leg uses an `@example.test` attendee.
 * It is redacted from every assertion and log message (only `@example.test` survives the
 * redactor), so it can't reach junit-xml.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerMailboxOutboxProbeTest*'
 */
class MultiServerMailboxOutboxProbeTest {

    /** A probe event 28 days out at 14:00Z, so no server treats it as a past event. */
    private val probeStartMs = ((System.currentTimeMillis() / 86_400_000L) + 28) * 86_400_000L + 14 * 3_600_000L

    /** [ms] as an iCalendar UTC date-time, e.g. 20261023T140000Z. */
    private fun icsUtc(ms: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(ms))

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

        // ---- Control: VFREEBUSY REQUEST, seen returning 2.0;Success. If it fails,
        // the outbox is down and the event-leg result below is meaningless. ----
        if (outbox != null && organizer != null) {
            val (fbCode, fbBody) = postOutbox(
                absolute(outbox), organizer, organizer,
                freeBusyRequest(organizer)
            )
            val fbOk = fbCode == 200 &&
                Regex("""request-status>\s*2\.\d""", RegexOption.IGNORE_CASE).containsMatchIn(fbBody.orEmpty())
            println("  [control] VFREEBUSY outbox POST: HTTP $fbCode, 2.x=$fbOk")
            println("    body: ${redact(fbBody.orEmpty()).take(300)}")
            // Asserted so a dead outbox can't pass the event leg below as a false
            // 'inert' verdict.
            org.junit.Assert.assertTrue(
                "Mailbox/OX VFREEBUSY control failed (HTTP $fbCode) — outbox endpoint not alive; " +
                    "event-inertness verdict would be unreliable",
                fbOk
            )
        }

        // ---- Hypotheses (a) and (b): event REQUEST to a real recipient, with the
        // per-attendee Recipient header. Runs only when the consenting recipient
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
            // A real per-recipient result is a <CAL:response> element with a
            // <recipient>/<request-status> pair. The inert case is a self-closing
            // <schedule-response/> with no response child, so require an opening
            // <...response> tag that is neither the schedule-response wrapper nor
            // self-closed.
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

            // Pins the no-remedy classification for Mailbox/OX: with the control
            // proving the outbox alive, an event REQUEST to a real recipient must
            // not yield a per-recipient delivery response. If OX starts honoring
            // event REQUESTs through the outbox, this fails and the no-remedy
            // classification must be revisited.
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

        // ---- Implicit PUT leg: create on a real calendar, re-fetch, and classify
        // through the app's read-back parser. Deletes only the URL it created. ----
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
                DTSTART:${icsUtc(probeStartMs)}
                DTEND:${icsUtc(probeStartMs + 3_600_000L)}
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
        // Verdict (2026-06-10, against a real consenting recipient): VFREEBUSY
        // works (2.0;Success) but an event REQUEST returns an empty
        // <schedule-response/>, and the implicit PUT stores the attendee with no
        // SCHEDULE-STATUS. So Mailbox/OX has no client-drivable CalDAV
        // scheduling channel; delivery runs through OX's own web/EAS stack. The
        // control and event-REQUEST assertions above pin that.
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
        dtStart = ICalDateTime.parse(icsUtc(probeStartMs)),
        dtEnd = ICalDateTime.parse(icsUtc(probeStartMs + 3_600_000L)),
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

    /** Masks every address outside the reserved `@example.test` domain. */
    private fun redact(text: String): String =
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+""").replace(text) { m ->
            if (m.value.endsWith("@example.test", ignoreCase = true)) m.value else "<redacted-email>"
        }
}
