package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.UUID

/**
 * Capture canonical recurring-edit fixtures for each supported CalDAV
 * server. For each (server × edit-mode) the harness creates a master
 * weekly recurring event, performs the edit, fetches the stored body
 * back, and writes a redacted fixture to
 * `docs/fixtures/recurring-edit/<server>/...`.
 *
 * Edit modes captured:
 *   01 — master create + fetch round-trip
 *   02 — THIS_EVENT     (single-occurrence exception via RECURRENCE-ID)
 *   03 — THIS_AND_FUTURE (split: truncate master with UNTIL + new series UID)
 *   04 — ALL_EVENTS     (PUT to master with new title)
 *
 * Anonymization: every PUT body uses synthetic `@example.test`
 * addresses. Before any fixture lands on disk it goes through
 * [FixtureRedactor] which masks any address that doesn't end in
 * `@example.test`, opaque etag/schedule-tag values, account-id segments
 * in URLs, and `CN=` display-names. The harness loud-fails if a
 * post-redaction body still contains a non-synthetic email-shaped
 * substring.
 *
 * Run:
 *     ./gradlew :app:testDebugUnitTest -Pintegration \
 *         --tests '*MultiServerRecurringEditFixturesTest*'
 *
 * Output dir is gitignored (`docs/`). Servers unreachable at runtime
 * skip via `assumeTrue`.
 */
@RunWith(Parameterized::class)
class MultiServerRecurringEditFixturesTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = CalDavServerConfig.allServers()

        private val classStartMs = System.currentTimeMillis()
        internal val UID_PREFIX = "recur-fix-$classStartMs-"
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private var calendarUrl: String? = null
    private val createdEventUrls = mutableListOf<Pair<String, String>>()

    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setup() {
        val pair = CalDavTestServerLoader.createClient(config)
        if (pair != null) {
            client = pair.first
            creds = pair.second
        }
    }

    @After
    fun cleanup() = runBlocking {
        val c = client ?: return@runBlocking
        // Cleanup walks ONLY URLs we created in this run; we never read
        // a calendar listing and delete arbitrary events. This is the
        // hard invariant that protects against ever touching user data.
        for ((url, etag) in createdEventUrls.reversed()) {
            try {
                c.deleteEvent(url, etag)
            } catch (_: Exception) {
                // Best-effort. Orphans use unique UID prefix
                // `recur-fix-{ms}-…` and are harmless if left.
            }
        }
    }

    private fun assumeReady() {
        assumeTrue(
            "${config.name} credentials not available",
            client != null && creds != null
        )
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
    }

    private suspend fun discoverCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            val wellKnown = c.discoverWellKnown(endpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else endpoint
        } else {
            endpoint
        }
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = c.listCalendars(home).getOrNull() ?: return null
        return calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
    }

    private fun trackEvent(url: String, etag: String) {
        createdEventUrls.removeAll { it.first == url }
        createdEventUrls.add(Pair(url, etag))
    }

    /**
     * Belt-and-suspenders: refuse to mutate any URL we didn't create in
     * this run. Catches any future refactor that drifts off our own URL
     * set and would otherwise touch user data.
     */
    private fun assertOurEvent(url: String) {
        check(createdEventUrls.any { it.first == url }) {
            "Refusing to mutate URL not created by this test run: $url"
        }
    }

    /**
     * Compute three weekly occurrence timestamps starting next Monday at
     * 10:00 UTC. We pick Monday because every server tested handles
     * weekday alignment without timezone hand-waving.
     */
    private data class TimePoints(
        val first: String,
        val firstEnd: String,
        val second: String,
        val secondEnd: String,
        val third: String,
        val thirdEnd: String,
    )

    private fun timePoints(): TimePoints {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 10)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        cal.add(Calendar.WEEK_OF_YEAR, 1) // start in the future
        val first = icsDateFormat.format(cal.time)
        cal.add(Calendar.HOUR_OF_DAY, 1)
        val firstEnd = icsDateFormat.format(cal.time)
        cal.add(Calendar.HOUR_OF_DAY, -1)
        cal.add(Calendar.WEEK_OF_YEAR, 1)
        val second = icsDateFormat.format(cal.time)
        cal.add(Calendar.HOUR_OF_DAY, 1)
        val secondEnd = icsDateFormat.format(cal.time)
        cal.add(Calendar.HOUR_OF_DAY, -1)
        cal.add(Calendar.WEEK_OF_YEAR, 1)
        val third = icsDateFormat.format(cal.time)
        cal.add(Calendar.HOUR_OF_DAY, 1)
        val thirdEnd = icsDateFormat.format(cal.time)
        return TimePoints(first, firstEnd, second, secondEnd, third, thirdEnd)
    }

    private fun masterIcs(uid: String, t: TimePoints, sequence: Int = 0): String =
        """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Recurring Edit Fixture//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(Date())}
DTSTART:${t.first}
DTEND:${t.firstEnd}
RRULE:FREQ=WEEKLY;COUNT=5
SUMMARY:Recurring fixture (master)
ORGANIZER;CN=Org Synthetic:mailto:organizer.synthetic@example.test
ATTENDEE;CN=Alice Synthetic;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:alice.synthetic@example.test
ATTENDEE;CN=Bob Synthetic;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT:mailto:bob.synthetic@example.test
SEQUENCE:$sequence
END:VEVENT
END:VCALENDAR
        """.trimIndent()

    private fun fixtureDir(): File {
        val root = resolveProjectRoot()
        val dir = File(root, "docs/fixtures/recurring-edit/${config.name.lowercase()}")
        dir.mkdirs()
        return dir
    }

    private fun writeFixture(name: String, body: String) {
        val redacted = FixtureRedactor.redact(body)
        FixtureRedactor.assertSafe(redacted, label = "${config.name}/$name")
        File(fixtureDir(), name).writeText(redacted)
    }

    private fun writeNote(name: String, lines: List<String>) {
        val body = lines.joinToString("\n", postfix = "\n")
        File(fixtureDir(), name).writeText(FixtureRedactor.redact(body))
    }

    @Test
    fun `01 capture master create roundtrip`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-create"
        val t = timePoints()
        val ics = masterIcs(uid, t)

        writeFixture("01-master-PUT.ics", ics)

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assumeTrue(
            "Create failed on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess()
        )
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        val fetchResult = client!!.fetchEvent(url)
        assumeTrue(
            "Fetch failed on ${config.name}",
            fetchResult.isSuccess()
        )
        val storedIcs = fetchResult.getOrNull()!!.icalData

        writeFixture("01-master-stored.ics", storedIcs)
        writeNote(
            "01-master-summary.md",
            buildSummary(
                title = "Master create round-trip",
                put = ics,
                stored = storedIcs,
                etagBefore = "(none)",
                etagAfter = etag,
                fetchedEtag = fetchResult.getOrNull()!!.etag
            )
        )
    }

    @Test
    fun `02 capture this-event-only edit`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-this"
        val t = timePoints()

        // Step 1 — create master.
        val masterIcs = masterIcs(uid, t)
        val createResult = client!!.createEvent(calendarUrl!!, uid, masterIcs)
        assumeTrue("create failed", createResult.isSuccess())
        val (url, masterEtag) = createResult.getOrNull()!!
        trackEvent(url, masterEtag)

        // Step 2 — PUT same href with master+exception VEVENTs (RFC 5545
        // single-resource recurrence model). Exception modifies SUMMARY
        // and shifts time by +4h on the third occurrence.
        val exceptionStart = t.third.replace("T10", "T14")
        val exceptionEnd = t.third.replace("T10", "T15")
        val combinedIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Recurring Edit Fixture//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(Date())}
DTSTART:${t.first}
DTEND:${t.firstEnd}
RRULE:FREQ=WEEKLY;COUNT=5
SUMMARY:Recurring fixture (master)
ORGANIZER;CN=Org Synthetic:mailto:organizer.synthetic@example.test
ATTENDEE;CN=Alice Synthetic;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:alice.synthetic@example.test
ATTENDEE;CN=Bob Synthetic;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT:mailto:bob.synthetic@example.test
SEQUENCE:1
END:VEVENT
BEGIN:VEVENT
UID:$uid
RECURRENCE-ID:${t.third}
DTSTAMP:${icsDateFormat.format(Date())}
DTSTART:$exceptionStart
DTEND:$exceptionEnd
SUMMARY:Recurring fixture (third moved)
ORGANIZER;CN=Org Synthetic:mailto:organizer.synthetic@example.test
ATTENDEE;CN=Alice Synthetic;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:alice.synthetic@example.test
ATTENDEE;CN=Bob Synthetic;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT:mailto:bob.synthetic@example.test
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        writeFixture("02-this-event-PUT.ics", combinedIcs)

        assertOurEvent(url)
        val updateResult = client!!.updateEvent(url, combinedIcs, masterEtag)
        if (!updateResult.isSuccess()) {
            writeNote(
                "02-this-event-summary.md",
                listOf(
                    "## ${config.name} — THIS_EVENT (RECURRENCE-ID exception)",
                    "",
                    "**Outcome:** PUT REJECTED",
                    "**Server message:** ${(updateResult as? CalDavResult.Error)?.message}",
                    "",
                    "Server does not accept master+exception in a single resource.",
                )
            )
            return@runBlocking
        }
        val newEtag = updateResult.getOrNull()!!
        trackEvent(url, newEtag)

        val fetchResult = client!!.fetchEvent(url)
        assumeTrue("fetch failed", fetchResult.isSuccess())
        val storedIcs = fetchResult.getOrNull()!!.icalData

        writeFixture("02-this-event-stored.ics", storedIcs)
        writeNote(
            "02-this-event-summary.md",
            buildSummary(
                title = "THIS_EVENT — single-occurrence exception via RECURRENCE-ID",
                put = combinedIcs,
                stored = storedIcs,
                etagBefore = masterEtag,
                etagAfter = newEtag,
                fetchedEtag = fetchResult.getOrNull()!!.etag
            )
        )
    }

    @Test
    fun `03 capture this-and-future split`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-future-master"
        val splitUid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-future-split"
        val t = timePoints()

        // Step 1 — create master.
        val masterIcs = masterIcs(uid, t)
        val createResult = client!!.createEvent(calendarUrl!!, uid, masterIcs)
        assumeTrue("create failed", createResult.isSuccess())
        val (url, masterEtag) = createResult.getOrNull()!!
        trackEvent(url, masterEtag)

        // Step 2 — truncate master with UNTIL = third occurrence start - 1
        // second. Per `splitSeries` (EventWriter.kt:435).
        val untilCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        untilCal.time = icsDateFormat.parse(t.third)!!
        untilCal.add(Calendar.SECOND, -1)
        val untilTs = icsDateFormat.format(untilCal.time)

        val truncatedMasterIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Recurring Edit Fixture//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(Date())}
DTSTART:${t.first}
DTEND:${t.firstEnd}
RRULE:FREQ=WEEKLY;COUNT=5;UNTIL=$untilTs
SUMMARY:Recurring fixture (master)
ORGANIZER;CN=Org Synthetic:mailto:organizer.synthetic@example.test
ATTENDEE;CN=Alice Synthetic;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:alice.synthetic@example.test
ATTENDEE;CN=Bob Synthetic;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT:mailto:bob.synthetic@example.test
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()
        writeFixture("03-this-and-future-master-PUT.ics", truncatedMasterIcs)

        assertOurEvent(url)
        val updateMasterResult = client!!.updateEvent(url, truncatedMasterIcs, masterEtag)
        // We deliberately keep both COUNT and UNTIL in the truncated PUT
        // to capture how each server reacts — RFC 5545 says these are
        // mutually exclusive.
        val masterFollowupEtag = if (updateMasterResult.isSuccess()) {
            val e = updateMasterResult.getOrNull()!!
            trackEvent(url, e)
            e
        } else {
            // Retry with COUNT removed (matches how splitSeries should
            // behave once we add the strip).
            val cleanIcs = truncatedMasterIcs.replace(";COUNT=5", "")
            assertOurEvent(url)
            val retry = client!!.updateEvent(url, cleanIcs, masterEtag)
            if (retry.isSuccess()) {
                writeFixture("03-this-and-future-master-PUT.ics", cleanIcs)
                val e = retry.getOrNull()!!
                trackEvent(url, e)
                e
            } else {
                writeNote(
                    "03-this-and-future-summary.md",
                    listOf(
                        "## ${config.name} — THIS_AND_FUTURE (split)",
                        "",
                        "**Outcome:** Master truncate REJECTED",
                        "**With COUNT+UNTIL:** ${(updateMasterResult as? CalDavResult.Error)?.message}",
                        "**With UNTIL only:** ${(retry as? CalDavResult.Error)?.message}",
                    )
                )
                return@runBlocking
            }
        }

        val fetchedMaster = client!!.fetchEvent(url)
        if (fetchedMaster.isSuccess()) {
            writeFixture("03-this-and-future-master-stored.ics", fetchedMaster.getOrNull()!!.icalData)
        }

        // Step 3 — create new series for THIS+FUTURE starting at the
        // third occurrence with shifted SUMMARY.
        val splitMasterIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Recurring Edit Fixture//EN
BEGIN:VEVENT
UID:$splitUid
DTSTAMP:${icsDateFormat.format(Date())}
DTSTART:${t.third}
DTEND:${t.thirdEnd}
RRULE:FREQ=WEEKLY;COUNT=3
SUMMARY:Recurring fixture (split — future)
ORGANIZER;CN=Org Synthetic:mailto:organizer.synthetic@example.test
ATTENDEE;CN=Alice Synthetic;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:alice.synthetic@example.test
ATTENDEE;CN=Bob Synthetic;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT:mailto:bob.synthetic@example.test
SEQUENCE:0
END:VEVENT
END:VCALENDAR
        """.trimIndent()
        writeFixture("03-this-and-future-split-PUT.ics", splitMasterIcs)

        val splitResult = client!!.createEvent(calendarUrl!!, splitUid, splitMasterIcs)
        if (splitResult.isSuccess()) {
            val (splitUrl, splitEtag) = splitResult.getOrNull()!!
            trackEvent(splitUrl, splitEtag)
            val fetchedSplit = client!!.fetchEvent(splitUrl)
            if (fetchedSplit.isSuccess()) {
                writeFixture("03-this-and-future-split-stored.ics", fetchedSplit.getOrNull()!!.icalData)
            }
            writeNote(
                "03-this-and-future-summary.md",
                buildSummary(
                    title = "THIS_AND_FUTURE — split master + new series",
                    put = "(see 03-*-PUT.ics files)",
                    stored = "(see 03-*-stored.ics files)",
                    etagBefore = masterEtag,
                    etagAfter = "$masterFollowupEtag (master), $splitEtag (split)",
                    fetchedEtag = fetchedSplit.getOrNull()?.etag ?: "(error)"
                )
            )
        } else {
            writeNote(
                "03-this-and-future-summary.md",
                listOf(
                    "## ${config.name} — THIS_AND_FUTURE (split)",
                    "",
                    "**Outcome:** master truncate OK; new series CREATE REJECTED",
                    "**Error:** ${(splitResult as? CalDavResult.Error)?.message}",
                )
            )
        }
    }

    @Test
    fun `04 capture all-events edit`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-all"
        val t = timePoints()

        val masterIcs = masterIcs(uid, t)
        val createResult = client!!.createEvent(calendarUrl!!, uid, masterIcs)
        assumeTrue("create failed", createResult.isSuccess())
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        // Update SUMMARY + LOCATION to drive a server-detectable diff.
        val updatedIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Recurring Edit Fixture//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(Date())}
DTSTART:${t.first}
DTEND:${t.firstEnd}
RRULE:FREQ=WEEKLY;COUNT=5
SUMMARY:Recurring fixture (renamed for all)
LOCATION:Conference Room A
ORGANIZER;CN=Org Synthetic:mailto:organizer.synthetic@example.test
ATTENDEE;CN=Alice Synthetic;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:alice.synthetic@example.test
ATTENDEE;CN=Bob Synthetic;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT:mailto:bob.synthetic@example.test
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        writeFixture("04-all-events-PUT.ics", updatedIcs)

        assertOurEvent(url)
        val updateResult = client!!.updateEvent(url, updatedIcs, etag)
        assumeTrue("update failed", updateResult.isSuccess())
        val newEtag = updateResult.getOrNull()!!
        trackEvent(url, newEtag)

        val fetchResult = client!!.fetchEvent(url)
        assumeTrue("fetch failed", fetchResult.isSuccess())
        val storedIcs = fetchResult.getOrNull()!!.icalData

        writeFixture("04-all-events-stored.ics", storedIcs)
        writeNote(
            "04-all-events-summary.md",
            buildSummary(
                title = "ALL_EVENTS — PUT to master with new fields",
                put = updatedIcs,
                stored = storedIcs,
                etagBefore = etag,
                etagAfter = newEtag,
                fetchedEtag = fetchResult.getOrNull()!!.etag
            )
        )
    }

    private fun buildSummary(
        title: String,
        put: String,
        stored: String,
        etagBefore: String,
        etagAfter: String,
        fetchedEtag: String?
    ): List<String> {
        val putLines = put.lines().filter { it.isNotBlank() }.toSet()
        val storedLines = stored.lines().filter { it.isNotBlank() }.toSet()
        val added = (storedLines - putLines).filterNot { it.startsWith("DTSTAMP") || it.startsWith("PRODID") }
        val removed = (putLines - storedLines).filterNot { it.startsWith("DTSTAMP") || it.startsWith("PRODID") }
        val unfoldedStored = stored.replace(Regex("""\r?\n[ \t]"""), "")
        val foldedAttendee = unfoldedStored.lines().any {
            it.startsWith("ATTENDEE") && it.length > 75
        }
        return buildList {
            add("## ${config.name} — $title")
            add("")
            add("**ETag before:** $etagBefore")
            add("**ETag after PUT:** $etagAfter")
            add("**ETag on fetch:** ${fetchedEtag ?: "(none)"}")
            add("**Body bytes (PUT → stored):** ${put.length} → ${stored.length}")
            add("**Server line-folds long ATTENDEE:** $foldedAttendee")
            add("")
            add("### Lines added by server")
            if (added.isEmpty()) add("*(none)*") else added.sorted().forEach { add("- `$it`") }
            add("")
            add("### Lines removed by server")
            if (removed.isEmpty()) add("*(none)*") else removed.sorted().forEach { add("- `$it`") }
        }
    }
}

/**
 * Strict redactor for fixture output. Two passes:
 *   1. Replace any non-`@example.test` email with the synthetic
 *      placeholder.
 *   2. Mask account-id-shaped path segments (long alphanumerics in URL
 *      portions of properties), opaque tag values (Schedule-Tag /
 *      ETag), and `CN=` display-names.
 *
 * [assertSafe] re-scans the redacted body and throws if anything still
 * looks like a real email address — fixtures are never written to disk
 * if redaction fails.
 */
internal object FixtureRedactor {
    private val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val cnRegex = Regex("""CN=[^;:]+""")
    private val accountIdRegex = Regex("""/(\d{6,}|[A-F0-9-]{20,})/""")
    private val opaqueTagRegex = Regex(""""[A-Za-z0-9+/=:_-]{16,}"""")

    /**
     * Lines whose value is server- or account-specific PII (calendar
     * name, default timezone, etc). Server-stored fixtures often echo
     * these from PROPFIND or account settings. Replaced wholesale with a
     * `<redacted>` token rather than stripped so the diff still shows
     * the property *was* present.
     */
    private val piiPropertyPrefixes = listOf(
        "X-WR-CALNAME",
        "X-WR-CALDESC",
        "X-WR-TIMEZONE",
        "X-WR-RELCALID",
        "X-PUBLISHED-TTL",
        "X-APPLE-CALENDAR-COLOR",
        "X-APPLE-LANGUAGE",
        "X-APPLE-LOCALE",
    )

    fun redact(text: String): String {
        var out = text
        out = emailRegex.replace(out) { match ->
            val email = match.value
            if (email.endsWith("@example.test")) email else "redacted@example.test"
        }
        out = cnRegex.replace(out) { match ->
            val name = match.value.removePrefix("CN=")
            // Keep CN= when it's already a synthetic placeholder.
            if (name.contains("Synthetic", ignoreCase = true)) match.value else "CN=Redacted"
        }
        out = accountIdRegex.replace(out, "/<redacted-id>/")
        out = opaqueTagRegex.replace(out, "\"<redacted-tag>\"")
        // Match each PII property anywhere on a line (handles raw ICS
        // lines AND markdown-wrapped diff entries like ``- `X-WR-…` ``).
        for (prefix in piiPropertyPrefixes) {
            out = Regex("""($prefix):[^\n`]*""").replace(out, "$1:<redacted>")
        }
        return out
    }

    fun assertSafe(text: String, label: String) {
        val leakage = emailRegex.find(text)?.value
        if (leakage != null && !leakage.endsWith("@example.test")) {
            throw IllegalStateException(
                "Fixture redaction failed for $label — non-synthetic email leaked: $leakage"
            )
        }
        for (prefix in piiPropertyPrefixes) {
            val match = Regex("""$prefix:[^\n`]*""").findAll(text).firstOrNull { m ->
                !m.value.endsWith(":<redacted>")
            }
            if (match != null) {
                throw IllegalStateException(
                    "Fixture redaction failed for $label — $prefix leaked: ${match.value}"
                )
            }
        }
    }
}
