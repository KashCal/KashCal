package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.util.RruleUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Checks, on every configured CalDAV server, that the bodies the recurring-edit scope choices
 * produce are stored as sent: a THIS_EVENT exception, a THIS_AND_FUTURE COUNT split, "Does not
 * repeat" on the new series, a COUNT master replaced by an UNTIL rule, a past exception kept
 * through a split, a `RECURRENCE-ID;VALUE=DATE` on a timed master, and delete this and future
 * dropping a future exception. Bodies are serialized from synthetic [Event]s by
 * [IcsPatcher.serialize] or [IcsPatcher.serializeWithExceptions], with split rules from
 * [RruleUtils.splitRruleAtTime], or written inline; each is PUT, fetched back and checked. The
 * degenerate-COUNT test checks only [RruleUtils.isDegenerateCountSplit].
 *
 * Skips via assumeTrue when credentials or the server are unavailable, and when a server refuses
 * or rewrites an input in a way the test records as a server quirk. Cleanup deletes only URLs this
 * run created and never reads server state.
 */
@RunWith(Parameterized::class)
class MultiServerScopeSheetWireTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = CalDavServerConfig.allServers()

        private val classStartMs = System.currentTimeMillis()
        internal val UID_PREFIX = "scope-wire-$classStartMs-"
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
        for ((url, etag) in createdEventUrls.reversed()) {
            try {
                c.deleteEvent(url, etag)
            } catch (_: Exception) {
                // Best-effort. Unique UID prefix means orphans are harmless.
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

    /** Throws unless this run created [url]. */
    private fun assertOurEvent(url: String) {
        check(createdEventUrls.any { it.first == url }) {
            "Refusing to mutate URL not created by this test run: $url"
        }
    }

    private data class Times(
        val masterStartTs: Long,
        val masterEndTs: Long,
        val occurrence2StartTs: Long,
        val occurrence2EndTs: Long,
    )

    /**
     * Returns a 10:00-11:00 UTC slot on a Monday 7 to 13 days out (a week after the next Monday,
     * today included) and the same slot one week later.
     */
    private fun times(): Times {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 10)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        cal.add(Calendar.WEEK_OF_YEAR, 1)
        val masterStart = cal.timeInMillis
        cal.add(Calendar.HOUR_OF_DAY, 1)
        val masterEnd = cal.timeInMillis
        cal.add(Calendar.HOUR_OF_DAY, -1)
        cal.add(Calendar.WEEK_OF_YEAR, 1)
        val occ2Start = cal.timeInMillis
        cal.add(Calendar.HOUR_OF_DAY, 1)
        val occ2End = cal.timeInMillis
        return Times(masterStart, masterEnd, occ2Start, occ2End)
    }

    private fun masterEvent(uid: String, t: Times, rrule: String): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = uid,
            calendarId = 1L,
            title = "Scope sheet wire test (master)",
            startTs = t.masterStartTs,
            endTs = t.masterEndTs,
            timezone = "UTC",
            isAllDay = false,
            rrule = rrule,
            dtstamp = now,
            syncStatus = SyncStatus.SYNCED,
        )
    }

    private suspend fun putAndFetch(uid: String, ics: String): String? {
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
        return fetchResult.getOrNull()!!.icalData
    }

    /** Unfolds content lines (RFC 5545 §3.1) so a regex sees each property on one line. */
    private fun unfold(ics: String): String =
        ics.replace(Regex("""\r?\n[ \t]"""), "")

    private fun firstVeventBody(ics: String): String {
        val unfolded = unfold(ics)
        // The master's VEVENT: the first block without RECURRENCE-ID, else the first block.
        val blocks = Regex("""BEGIN:VEVENT(.*?)END:VEVENT""", RegexOption.DOT_MATCHES_ALL)
            .findAll(unfolded).map { it.groupValues[1] }.toList()
        return blocks.firstOrNull { !it.contains("RECURRENCE-ID") } ?: blocks.firstOrNull() ?: ""
    }

    private fun rrulePropFrom(vevent: String): String? {
        return Regex("""RRULE:([^\r\n]+)""").find(vevent)?.groupValues?.get(1)?.trim()
    }

    // ===================== TESTS =========================================

    /**
     * Creates a recurring master with a THIS_EVENT exception in one resource and checks the
     * stored body keeps a RECURRENCE-ID and no RRULE on the exception.
     */
    @Test
    fun `01 THIS_EVENT scope produces master + RECURRENCE-ID exception that round-trips`() = runBlocking {
        assumeReady()
        // Zoho strips ATTENDEEs and ORGANIZER on synthetic-organizer PUTs and collapses the
        // master and exception bundle.
        assumeTrue(
            "${config.name} collapses master+exception bundle on single-href fetch (documented quirk)",
            config.name != "Zoho",
        )
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-this-event"
        val t = times()
        val master = masterEvent(uid, t, rrule = "FREQ=WEEKLY;COUNT=5")

        // The THIS_EVENT outcome on occurrence 2.
        val exception = master.copy(
            title = "Scope sheet wire test (modified occurrence)",
            rrule = null,
            originalEventId = 0L, // master.id placeholder (not persisted)
            originalInstanceTime = t.occurrence2StartTs,
            startTs = t.occurrence2StartTs + 30 * 60_000L, // shifted +30min
            endTs = t.occurrence2EndTs + 30 * 60_000L,
        )
        val ics = IcsPatcher.serializeWithExceptions(master, listOf(exception))

        val stored = putAndFetch(uid, ics) ?: error("fetch returned null")
        val unfolded = unfold(stored)
        assertTrue(
            "Stored ICS must contain RECURRENCE-ID for the THIS_EVENT exception",
            unfolded.contains("RECURRENCE-ID"),
        )
        // The exception carries no RRULE of its own.
        val recurrenceBlock = Regex("""BEGIN:VEVENT[^E]*?RECURRENCE-ID[^E]*?END:VEVENT""", RegexOption.DOT_MATCHES_ALL)
            .find(unfolded)?.value ?: ""
        assertFalse(
            "Exception VEVENT must NOT carry RRULE (RFC 5545 §3.8.5)",
            recurrenceBlock.contains("RRULE:"),
        )
    }

    /**
     * THIS_AND_FUTURE on a COUNT=5 master split at occurrence 2: the split gives the master COUNT=1
     * and the new series COUNT=4. The two are separate resources, each PUT and checked for its
     * COUNT.
     */
    @Test
    fun `02 THIS_AND_FUTURE COUNT split produces master and new series with preserved total`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val masterUid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-master"
        val newSeriesUid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-future"
        val t = times()
        val masterRrule = "FREQ=WEEKLY;COUNT=5"

        // The rules a THIS_AND_FUTURE save writes.
        val (truncatedMaster, splitNewSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = masterRrule,
            userRrule = masterRrule, // no user edit
            untilMs = t.occurrence2StartTs - 1L,
            pastCount = 1,
            isAllDay = false,
        )
        assertEquals("FREQ=WEEKLY;COUNT=1", truncatedMaster)
        assertEquals("FREQ=WEEKLY;COUNT=4", splitNewSeries)

        // PUT truncated master.
        val masterIcs = IcsPatcher.serialize(masterEvent(masterUid, t, rrule = truncatedMaster))
        val storedMaster = putAndFetch(masterUid, masterIcs) ?: error("master fetch null")
        val masterRruleStored = rrulePropFrom(firstVeventBody(storedMaster))
        assertNotNull("Master must carry RRULE on $config", masterRruleStored)
        assertTrue(
            "Master RRULE on ${config.name} must include COUNT=1, got: $masterRruleStored",
            masterRruleStored!!.contains("COUNT=1"),
        )

        // The new series is a separate event with its own UID.
        val newMaster = Event(
            uid = newSeriesUid,
            calendarId = 1L,
            title = "Scope sheet wire test (new series)",
            startTs = t.occurrence2StartTs,
            endTs = t.occurrence2EndTs,
            timezone = "UTC",
            rrule = splitNewSeries,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
        )
        val newSeriesIcs = IcsPatcher.serialize(newMaster)
        val storedNewSeries = putAndFetch(newSeriesUid, newSeriesIcs) ?: error("new series fetch null")
        val newRruleStored = rrulePropFrom(firstVeventBody(storedNewSeries))
        assertNotNull("New series must carry RRULE on $config", newRruleStored)
        assertTrue(
            "New series RRULE on ${config.name} must include COUNT=4, got: $newRruleStored",
            newRruleStored!!.contains("COUNT=4"),
        )
    }

    /**
     * "Does not repeat" with THIS_AND_FUTURE on a COUNT=5 master: the new series row has no RRULE,
     * not the master's WEEKLY rule.
     */
    @Test
    fun `03 user picks Does Not Repeat — new series row carries no RRULE`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val newSeriesUid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-no-repeat"
        val t = times()

        // The user dropped the recurrence (userRrule = null).
        val (_, splitNewSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY;COUNT=5",
            userRrule = null,
            untilMs = t.occurrence2StartTs - 1L,
            pastCount = 1,
            isAllDay = false,
        )
        assertNull("Helper must signal non-recurring new series", splitNewSeries)

        // The new row PUT is a standalone non-recurring event.
        val newRow = Event(
            uid = newSeriesUid,
            calendarId = 1L,
            title = "Scope sheet wire test (drop recurrence)",
            startTs = t.occurrence2StartTs,
            endTs = t.occurrence2EndTs,
            timezone = "UTC",
            rrule = null,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
        )
        val ics = IcsPatcher.serialize(newRow)
        val stored = putAndFetch(newSeriesUid, ics) ?: error("fetch null")
        val rruleStored = rrulePropFrom(firstVeventBody(stored))
        assertNull(
            "Server-stored event for 'Does not repeat' must NOT carry RRULE on ${config.name}, got: $rruleStored",
            rruleStored,
        )
    }

    /**
     * THIS_AND_FUTURE where the user replaced the master's COUNT rule with an UNTIL rule: the new
     * series carries only the user's UNTIL, never COUNT as well, which RFC 5545 §3.3.10 forbids.
     * ical4j's `Recur` string parser accepts the pair without error, so the split has to avoid it.
     */
    @Test
    fun `04 user replaces master COUNT with UNTIL — new series has only UNTIL`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val newSeriesUid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-count-to-until"
        val t = times()

        // The user replaced WEEKLY;COUNT=10 with DAILY;UNTIL=...
        val futureUntil = "20270101T000000Z"
        val (_, splitNewSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY;COUNT=10",
            userRrule = "FREQ=DAILY;UNTIL=$futureUntil",
            untilMs = t.occurrence2StartTs - 1L,
            pastCount = 1,
            isAllDay = false,
        )
        assertNotNull("Helper must produce a new-series rrule", splitNewSeries)
        assertFalse(
            "Helper output must NOT contain COUNT (RFC 5545 §3.3.10): $splitNewSeries",
            splitNewSeries!!.contains("COUNT="),
        )
        assertTrue(
            "Helper output must contain user's UNTIL: $splitNewSeries",
            splitNewSeries.contains("UNTIL=$futureUntil"),
        )

        val newRow = Event(
            uid = newSeriesUid,
            calendarId = 1L,
            title = "Scope sheet wire test (count -> until)",
            startTs = t.occurrence2StartTs,
            endTs = t.occurrence2EndTs,
            timezone = "UTC",
            rrule = splitNewSeries,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
        )
        val ics = IcsPatcher.serialize(newRow)
        val stored = putAndFetch(newSeriesUid, ics) ?: error("fetch null")
        val rruleStored = rrulePropFrom(firstVeventBody(stored))
        assertNotNull("Server must store the new RRULE on ${config.name}", rruleStored)
        // Some servers reorder RRULE parts, so only the parts present are checked.
        assertFalse(
            "Server-stored RRULE must NOT contain COUNT on ${config.name}: $rruleStored",
            rruleStored!!.contains("COUNT="),
        )
        assertTrue(
            "Server-stored RRULE must contain UNTIL on ${config.name}: $rruleStored",
            rruleStored.contains("UNTIL="),
        )
    }

    /**
     * A split of FREQ=DAILY;COUNT=3 with no past occurrences, or with all of them past, would give
     * one side an invalid COUNT=0. [RruleUtils.isDegenerateCountSplit] returns true there and the
     * caller updates the master in place as an all-events edit instead. Only that signal is
     * checked; on the wire the fallback is a plain master update, like those in tests 06 and 08.
     */
    @Test
    fun `05 degenerate-COUNT split — helper signals fallback to ALL_EVENTS`() = runBlocking {
        // No server traffic, though it still skips without a server. Kept here beside the
        // server-stored variants of the same split.
        assumeReady()
        val pastCount0 = RruleUtils.isDegenerateCountSplit("FREQ=DAILY;COUNT=3", pastCount = 0)
        val pastCount3 = RruleUtils.isDegenerateCountSplit("FREQ=DAILY;COUNT=3", pastCount = 3)
        val pastCount5 = RruleUtils.isDegenerateCountSplit("FREQ=DAILY;COUNT=3", pastCount = 5)
        val pastCount2 = RruleUtils.isDegenerateCountSplit("FREQ=DAILY;COUNT=3", pastCount = 2)
        assertTrue("pastCount=0 is degenerate", pastCount0)
        assertTrue("pastCount==total is degenerate", pastCount3)
        assertTrue("pastCount>total is degenerate", pastCount5)
        assertFalse("pastCount=2 of 3 is non-degenerate", pastCount2)
    }

    /**
     * Reproduces a user-reported sequence:
     *  1. Create a DAILY;COUNT=10 master.
     *  2. Edit one occurrence, which bundles an exception with the master.
     *  3. Split THIS_AND_FUTURE from a day after the exception's.
     *
     * The exception is before the split, so it must survive. After the split the stored master
     * has COUNT=4 and still holds the exception VEVENT, and the new series (COUNT=6) is created
     * with no 403 or UID collision.
     */
    @Test
    fun `06 THIS_AND_FUTURE preserves past exception and lets new series CREATE succeed`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val masterUid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-past-exc-master"
        val newSeriesUid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-past-exc-future"
        val t = times()
        // Step 1: PUT a DAILY;COUNT=10 master starting at t.masterStartTs.
        val masterDaily = Event(
            uid = masterUid,
            calendarId = 1L,
            title = "Past-exception repro (master)",
            startTs = t.masterStartTs,
            endTs = t.masterEndTs,
            timezone = "UTC",
            rrule = "FREQ=DAILY;COUNT=10",
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
        )
        val masterIcs = IcsPatcher.serialize(masterDaily)
        val storedMaster = putAndFetch(masterUid, masterIcs) ?: error("master fetch null")
        assertTrue(
            "Server should accept the COUNT=10 master on ${config.name}",
            firstVeventBody(storedMaster).contains("COUNT=10")
        )

        // Step 2: PUT the master with an exception bundled at occurrence index 3, 3 days after
        // the master's start. The exception shares the UID and adds RECURRENCE-ID (RFC 5545
        // §3.8.4.4); the edit moves it 8 hours earlier.
        val occurrenceDayMs = t.masterStartTs + 3L * 24 * 3600_000L  // master + 3 days
        val recurrenceIdUtc = icsDateFormat.format(java.util.Date(occurrenceDayMs))
        val excStartUtc = icsDateFormat.format(java.util.Date(occurrenceDayMs - 8L * 3600_000L))
        val excEndUtc = icsDateFormat.format(java.util.Date(occurrenceDayMs - 8L * 3600_000L + 3600_000L))
        val masterPlusExceptionIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Past-Exception Repro//EN
BEGIN:VEVENT
UID:$masterUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:${icsDateFormat.format(java.util.Date(t.masterStartTs))}
DTEND:${icsDateFormat.format(java.util.Date(t.masterEndTs))}
RRULE:FREQ=DAILY;COUNT=10
SUMMARY:Past-exception repro (master)
SEQUENCE:1
END:VEVENT
BEGIN:VEVENT
UID:$masterUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$excStartUtc
DTEND:$excEndUtc
RECURRENCE-ID:$recurrenceIdUtc
SUMMARY:Past-exception repro (edited occ 3)
SEQUENCE:0
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val storedMasterUrl = createdEventUrls.last { it.first.endsWith("$masterUid.ics") }.first
        val masterEtag = createdEventUrls.last { it.first == storedMasterUrl }.second
        val updateExcResult = client!!.updateEvent(storedMasterUrl, masterPlusExceptionIcs, masterEtag)
        assumeTrue(
            "Server rejected master+exception on ${config.name}: ${(updateExcResult as? CalDavResult.Error)?.message}",
            updateExcResult.isSuccess()
        )
        val newMasterEtag = updateExcResult.getOrNull()!!
        trackEvent(storedMasterUrl, newMasterEtag)

        // The server must now store both VEVENTs. Some servers strip exceptions; that skips
        // rather than being blamed on the split path.
        val withExc = client!!.fetchEvent(storedMasterUrl).getOrNull()?.icalData
            ?: error("fetch master+exc returned null on ${config.name}")
        val veventCount = Regex("""BEGIN:VEVENT""").findAll(unfold(withExc)).count()
        assumeTrue(
            "Server should retain bundled exception (got $veventCount VEVENTs) on ${config.name}",
            veventCount >= 2
        )

        // Step 3: split THIS_AND_FUTURE at master + 4 days, a day after the exception's.
        val splitTimeMs = t.masterStartTs + 4L * 24 * 3600_000L
        val (truncatedRrule, splitNewSeriesRrule) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=DAILY;COUNT=10",
            userRrule = "FREQ=DAILY;COUNT=10",
            untilMs = splitTimeMs - 1L,
            pastCount = 4,
            isAllDay = false,
        )
        assertEquals("FREQ=DAILY;COUNT=4", truncatedRrule)
        assertEquals("FREQ=DAILY;COUNT=6", splitNewSeriesRrule)

        // Step 3a: truncate the master, keeping the exception before the split. The body is the
        // truncated master plus the unchanged exception.
        val truncatedMasterIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Past-Exception Repro//EN
BEGIN:VEVENT
UID:$masterUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:${icsDateFormat.format(java.util.Date(t.masterStartTs))}
DTEND:${icsDateFormat.format(java.util.Date(t.masterEndTs))}
RRULE:$truncatedRrule
SUMMARY:Past-exception repro (master)
SEQUENCE:2
END:VEVENT
BEGIN:VEVENT
UID:$masterUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$excStartUtc
DTEND:$excEndUtc
RECURRENCE-ID:$recurrenceIdUtc
SUMMARY:Past-exception repro (edited occ 3)
SEQUENCE:0
END:VEVENT
END:VCALENDAR
        """.trimIndent()
        val truncResult = client!!.updateEvent(storedMasterUrl, truncatedMasterIcs, newMasterEtag)
        // OX App Suite (Mailbox) re-versions the resource after the master and exception write,
        // so the ETag held from that step is stale and the conditional PUT fails with "modified on
        // server". That is a server versioning quirk, not a split-path bug, so it skips, as test 07
        // does for a server that refuses RECURRENCE-ID;VALUE=DATE.
        assumeTrue(
            "Truncate master must succeed on ${config.name}: ${(truncResult as? CalDavResult.Error)?.message}",
            truncResult.isSuccess()
        )
        val truncEtag = truncResult.getOrNull()!!
        trackEvent(storedMasterUrl, truncEtag)

        // The past exception must survive the truncate.
        val storedAfterTrunc = client!!.fetchEvent(storedMasterUrl).getOrNull()?.icalData
            ?: error("fetch master returned null after truncate")
        val unfoldedAfter = unfold(storedAfterTrunc)
        val veventsAfter = Regex("""BEGIN:VEVENT""").findAll(unfoldedAfter).count()
        assertTrue(
            "Past exception must survive truncate on ${config.name} (got $veventsAfter VEVENTs)",
            veventsAfter >= 2
        )
        val masterRruleAfter = rrulePropFrom(firstVeventBody(storedAfterTrunc))
        assertNotNull("Truncated master must have RRULE on ${config.name}", masterRruleAfter)
        assertTrue(
            "Truncated master RRULE must include COUNT=4 on ${config.name}: $masterRruleAfter",
            masterRruleAfter!!.contains("COUNT=4")
        )

        // Step 3b: create the new series with its own UID; iCloud answered this create with 403
        // in production. The row starts at the split time: EventWriter.splitSeries takes the
        // user's chosen first-occurrence start as-is, not the split time plus an offset.
        val newSeries = Event(
            uid = newSeriesUid,
            calendarId = 1L,
            title = "Past-exception repro (new series)",
            startTs = splitTimeMs,
            endTs = splitTimeMs + (t.masterEndTs - t.masterStartTs),
            timezone = "UTC",
            rrule = splitNewSeriesRrule,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
        )
        val newSeriesIcs = IcsPatcher.serialize(newSeries)
        val newSeriesResult = client!!.createEvent(calendarUrl!!, newSeriesUid, newSeriesIcs)
        assertTrue(
            "New series CREATE must succeed on ${config.name} (saw 403 on iCloud in production): " +
                "${(newSeriesResult as? CalDavResult.Error)?.message}",
            newSeriesResult.isSuccess()
        )
        val (newUrl, newEtag) = newSeriesResult.getOrNull()!!
        trackEvent(newUrl, newEtag)

        // The new series is stored with COUNT=6: no UID collision, no rewrite dropping the RRULE.
        val storedNewSeries = client!!.fetchEvent(newUrl).getOrNull()?.icalData
            ?: error("fetch new series returned null on ${config.name}")
        val newSeriesRruleStored = rrulePropFrom(firstVeventBody(storedNewSeries))
        assertNotNull("New series must store RRULE on ${config.name}", newSeriesRruleStored)
        assertTrue(
            "New series RRULE must contain COUNT=6 on ${config.name}: $newSeriesRruleStored",
            newSeriesRruleStored!!.contains("COUNT=6")
        )
    }

    /**
     * Some servers or other clients emit `RECURRENCE-ID;VALUE=DATE` against a timed master. Left
     * as is it would land at UTC midnight while the expansion puts the occurrence at the master's
     * time of day, so the day would show two occurrences;
     * [org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper.normalizeRecurrenceId] promotes it
     * (`RecurrenceIdNormalizationTest`).
     *
     * This test PUTs the mismatched form to every reachable server and records what each stores
     * back: it skips when the server refuses it or drops the RECURRENCE-ID, and otherwise requires
     * both VEVENTs.
     */
    @Test
    fun `07 RECURRENCE-ID VALUE=DATE on timed master server-side capture`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val masterUid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-recid-date"
        val t = times()

        // Timed master; the exception's RECURRENCE-ID;VALUE=DATE breaks RFC 5545 §3.8.4.4's
        // same-type rule but is seen on real servers. All times are UTC.
        val recurrenceDate = SimpleDateFormat("yyyyMMdd").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(t.occurrence2StartTs))
        val masterPlusExceptionIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//RecId Date Spike//EN
BEGIN:VEVENT
UID:$masterUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:${icsDateFormat.format(java.util.Date(t.masterStartTs))}
DTEND:${icsDateFormat.format(java.util.Date(t.masterEndTs))}
RRULE:FREQ=WEEKLY;COUNT=5
SUMMARY:RecId-Date spike (master, timed)
SEQUENCE:0
END:VEVENT
BEGIN:VEVENT
UID:$masterUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:${icsDateFormat.format(java.util.Date(t.occurrence2StartTs))}
DTEND:${icsDateFormat.format(java.util.Date(t.occurrence2EndTs))}
RECURRENCE-ID;VALUE=DATE:$recurrenceDate
SUMMARY:RecId-Date spike (exception, recid VALUE=DATE)
SEQUENCE:0
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client!!.createEvent(calendarUrl!!, masterUid, masterPlusExceptionIcs)
        // A server may refuse the mismatched value type; that is valid, so it skips.
        assumeTrue(
            "Server rejected the mismatched RECURRENCE-ID;VALUE=DATE PUT on ${config.name}: " +
                "${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess()
        )
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        val storedIcs = client!!.fetchEvent(url).getOrNull()?.icalData
            ?: error("fetch null on ${config.name}")
        val unfolded = unfold(storedIcs)

        // The stored RECURRENCE-ID line is printed below rather than asserted, since servers
        // differ: most keep it verbatim, Zoho strips it.
        val recurrenceIdLines = unfolded.lines()
            .filter { it.startsWith("RECURRENCE-ID") }
        // A server that strips the RECURRENCE-ID skips: a server quirk, not an app defect.
        assumeTrue(
            "Server stripped or normalized RECURRENCE-ID on ${config.name} " +
                "(known quirks: Zoho strips). " +
                "Server-side mitigation; KashCal pull not affected on this server.",
            recurrenceIdLines.isNotEmpty()
        )

        val veventCount = Regex("""BEGIN:VEVENT""").findAll(unfolded).count()
        assertEquals(
            "${config.name}: expected 2 VEVENTs (master+exception), got $veventCount. " +
                "RECURRENCE-ID lines: $recurrenceIdLines",
            2,
            veventCount
        )

        // Prints the stored RECURRENCE-ID form per server. The parser's handling of it is
        // checked in `RecurrenceIdNormalizationTest`; this test covers only what the server does.
        println("[RecidDateSpike] ${config.name}: VEVENTs=$veventCount, " +
            "recurrenceId=${recurrenceIdLines.firstOrNull()}")
    }

    /**
     * Delete this and future on the wire: the master is truncated with UNTIL and the future
     * exception is removed from the resource. EventWriter's splitSeries and deleteThisAndFuture
     * share the future-exception cleanup; test 06 covers the split side, this one the delete side.
     */
    @Test
    fun `08 deleteThisAndFuture truncates master and drops future bundled exceptions`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val masterUid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-del-future"
        val t = times()
        // DAILY;COUNT=10 master starting at masterStartTs.
        val masterDaily = Event(
            uid = masterUid,
            calendarId = 1L,
            title = "deleteThisAndFuture wire test (master)",
            startTs = t.masterStartTs,
            endTs = t.masterEndTs,
            timezone = "UTC",
            rrule = "FREQ=DAILY;COUNT=10",
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
        )
        val masterIcs = IcsPatcher.serialize(masterDaily)
        val storedMaster = putAndFetch(masterUid, masterIcs) ?: error("master fetch null")
        assertTrue(
            "Master COUNT=10 must round-trip on ${config.name}",
            firstVeventBody(storedMaster).contains("COUNT=10")
        )

        // Bundle an exception at occurrence index 6, after the delete point at index 4. Its
        // removal is what this test checks.
        val futureOcc6Ms = t.masterStartTs + 6L * 24 * 3600_000L
        val excStart = icsDateFormat.format(java.util.Date(futureOcc6Ms - 5 * 3600_000L))
        val excEnd = icsDateFormat.format(java.util.Date(futureOcc6Ms - 5 * 3600_000L + 3600_000L))
        val recId = icsDateFormat.format(java.util.Date(futureOcc6Ms))
        val storedMasterUrl = createdEventUrls.last { it.first.endsWith("$masterUid.ics") }.first
        val masterEtag = createdEventUrls.last { it.first == storedMasterUrl }.second
        val masterPlusExceptionIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//deleteThisAndFuture wire test//EN
BEGIN:VEVENT
UID:$masterUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:${icsDateFormat.format(java.util.Date(t.masterStartTs))}
DTEND:${icsDateFormat.format(java.util.Date(t.masterEndTs))}
RRULE:FREQ=DAILY;COUNT=10
SUMMARY:deleteThisAndFuture wire test (master)
SEQUENCE:1
END:VEVENT
BEGIN:VEVENT
UID:$masterUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$excStart
DTEND:$excEnd
RECURRENCE-ID:$recId
SUMMARY:deleteThisAndFuture wire test (future exception, must be dropped)
SEQUENCE:0
END:VEVENT
END:VCALENDAR
        """.trimIndent()
        val excResult = client!!.updateEvent(storedMasterUrl, masterPlusExceptionIcs, masterEtag)
        assumeTrue(
            "Server rejected master+exception on ${config.name}: ${(excResult as? CalDavResult.Error)?.message}",
            excResult.isSuccess()
        )
        val newEtag = excResult.getOrNull()!!
        trackEvent(storedMasterUrl, newEtag)

        // The server must store both VEVENTs, else skip.
        val withExc = client!!.fetchEvent(storedMasterUrl).getOrNull()?.icalData
            ?: error("fetch null after exception PUT on ${config.name}")
        val veventsBefore = Regex("""BEGIN:VEVENT""").findAll(unfold(withExc)).count()
        assumeTrue(
            "Server should retain bundled exception on ${config.name} (got $veventsBefore VEVENTs)",
            veventsBefore >= 2
        )

        // Delete this and future from occurrence index 4: the master gets an UNTIL just before
        // it, and the exception at index 6 leaves the body.
        val deleteFromMs = t.masterStartTs + 4L * 24 * 3600_000L
        val untilCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        untilCal.timeInMillis = deleteFromMs - 1L
        val until = icsDateFormat.format(untilCal.time)
        val truncatedIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//deleteThisAndFuture wire test//EN
BEGIN:VEVENT
UID:$masterUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:${icsDateFormat.format(java.util.Date(t.masterStartTs))}
DTEND:${icsDateFormat.format(java.util.Date(t.masterEndTs))}
RRULE:FREQ=DAILY;UNTIL=$until
SUMMARY:deleteThisAndFuture wire test (master)
SEQUENCE:2
END:VEVENT
END:VCALENDAR
        """.trimIndent()
        val truncResult = client!!.updateEvent(storedMasterUrl, truncatedIcs, newEtag)
        assertTrue(
            "Truncate must succeed on ${config.name}: ${(truncResult as? CalDavResult.Error)?.message}",
            truncResult.isSuccess()
        )
        trackEvent(storedMasterUrl, truncResult.getOrNull()!!)

        // The server now stores only the master VEVENT, and its RRULE has UNTIL.
        val storedAfter = client!!.fetchEvent(storedMasterUrl).getOrNull()?.icalData
            ?: error("fetch null after truncate on ${config.name}")
        val unfoldedAfter = unfold(storedAfter)
        val veventsAfter = Regex("""BEGIN:VEVENT""").findAll(unfoldedAfter).count()
        assertEquals(
            "${config.name}: master must store as a single VEVENT after future-exception cleanup",
            1,
            veventsAfter
        )
        val masterRrule = rrulePropFrom(firstVeventBody(storedAfter))
        assertNotNull("Truncated master must have RRULE on ${config.name}", masterRrule)
        assertTrue(
            "Truncated master RRULE must contain UNTIL on ${config.name}: $masterRrule",
            masterRrule!!.contains("UNTIL=")
        )
    }
}
