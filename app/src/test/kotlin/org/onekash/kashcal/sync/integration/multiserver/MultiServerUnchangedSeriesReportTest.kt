package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.model.SyncChange
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
 * Checks on every server that a pull reports a series or a changed occurrence only when its own
 * content changed. CalDAV gives one etag per resource, so editing one occurrence changes the etag
 * the pull sees for the whole series; the unchanged parts must not show as "updated".
 *
 * Each case creates a weekly series, pulls it with the real PullStrategy into in-memory Room,
 * edits the server's copy the way another client does (GET, change, PUT with If-Match, so content
 * the server added, such as Zoho's alarm, is kept), and pulls again. The move is written the way
 * iOS writes it: RECURRENCE-ID in the user's zone against a series DTSTART in UTC.
 *
 * Writes only to a calendar the sharing check (UnsharedCalendarPicker) accepts, so test events
 * don't reach other people. Creates events with this run's UIDs only, updates and deletes only the
 * URL its create call returned and URLs that carry this run's full UID (the create URL it records
 * before the PUT, or a redirect of an update), and builds no push path. Printed results and failure
 * messages list only this run's events. Skips a server without credentials, unreachable, or with no
 * accepted calendar (Zoho reports no sharing properties at all, so it is skipped). A failed pull
 * fails the test; server-side setup failures skip it.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration --tests '*MultiServerUnchangedSeriesReportTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerUnchangedSeriesReportTest(
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
        private val CHICAGO: ZoneId = ZoneId.of("America/Chicago")
        private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private val chicagoFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(CHICAGO)
        private val pickedCalendar = mutableMapOf<String, String?>()
    }

    private lateinit var database: KashCalDatabase
    private lateinit var pullStrategy: PullStrategy
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
            Account(provider = AccountProvider.CALDAV, email = "unchanged-series@example.test")
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
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//KashCal//Unchanged Series//EN\r\n" +
            vevents.joinToString("") + "END:VCALENDAR\r\n"

    private fun series(uid: String, title: String) = vevent(uid,
        "DTSTART:${utc(START_MS)}", "DTEND:${utc(START_MS + HOUR_MS)}",
        "RRULE:FREQ=WEEKLY;COUNT=6", "SEQUENCE:0", "SUMMARY:$title")

    private fun override(uid: String, title: String, slot: Long, newStart: Long) = vevent(uid,
        "RECURRENCE-ID;TZID=America/Chicago:${local(slot)}",
        "DTSTART;TZID=America/Chicago:${local(newStart)}",
        "DTEND;TZID=America/Chicago:${local(newStart + HOUR_MS)}",
        "SEQUENCE:0", "SUMMARY:$title")

    private fun lines(body: String) = unfold(body).split(Regex("\r?\n")).filter { it.isNotEmpty() }

    /** Adds [vevent] to the server body, before END:VCALENDAR. */
    private fun withVevent(body: String, vevent: String): String {
        val lines = lines(body).toMutableList()
        val end = lines.indexOfLast { it == "END:VCALENDAR" }
        lines.addAll(end, vevent.trimEnd().split("\r\n"))
        return crlf(lines)
    }

    /** Adds [exdateLine] to the series VEVENT (the one without RECURRENCE-ID), after its RRULE. */
    private fun withSeriesLine(body: String, exdateLine: String): String {
        val lines = lines(body).toMutableList()
        var i = 0
        while (i < lines.size) {
            if (lines[i] == "BEGIN:VEVENT") {
                val end = (i until lines.size).first { lines[it] == "END:VEVENT" }
                val block = lines.subList(i, end)
                if (block.none { it.startsWith("RECURRENCE-ID") }) {
                    val rrule = i + block.indexOfFirst { it.startsWith("RRULE") }
                    lines.add(rrule + 1, exdateLine)
                    return crlf(lines)
                }
                i = end
            }
            i++
        }
        error("no series VEVENT in server body")
    }

    /** Sets every SUMMARY in the body to [title]; the rename case has one VEVENT. */
    private fun withSummary(body: String, title: String): String =
        crlf(lines(body).map { if (it.startsWith("SUMMARY")) "SUMMARY:$title" else it })

    // ---- scenario ----

    private inner class Scenario(case: String) {
        val token = "us$runId-$case"
        val uid = "unchanged-series-$runId-${config.name.lowercase()}-$case-${UUID.randomUUID()}"
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

        /** Pulls and returns only this scenario's changes. */
        fun pull(): List<SyncChange> = runBlocking {
            // SOGo's ctag doesn't move within the second of a write.
            Thread.sleep(1500)
            val cal = database.calendarsDao().getById(calendar.id)!!
            val result = pullStrategy.pull(cal, client = client!!)
            assertTrue("${config.name}: pull failed (${FixtureRedactor.redact(result.toString())})", result is PullResult.Success)
            (result as PullResult.Success).changes.filter { it.eventTitle.contains(token) }
        }

        fun seriesId(): Long = runBlocking {
            database.eventsDao().getByUid(uid).single { it.originalEventId == null }.id
        }

        fun occurrenceId(): Long = runBlocking {
            database.eventsDao().getByUid(uid).single { it.originalEventId != null }.id
        }

        fun describe(changes: List<SyncChange>): String {
            val s = seriesId()
            return changes.joinToString(prefix = "[", postfix = "]") {
                "${it.type}:${if (it.eventId == s) "series" else if (it.eventId == null) "gone" else "occurrence"}"
            }
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
        println(FixtureRedactor.redact("UNCHANGED-SERIES|${config.name}|$case|$line"))

    // ---- cases ----

    @Test
    fun `moving one occurrence reports the occurrence, not the series`() {
        val s = scenario("move")
        val title = "${s.token} Weekly"
        s.create(vcal(series(s.uid, title)))
        s.pull()

        val slot = START_MS + WEEK_MS
        s.editOnServer { withVevent(it, override(s.uid, title, slot, slot + HOUR_MS)) }
        val changes = s.pull()
        record("move", s.describe(changes))

        assertTrue("${config.name}: the unchanged series must not be reported, got ${s.describe(changes)}",
            changes.none { it.eventId == s.seriesId() })
        assertTrue("${config.name}: the moved occurrence must be reported, got ${s.describe(changes)}",
            changes.any { it.eventId == s.occurrenceId() })
    }

    @Test
    fun `cancelling an occurrence reports the series, not an untouched changed occurrence`() {
        val s = scenario("exdate")
        val title = "${s.token} Weekly"
        val slot = START_MS + WEEK_MS
        s.create(vcal(series(s.uid, title), override(s.uid, title, slot, slot + HOUR_MS)))
        s.pull()

        s.editOnServer { withSeriesLine(it, "EXDATE:${utc(START_MS + 2 * WEEK_MS)}") }
        val changes = s.pull()
        record("exdate", s.describe(changes))

        assertTrue("${config.name}: the series gained an EXDATE and must be reported, got ${s.describe(changes)}",
            changes.any { it.eventId == s.seriesId() })
        assertTrue("${config.name}: the untouched occurrence must not be reported, got ${s.describe(changes)}",
            changes.none { it.eventId == s.occurrenceId() })
    }

    @Test
    fun `renaming the series is reported`() {
        val s = scenario("rename")
        s.create(vcal(series(s.uid, "${s.token} Weekly")))
        s.pull()

        s.editOnServer { withSummary(it, "${s.token} Renamed") }
        val changes = s.pull()
        record("rename", s.describe(changes))

        assertTrue("${config.name}: a renamed series must be reported, got ${s.describe(changes)}",
            changes.any { it.eventId == s.seriesId() })
    }
}
