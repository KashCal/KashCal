package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.changes.RecentChangeEntry
import org.onekash.kashcal.domain.changes.RecentChangesRecorder
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Checks on every server what the Recent changes log records for each kind of edit another
 * client makes to a recurring event: one row per event or changed occurrence, worded as the user
 * sees the change. Runs the real PullStrategy and the real RecentChangesRecorder into in-memory
 * Room.
 *
 * Each case creates a weekly series, pulls it (the calendar's first pull, which records
 * nothing), edits the server's copy the way another client does (GET, change, PUT with
 * If-Match), and pulls again. The edits follow what a real iPhone wrote to iCloud: a move as a
 * new override with RECURRENCE-ID in the user's zone against a UTC series DTSTART, a delete as
 * a UTC EXDATE, a series rename copied into the override, and a default VALARM plus TRANSP added
 * to the series on first touch.
 *
 * Writes only to a calendar the sharing check (UnsharedCalendarPicker) accepts, so test events
 * don't reach other people. Creates events with this run's UIDs only, updates and deletes only the
 * URL its create call returned and URLs that carry this run's full UID (the create URL it records
 * before the PUT, or a redirect of an update), and builds no push path. Assertions, printed results
 * and failure messages read only this run's rows. Skips a server without credentials, unreachable,
 * or with no accepted calendar (Zoho reports no sharing properties at all, so it is skipped). A
 * failed pull fails the test; server-side setup failures skip it.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration --tests '*MultiServerRecentChangesTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerRecentChangesTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val runId = System.currentTimeMillis()
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val WEEK_MS = 7 * DAY_MS
        // Three weeks out at 15:00 UTC, so no strict server refuses it as past.
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 21) * DAY_MS + 15 * HOUR_MS
        private val SLOT_MS = START_MS + WEEK_MS
        private val CHICAGO: ZoneId = ZoneId.of("America/Chicago")
        private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private val chicagoFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(CHICAGO)
        private val pickedCalendar = mutableMapOf<String, String?>()
    }

    private lateinit var database: KashCalDatabase
    private lateinit var pullStrategy: PullStrategy
    private lateinit var recorder: RecentChangesRecorder
    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val createdUrls = mutableListOf<String>()

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries().build()
        val dataStore = mockk<KashCalDataStore>(relaxed = true)
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)
        // The relaxed default isn't a working Flow; Int.MAX_VALUE means "All".
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        CalDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
        // The pull reads the account only to match invites; none here.
        val accountRepository = mockk<AccountRepository>()
        coEvery { accountRepository.getAccountById(any()) } returns null
        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = OccurrenceGenerator(
                database, database.occurrencesDao(), database.eventsDao(),
                TestDataStoreFactory.createDefault()
            ),
            defaultQuirks = config.quirksFactory(creds?.serverUrl ?: config.defaultServerUrl ?: ""),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = accountRepository,
            reminderScheduler = mockk(relaxed = true)
        )
        recorder = RecentChangesRecorder(database)
    }

    @After
    fun cleanup() = runBlocking {
        client?.let { c ->
            for (url in createdUrls) {
                try { c.deleteEvent(url, null) } catch (_: Exception) { /* best-effort */ }
            }
        }
        if (::database.isInitialized) database.close()
        unmockkAll()
    }

    // ---- server access ----

    private suspend fun discoverCalendar(): String? =
        pickedCalendar.getOrPut(config.name) { UnsharedCalendarPicker(config, client!!, creds!!).find() }

    private fun localCalendarFor(calendarUrl: String): Calendar = runBlocking {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = "recent-changes@example.test")
        )
        val id = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = calendarUrl, displayName = "Probe", color = 0)
        )
        database.calendarsDao().getById(id)!!
    }

    // ---- ICS ----

    private fun utc(ms: Long) = utcFormat.format(Instant.ofEpochMilli(ms))
    private fun local(ms: Long) = chicagoFormat.format(Instant.ofEpochMilli(ms))
    private fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")
    private fun crlf(lines: List<String>) = lines.joinToString("\r\n") + "\r\n"

    private fun vevent(uid: String, vararg lines: String) =
        "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:${utc(System.currentTimeMillis())}\r\n" +
            lines.joinToString("") { "$it\r\n" } + "END:VEVENT\r\n"

    private fun vcal(vararg vevents: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//KashCal//Recent Changes//EN\r\n" +
            vevents.joinToString("") + "END:VCALENDAR\r\n"

    private fun series(uid: String, title: String, vararg extra: String) = vevent(uid,
        "DTSTART:${utc(START_MS)}", "DTEND:${utc(START_MS + HOUR_MS)}",
        "RRULE:FREQ=WEEKLY;COUNT=6", "SEQUENCE:0", "SUMMARY:$title", *extra)

    private fun override(uid: String, title: String, newStart: Long) = vevent(uid,
        "RECURRENCE-ID;TZID=America/Chicago:${local(SLOT_MS)}",
        "DTSTART;TZID=America/Chicago:${local(newStart)}",
        "DTEND;TZID=America/Chicago:${local(newStart + HOUR_MS)}",
        "SEQUENCE:0", "SUMMARY:$title")

    private fun lines(body: String) = unfold(body).split(Regex("\r?\n")).filter { it.isNotEmpty() }

    /** Returns the VEVENT blocks of [lines] as index ranges, END line included. */
    private fun vevents(lines: List<String>): List<IntRange> = buildList {
        var i = 0
        while (i < lines.size) {
            if (lines[i] == "BEGIN:VEVENT") {
                val end = (i until lines.size).first { lines[it] == "END:VEVENT" }
                add(i..end)
                i = end
            }
            i++
        }
    }

    private fun isOverride(lines: List<String>, block: IntRange) =
        block.any { lines[it].startsWith("RECURRENCE-ID") }

    /** Adds [vevent] to the server body, before END:VCALENDAR. */
    private fun withVevent(body: String, vevent: String): String {
        val lines = lines(body).toMutableList()
        val end = lines.indexOfLast { it == "END:VCALENDAR" }
        lines.addAll(end, vevent.trimEnd().split("\r\n"))
        return crlf(lines)
    }

    /** Drops every override VEVENT from the server body. */
    private fun withoutOverrides(body: String): String {
        val lines = lines(body)
        val drop = vevents(lines).filter { isOverride(lines, it) }.flatMap { it.toList() }.toSet()
        return crlf(lines.filterIndexed { i, _ -> i !in drop })
    }

    /** Rewrites the series VEVENT's lines with [edit], then inserts [add] before its END. */
    private fun withSeries(body: String, add: List<String> = emptyList(), edit: (String) -> String? = { it }): String {
        val lines = lines(body)
        val series = vevents(lines).first { !isOverride(lines, it) }
        val out = mutableListOf<String>()
        lines.forEachIndexed { i, line ->
            if (i == series.last) out.addAll(add)
            if (i in series && i != series.first && i != series.last) edit(line)?.let { out.add(it) } else out.add(line)
        }
        return crlf(out)
    }

    /** Moves the series by [deltaMs], rewriting its existing EXDATEs the way a client must. */
    private fun withSeriesMoved(body: String, deltaMs: Long): String = withSeries(body) { line ->
        when {
            line.startsWith("DTSTART") -> "DTSTART:${utc(START_MS + deltaMs)}"
            line.startsWith("DTEND") -> "DTEND:${utc(START_MS + HOUR_MS + deltaMs)}"
            line.startsWith("EXDATE") -> "EXDATE:${utc(SLOT_MS + deltaMs)}"
            else -> line
        }
    }

    /** Moves the override VEVENT to [newStart], leaving the series as the server wrote it. */
    private fun withOverrideMovedTo(body: String, newStart: Long): String {
        val lines = lines(body)
        val override = vevents(lines).first { isOverride(lines, it) }
        return crlf(lines.mapIndexed { i, l ->
            when {
                i !in override -> l
                l.startsWith("DTSTART") -> "DTSTART;TZID=America/Chicago:${local(newStart)}"
                l.startsWith("DTEND") -> "DTEND;TZID=America/Chicago:${local(newStart + HOUR_MS)}"
                else -> l
            }
        })
    }

    /** Sets every SUMMARY in the body to [title]. */
    private fun withSummary(body: String, title: String): String =
        crlf(lines(body).map { if (it.startsWith("SUMMARY")) "SUMMARY:$title" else it })

    // ---- scenario ----

    private inner class Scenario(case: String) {
        val token = "rc$runId-$case"
        val uid = "recent-changes-$runId-${config.name.lowercase()}-$case-${UUID.randomUUID()}"
        lateinit var calendar: Calendar
        lateinit var calendarUrl: String
        lateinit var url: String

        fun create(ics: String) = runBlocking {
            // Recorded before the PUT: a create that times out after the server stored it is
            // still cleaned up. The URL carries this run's full UID.
            createdUrls.add("${calendarUrl.trimEnd('/')}/$uid.ics")
            val r = client!!.createEvent(calendarUrl, uid, ics)
            assumeTrue("${config.name}: create failed (${(r as? CalDavResult.Error)?.code})", r is CalDavResult.Success)
            url = (r as CalDavResult.Success).data.first
            if (url !in createdUrls) createdUrls.add(url)
        }

        /** GETs the server's copy, applies [edit], and PUTs it back with If-Match. */
        fun editOnServer(edit: (String) -> String) = runBlocking {
            val fetched = client!!.fetchEvent(url)
            assumeTrue("${config.name}: GET failed", fetched is CalDavResult.Success)
            val body = (fetched as CalDavResult.Success).data.icalData
            val etag = client!!.fetchEtag(url).getOrNull() ?: fetched.data.etag
            assumeTrue("${config.name}: no etag for If-Match", !etag.isNullOrEmpty())
            val r = client!!.updateEvent(url, edit(body), etag!!)
            assumeTrue("${config.name}: update failed (${(r as? CalDavResult.Error)?.code})", r is CalDavResult.Success)
            // A redirected PUT moves the event; follow it if the new URL is still this run's.
            (r as CalDavResult.Success).finalUrl?.takeIf { it != url && it.contains(uid) }?.let {
                url = it
                if (it !in createdUrls) createdUrls.add(it)
            }
        }

        /**
         * Deletes this scenario's own event at its current URL: the create URL, or a redirect of
         * an update that carries this run's UID.
         */
        fun deleteOnServer() = runBlocking {
            val r = client!!.deleteEvent(url, null)
            assumeTrue("${config.name}: delete failed (${(r as? CalDavResult.Error)?.code})", r is CalDavResult.Success)
        }

        /** Pulls, records, and returns only this scenario's recorded entries. */
        fun pullAndRecord(): List<RecentChangeEntry> = runBlocking {
            // SOGo's ctag doesn't move within the second of a write.
            Thread.sleep(1500)
            val cal = database.calendarsDao().getById(calendar.id)!!
            val result = pullStrategy.pull(cal, client = client!!)
            assertTrue("${config.name}: pull failed (${FixtureRedactor.redact(result.toString())})", result is PullResult.Success)
            recorder.record((result as PullResult.Success).changes).filter { it.eventUid == uid }
        }

        /** This scenario's stored rows, after merging across pulls. */
        fun rows() = runBlocking {
            database.recentChangesDao().observeVisible(0).first().map { it.change }.filter { it.eventUid == uid }
        }

        fun describe(entries: List<RecentChangeEntry>) = entries.joinToString(prefix = "[", postfix = "]") {
            val at = if (it.instanceTs == 0L) "series" else "occurrence"
            "${it.type}:$at:${it.changedFields.joinToString("+")}"
        }
    }

    private fun scenario(case: String): Scenario {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
        val s = Scenario(case)
        val calendarUrl = runBlocking { discoverCalendar() }
        assumeTrue("${config.name}: no calendar confirmed unshared and writable", calendarUrl != null)
        s.calendarUrl = calendarUrl!!
        s.calendar = localCalendarFor(calendarUrl)
        return s
    }

    private fun record(case: String, line: String) =
        println(FixtureRedactor.redact("RECENT-CHANGES|${config.name}|$case|$line"))

    // ---- cases ----

    @Test
    fun `the calendar's first pull records nothing`() {
        val s = scenario("first")
        s.create(vcal(series(s.uid, "${s.token} Weekly")))

        val entries = s.pullAndRecord()
        record("first", s.describe(entries))

        assertEquals("${config.name}: a first pull must record nothing, got ${s.describe(entries)}", 0, entries.size)
    }

    @Test
    fun `moving one occurrence records one change at its slot`() {
        val s = scenario("move")
        val title = "${s.token} Weekly"
        s.create(vcal(series(s.uid, title)))
        s.pullAndRecord()

        s.editOnServer { withVevent(it, override(s.uid, title, SLOT_MS + HOUR_MS)) }
        val entries = s.pullAndRecord()
        record("move", s.describe(entries))

        val row = entries.singleOrNull()
        assertTrue("${config.name}: expected one occurrence row, got ${s.describe(entries)}",
            row != null && row.instanceTs == SLOT_MS)
        assertEquals(ChangeType.MODIFIED, row!!.type)
        assertEquals("moved from its slot", SLOT_MS, row.previousStartTs)
        assertTrue(ChangedField.TIME in row.changedFields)
    }

    @Test
    fun `cancelling one occurrence records it as cancelled and not the series`() {
        val s = scenario("exdate")
        s.create(vcal(series(s.uid, "${s.token} Weekly")))
        s.pullAndRecord()

        s.editOnServer { withSeries(it, add = listOf("EXDATE:${utc(SLOT_MS)}")) }
        val entries = s.pullAndRecord()
        record("exdate", s.describe(entries))

        val row = entries.singleOrNull()
        assertTrue("${config.name}: expected one cancelled row, got ${s.describe(entries)}",
            row != null && row.type == ChangeType.DELETED && row.instanceTs == SLOT_MS)
        assertEquals(SLOT_MS, row!!.startTs)
    }

    @Test
    fun `cancelling a changed occurrence records one cancelled row with its moved time`() {
        val s = scenario("cancelmoved")
        val title = "${s.token} Weekly"
        s.create(vcal(series(s.uid, title), override(s.uid, title, SLOT_MS + HOUR_MS)))
        s.pullAndRecord()

        s.editOnServer { withSeries(withoutOverrides(it), add = listOf("EXDATE:${utc(SLOT_MS)}")) }
        val entries = s.pullAndRecord()
        record("cancelmoved", s.describe(entries))

        val row = entries.singleOrNull()
        assertTrue("${config.name}: expected one cancelled row, got ${s.describe(entries)}",
            row != null && row.type == ChangeType.DELETED && row.instanceTs == SLOT_MS)
        assertEquals("the time the user last saw", SLOT_MS + HOUR_MS, row!!.startTs)
    }

    @Test
    fun `a series rename copied into its override records only the series`() {
        val s = scenario("rename")
        val title = "${s.token} Weekly"
        s.create(vcal(series(s.uid, title), override(s.uid, title, SLOT_MS + HOUR_MS)))
        s.pullAndRecord()

        s.editOnServer { withSummary(it, "${s.token} Renamed") }
        val entries = s.pullAndRecord()
        record("rename", s.describe(entries))

        val row = entries.singleOrNull()
        assertTrue("${config.name}: expected only the series row, got ${s.describe(entries)}",
            row != null && row.instanceTs == 0L)
        assertEquals(setOf(ChangedField.TITLE), row!!.changedFields)
    }

    @Test
    fun `a series rename copied into an earlier-moved occurrence renames that occurrence's row`() {
        val s = scenario("renamemoved")
        val title = "${s.token} Weekly"
        s.create(vcal(series(s.uid, title)))
        s.pullAndRecord()
        s.editOnServer { withVevent(it, override(s.uid, title, SLOT_MS + HOUR_MS)) }
        s.pullAndRecord()

        // Renames every VEVENT, the way iOS copies a series rename into its overrides.
        s.editOnServer { withSummary(it, "${s.token} Renamed") }
        val entries = s.pullAndRecord()
        record("renamemoved", s.describe(entries))

        val rows = s.rows()
        val occurrence = rows.singleOrNull { it.instanceTs == SLOT_MS }
        assertTrue("${config.name}: expected the moved occurrence's row, got ${s.describe(entries)}", occurrence != null)
        assertEquals("${s.token} Renamed", occurrence!!.title)
        assertEquals("it still reads as moved", SLOT_MS, occurrence.previousStartTs)
        assertEquals(setOf(ChangedField.TITLE), rows.single { it.instanceTs == 0L }.changedFields)
    }

    @Test
    fun `deleting a series after one occurrence moved leaves only the series row`() {
        val s = scenario("deletemoved")
        val title = "${s.token} Weekly"
        s.create(vcal(series(s.uid, title)))
        s.pullAndRecord()
        s.editOnServer { withVevent(it, override(s.uid, title, SLOT_MS + HOUR_MS)) }
        s.pullAndRecord()

        s.deleteOnServer()
        val entries = s.pullAndRecord()
        record("deletemoved", s.describe(entries))

        val rows = s.rows()
        assertEquals("${config.name}: expected only the deleted series row, got ${rows.map { it.instanceTs to it.changeType }}",
            listOf(0L to ChangeType.DELETED), rows.map { it.instanceTs to it.changeType })
    }

    @Test
    fun `a default alarm and busy-free added to the series record nothing`() {
        val s = scenario("alarm")
        s.create(vcal(series(s.uid, "${s.token} Weekly")))
        s.pullAndRecord()

        s.editOnServer {
            withSeries(it, add = listOf(
                "TRANSP:TRANSPARENT",
                "BEGIN:VALARM", "X-APPLE-DEFAULT-ALARM:TRUE", "ACTION:DISPLAY", "TRIGGER:-PT15M",
                "DESCRIPTION:Reminder", "END:VALARM"
            )) { line -> line.takeUnless { l -> l.startsWith("TRANSP") } }
        }
        val entries = s.pullAndRecord()
        record("alarm", s.describe(entries))

        assertEquals("${config.name}: an alarm or busy/free edit must record nothing, got ${s.describe(entries)}",
            0, entries.size)
    }

    @Test
    fun `a new series with a changed occurrence records one new row`() {
        val s = scenario("newseries")
        // An unrelated event of this run makes the calendar's first pull happen before the series.
        val title = "${s.token} Weekly"
        s.create(vcal(series(s.uid, title)))
        s.pullAndRecord()
        val later = Scenario("newseries2").apply { calendar = s.calendar; calendarUrl = s.calendarUrl }
        later.create(vcal(series(later.uid, title), override(later.uid, title, SLOT_MS + HOUR_MS)))

        val entries = later.pullAndRecord()
        record("newseries", later.describe(entries))

        val row = entries.singleOrNull()
        assertTrue("${config.name}: expected one new series row, got ${later.describe(entries)}",
            row != null && row.type == ChangeType.NEW && row.instanceTs == 0L)
    }

    @Test
    fun `a series moved an hour with its EXDATE rewritten records only the move`() {
        val s = scenario("movehour")
        s.create(vcal(series(s.uid, "${s.token} Weekly", "EXDATE:${utc(SLOT_MS)}")))
        s.pullAndRecord()

        s.editOnServer { withSeriesMoved(it, HOUR_MS) }
        val entries = s.pullAndRecord()
        record("movehour", s.describe(entries))

        val row = entries.singleOrNull()
        assertTrue("${config.name}: expected one series row, got ${s.describe(entries)}",
            row != null && row.instanceTs == 0L && row.type == ChangeType.MODIFIED)
        assertTrue(ChangedField.TIME in row!!.changedFields)
        assertEquals(START_MS, row.previousStartTs)
    }

    @Test
    fun `a series moved to the next weekday with its EXDATE rewritten records only the move`() {
        val s = scenario("moveday")
        s.create(vcal(series(s.uid, "${s.token} Weekly", "EXDATE:${utc(SLOT_MS)}")))
        s.pullAndRecord()

        s.editOnServer { withSeriesMoved(it, DAY_MS) }
        val entries = s.pullAndRecord()
        record("moveday", s.describe(entries))

        val row = entries.singleOrNull()
        assertTrue("${config.name}: expected one series row, got ${s.describe(entries)}",
            row != null && row.instanceTs == 0L && row.type == ChangeType.MODIFIED)
        assertTrue(ChangedField.TIME in row!!.changedFields)
    }

    @Test
    fun `a later move merges into the same stored row`() {
        val s = scenario("merge")
        val title = "${s.token} Weekly"
        s.create(vcal(series(s.uid, title)))
        s.pullAndRecord()

        s.editOnServer { withVevent(it, override(s.uid, title, SLOT_MS + HOUR_MS)) }
        s.pullAndRecord()
        s.editOnServer { withOverrideMovedTo(it, SLOT_MS + 2 * HOUR_MS) }
        s.pullAndRecord()

        val rows = s.rows()
        assertEquals("${config.name}: one stored row for the occurrence, got $rows", 1, rows.size)
        assertEquals("the slot the user knew", SLOT_MS, rows.single().previousStartTs)
        assertEquals(SLOT_MS + 2 * HOUR_MS, rows.single().startTs)
    }
}
