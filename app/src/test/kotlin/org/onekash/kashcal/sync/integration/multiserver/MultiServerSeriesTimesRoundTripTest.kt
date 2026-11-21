package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import org.onekash.kashcal.data.db.EventIcsStore
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.DigestAuthenticator
import org.onekash.kashcal.sync.contacts.FakeContactsProviderRepository
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.UUID

/**
 * Series times through the real pull, edit and push on every server, with the phone in
 * America/New_York (US clocks change on 14 Mar 2027):
 *
 * - A weekly series whose DTSTART is written in UTC (`DTSTART:...Z`) keeps 15:00Z on every
 *   occurrence after the clock change, the instant RFC 5545 §3.3.5 gives a UTC time, and still
 *   uploads its DTSTART in UTC form after an edit.
 * - An EXDATE or RDATE at another time of day than the series matches by instant
 *   (RFC 5545 §3.8.5.1-2): a 09:00 EXDATE on a 10:00 daily series hides nothing, and a 15:00
 *   RDATE on a day that has a 10:00 occurrence adds a second one.
 * - A series carrying the values an earlier build uploaded after deleting and changing one
 *   occurrence while it expanded the series in the phone zone (EXDATE and RECURRENCE-ID at
 *   14:00Z) shows what the servers that expand it show: the 15:00Z occurrences are still there.
 *
 * Each server's own answer is printed: a calendar-query time-range at exact instants and a
 * CALDAV:expand of the resource (RFC 4791 §9.6.5). Servers disagree on the off-time values and
 * several don't expand (iCloud, Stalwart, SOGo and Zoho return the stored object, Baikal
 * answers 500), so only the UTC-start hit, where every server that answers agrees, is
 * asserted. Each run writes its own resources under a fresh UID and deletes them afterwards.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration --tests '*MultiServerSeriesTimesRoundTripTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerSeriesTimesRoundTripTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private val NY = ZoneId.of("America/New_York")
        private const val FUTURE_END_MS = 4_102_444_800_000L // Jan 1, 2100 UTC

        private const val NY_VTIMEZONE = "BEGIN:VTIMEZONE\r\nTZID:America/New_York\r\n" +
            "BEGIN:DAYLIGHT\r\nTZOFFSETFROM:-0500\r\nTZOFFSETTO:-0400\r\nTZNAME:EDT\r\n" +
            "DTSTART:19700308T020000\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU\r\nEND:DAYLIGHT\r\n" +
            "BEGIN:STANDARD\r\nTZOFFSETFROM:-0400\r\nTZOFFSETTO:-0500\r\nTZNAME:EST\r\n" +
            "DTSTART:19701101T020000\r\nRRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU\r\nEND:STANDARD\r\n" +
            "END:VTIMEZONE"

        private fun utc(s: String): Long = Instant.from(UTC.parse(s)).toEpochMilli()
        private fun ny(y: Int, m: Int, d: Int, h: Int): Long =
            java.time.ZonedDateTime.of(y, m, d, h, 0, 0, 0, NY).toInstant().toEpochMilli()
        private fun fmt(ms: Long): String = UTC.format(Instant.ofEpochMilli(ms))
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private var calendarUrl: String? = null
    private val created = mutableListOf<String>()
    private val databases = mutableListOf<KashCalDatabase>()
    private val savedZone: TimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        CalDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
        TimeZone.setDefault(TimeZone.getTimeZone(NY))
    }

    @After
    fun tearDown() = runBlocking {
        val c = client
        if (c != null) {
            for (url in created) {
                runCatching { c.deleteEvent(url, c.fetchEtag(url).getOrNull()) }
                runCatching { c.deleteEvent(url, null) }
            }
            val home = calendarUrl
            if (home != null && created.isNotEmpty()) {
                val keys = created.map { it.substringAfterLast('/').substringBefore(".ics") }
                val listed = runCatching { c.fetchEtagsInRange(home, 0L, FUTURE_END_MS).getOrNull().orEmpty() }.getOrDefault(emptyList())
                for ((href, _) in listed.filter { (h, _) -> keys.any { h.contains(it) } }) {
                    runCatching { c.deleteEvent(resolve(home, href), null) }
                }
            }
        }
        databases.forEach { it.close() }
        TimeZone.setDefault(savedZone)
        unmockkAll()
    }

    @Test
    fun `a series written in UTC keeps its UTC time after the clock change through pull, edit and push`() = runBlocking<Unit> {
        ready()
        val uid = "series-times-utc-${UUID.randomUUID()}"
        val url = put(uid, listOf(vevent(uid, "DTSTART:20270308T150000Z", "DTEND:20270308T160000Z",
            "RRULE:FREQ=WEEKLY;COUNT=4", "SUMMARY:Series times UTC")))
        val expected = listOf("20270308T150000Z", "20270315T150000Z", "20270322T150000Z", "20270329T150000Z")

        val (db, master) = pullSeries(uid) ?: return@runBlocking
        report("utc", "stored zone=${master.timezone} occurrences=${shown(db, master).map(::fmt)}")
        report("utc", "server time-range 22 Mar 15:00Z=${timeRange("20270322T145500Z", "20270322T150500Z", url)} " +
            "14:00Z=${timeRange("20270322T135500Z", "20270322T140500Z", url)}")
        report("utc", "server expand=${expand(url)}")
        assertEquals("${config.name}: stored zone", "UTC", master.timezone)
        assertEquals("${config.name}: occurrences", expected, shown(db, master).map(::fmt))
        val hit = timeRange("20270322T145500Z", "20270322T150500Z", url)
        if (hit != null) assertTrue("${config.name}: the server's own time-range has 22 Mar at 15:00Z", hit)

        coordinator(db).updateEvent(master.copy(title = "Series times UTC renamed", updatedAt = master.updatedAt + 1))
        assertPushed(db)
        val after = unfold(client!!.fetchEvent(db.eventsDao().getById(master.id)?.caldavUrl ?: url).getOrNull()?.icalData.orEmpty())
        val starts = after.lines().filter { it.startsWith("DTSTART") }
        report("utc", "served after edit=${starts + after.lines().filter { it.startsWith("SUMMARY") }}")
        assertTrue("${config.name}: DTSTART stays in UTC form after the edit: $starts", "DTSTART:20270308T150000Z" in starts)
        assertFalse("${config.name}: no TZID on DTSTART: $starts", starts.any { it.startsWith("DTSTART;TZID") })

        val (db2, again) = pullSeries(uid) ?: return@runBlocking
        assertEquals("${config.name}: zone after re-pull", "UTC", again.timezone)
        assertEquals("${config.name}: occurrences after re-pull", expected, shown(db2, again).map(::fmt))
    }

    @Test
    fun `an EXDATE and an RDATE at another time of day match by instant through pull, edit and push`() = runBlocking<Unit> {
        ready()
        val uid = "series-times-offtime-${UUID.randomUUID()}"
        val url = put(uid, listOf(vevent(uid,
            "DTSTART;TZID=America/New_York:20270301T100000", "DTEND;TZID=America/New_York:20270301T110000",
            "RRULE:FREQ=DAILY;COUNT=6",
            "EXDATE;TZID=America/New_York:20270303T090000",
            "RDATE;TZID=America/New_York:20270304T150000",
            "SUMMARY:Series times off-time")), withNyZone = true)
        // The RFC 5545 set of the copy the server holds: Zoho keeps neither EXDATE nor RDATE.
        val held = seriesOf(client!!.fetchEvent(url).getOrNull()?.icalData.orEmpty())
        val heldExdates = held?.exdates?.map { it.timestamp }.orEmpty()
        val heldRdates = held?.rdates?.map { it.timestamp }.orEmpty()
        if (heldExdates.isEmpty() || heldRdates.isEmpty()) {
            report("offtime", "SERVER-DROPPED values: EXDATE=${heldExdates.map(::fmt)} RDATE=${heldRdates.map(::fmt)}")
        }
        val expected = ((1..6).map { ny(2027, 3, it, 10) } - heldExdates.toSet() + heldRdates).sorted().map(::fmt)

        val (db, master) = pullSeries(uid) ?: return@runBlocking
        report("offtime", "occurrences=${shown(db, master).map(::fmt)}")
        report("offtime", "server time-range 3 Mar 10:00 NY=${timeRange("20270303T145500Z", "20270303T150500Z", url)} " +
            "4 Mar 15:00 NY=${timeRange("20270304T195500Z", "20270304T200500Z", url)}")
        report("offtime", "server expand=${expand(url)}")
        assertEquals("${config.name}: occurrences (RFC 5545 §3.8.5.1-2)", expected, shown(db, master).map(::fmt))

        coordinator(db).updateEvent(master.copy(title = "Series times off-time renamed", updatedAt = master.updatedAt + 1))
        assertPushed(db)
        val body = client!!.fetchEvent(db.eventsDao().getById(master.id)?.caldavUrl ?: url).getOrNull()?.icalData.orEmpty()
        val series = seriesOf(body)
        report("offtime", "served after edit=${unfold(body).lines().filter { l -> listOf("DTSTART", "EXDATE", "RDATE").any { l.startsWith(it) } }}")
        assertEquals("${config.name}: EXDATE instants kept", heldExdates, series?.exdates?.map { it.timestamp }.orEmpty())
        assertEquals("${config.name}: RDATE instants kept", heldRdates, series?.rdates?.map { it.timestamp }.orEmpty())

        val (db2, again) = pullSeries(uid) ?: return@runBlocking
        assertEquals("${config.name}: occurrences after re-pull", expected, shown(db2, again).map(::fmt))
    }

    @Test
    fun `values an earlier build wrote at the phone-zone time show what servers that expand the series show`() = runBlocking<Unit> {
        ready()
        val uid = "series-times-shifted-${UUID.randomUUID()}"
        // What an earlier build uploaded after deleting 15 Mar and renaming 22 Mar in New York,
        // where it read the series' 15:00Z start as 11:00 local and expanded 14:00Z after 14 Mar.
        val url = put(uid, listOf(
            vevent(uid, "DTSTART:20270308T150000Z", "DTEND:20270308T160000Z", "RRULE:FREQ=WEEKLY;COUNT=4",
                "EXDATE:20270315T140000Z", "SUMMARY:Series times shifted"),
            vevent(uid, "RECURRENCE-ID:20270322T140000Z", "DTSTART:20270322T140000Z", "DTEND:20270322T150000Z",
                "SUMMARY:Series times shifted changed"),
        ))

        val (db, master) = pullSeries(uid) ?: return@runBlocking
        val rows = db.occurrencesDao().getForEvent(master.id).filter { !it.isCancelled }.sortedBy { it.startTs }
        val shownWithKind = rows.map { fmt(it.startTs) + if (it.exceptionEventId != null) "(changed)" else "" }
        report("shifted", "app shows=$shownWithKind")
        report("shifted", "server expand=${expand(url)}")
        // RFC 5545: the 14:00Z EXDATE excludes no occurrence of the 15:00Z series, and the
        // 14:00Z RECURRENCE-ID names no occurrence of it, so the changed copy shows beside the
        // 15:00Z original, as Nextcloud, Cyrus and Xandikos expand it.
        assertEquals(
            "${config.name}: app view of the earlier build's values",
            listOf("20270308T150000Z", "20270315T150000Z", "20270322T140000Z(changed)", "20270322T150000Z", "20270329T150000Z"),
            shownWithKind,
        )
    }

    @Test
    fun `an occurrence deleted in the app stays deleted after the series moves to another time`() = runBlocking<Unit> {
        ready()
        val uid = "series-times-moved-${UUID.randomUUID()}"
        val url = put(uid, listOf(vevent(uid,
            "DTSTART;TZID=America/New_York:20270301T100000", "DTEND;TZID=America/New_York:20270301T110000",
            "RRULE:FREQ=DAILY;COUNT=5", "SUMMARY:Series times moved")), withNyZone = true)

        val (db, master) = pullSeries(uid) ?: return@runBlocking
        coordinator(db).deleteSingleOccurrence(master.id, ny(2027, 3, 3, 10))
        assertPushed(db)
        val afterDelete = db.eventsDao().getById(master.id)!!
        coordinator(db).updateEvent(
            afterDelete.copy(startTs = afterDelete.startTs + 3_600_000, endTs = afterDelete.endTs + 3_600_000, updatedAt = afterDelete.updatedAt + 1)
        )
        assertPushed(db)

        val body = client!!.fetchEvent(db.eventsDao().getById(master.id)?.caldavUrl ?: url).getOrNull()?.icalData.orEmpty()
        report("moved", "served after move=${unfold(body).lines().filter { l -> listOf("DTSTART", "EXDATE").any { l.startsWith(it) } }}")
        report("moved", "server expand=${expand(url)}")
        // The app sends the moved EXDATE only; Zoho keeps the earlier one beside it (it merges
        // EXDATEs on update), which names no occurrence of the moved series.
        val served = seriesOf(body)?.exdates?.map { it.timestamp }.orEmpty()
        if (served != listOf(ny(2027, 3, 3, 11))) report("moved", "SERVER-KEPT EXDATE=${served.map(::fmt)}")
        assertTrue("${config.name}: the moved EXDATE reached the server: ${served.map(::fmt)}", ny(2027, 3, 3, 11) in served)

        val (db2, again) = pullSeries(uid) ?: return@runBlocking
        assertEquals("${config.name}: the deleted day stays hidden", listOf(1, 2, 4, 5).map { fmt(ny(2027, 3, it, 11)) }, shown(db2, again).map(::fmt))
    }

    // ---- setup ----

    private suspend fun ready() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
        val url = discoverWritableCalendar()
        if (url == null) report("setup", "REPORT no writable calendar (discovery found none)")
        assumeTrue("${config.name}: no writable calendar", url != null)
        calendarUrl = url
    }

    private fun vevent(uid: String, vararg lines: String): List<String> =
        listOf("BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261025T000000Z") + lines + "END:VEVENT"

    private suspend fun put(uid: String, components: List<List<String>>, withNyZone: Boolean = false): String {
        val body = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//KashCal//Series times//EN") +
            (if (withNyZone) listOf(NY_VTIMEZONE) else emptyList()) +
            components.flatten() + listOf("END:VCALENDAR", "")).joinToString("\r\n")
        val r = client!!.createEvent(calendarUrl!!, uid, body)
        val url = r.getOrNull()?.first ?: error("${config.name}: create refused: $r")
        created += url
        return url
    }

    /**
     * Pulls the calendar into a fresh database and returns the series row of [uid]. Zoho lists a
     * new resource late and sometimes not within a minute; that is printed and the case skipped.
     */
    private suspend fun pullSeries(uid: String): Pair<KashCalDatabase, Event>? {
        val db = newDatabase()
        val calendar = localCalendar(db, calendarUrl!!)
        val deadline = System.currentTimeMillis() + 60_000
        var master: Event? = null
        while (master == null) {
            pullStrategy(db).pull(db.calendarsDao().getById(calendar.id)!!, forceFullSync = true, client = client!!)
            master = db.eventsDao().getByUid(uid).firstOrNull { it.originalEventId == null }
            if (master == null && System.currentTimeMillis() > deadline) break
            if (master == null) Thread.sleep(5_000)
        }
        if (master == null) {
            report("setup", "${config.name.uppercase()}-NOT-RETURNED: the pull did not store the created series")
            assumeTrue("${config.name}: the pull did not return the created resource", false)
        }
        return db to master!!
    }

    private suspend fun shown(db: KashCalDatabase, master: Event): List<Long> =
        db.occurrencesDao().getForEvent(master.id).filter { !it.isCancelled }.map { it.startTs }.sorted()

    private suspend fun assertPushed(db: KashCalDatabase) {
        val calendar = db.calendarsDao().getAllOnce().first()
        val push = push(db).pushForCalendar(calendar, client!!)
        assertTrue("${config.name}: push $push", push is PushResult.Success && push.pushErrors.isEmpty())
    }

    // ---- the server's own answer ----

    /** An OkHttp client authenticated as the app's is: preemptive Basic, Digest on challenge. */
    private val http: OkHttpClient by lazy {
        val c = creds!!
        OkHttpClient.Builder()
            .authenticator(DigestAuthenticator(c.username, c.password, true))
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                chain.proceed(
                    if (req.header("Authorization") != null) req
                    else req.newBuilder().header("Authorization", Credentials.basic(c.username, c.password, Charsets.UTF_8)).build()
                )
            }
            .build()
    }

    private fun sendReport(xml: String): Pair<Int, String>? = runCatching {
        val req = Request.Builder().url(calendarUrl!!)
            .method("REPORT", xml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1").build()
        http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
    }.getOrNull()

    /** Whether the server's time-range query at exact instants lists [url]; null when it errors. */
    private fun timeRange(start: String, end: String, url: String): Boolean? {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">" +
            "<d:prop><d:getetag/></d:prop><c:filter><c:comp-filter name=\"VCALENDAR\"><c:comp-filter name=\"VEVENT\">" +
            "<c:time-range start=\"$start\" end=\"$end\"/></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>"
        val (code, body) = sendReport(xml) ?: return null
        if (code !in 200..299) return null
        return body.contains(url.substringAfterLast('/').substringBefore(".ics"))
    }

    /** Returns the server's CALDAV:expand of [url] over March 2027, or why it has no instances. */
    private fun expand(url: String): String {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><c:calendar-multiget xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">" +
            "<d:prop><c:calendar-data><c:expand start=\"20270301T000000Z\" end=\"20270401T000000Z\"/></c:calendar-data></d:prop>" +
            "<d:href>${URI(url).rawPath}</d:href></c:calendar-multiget>"
        val (code, body) = sendReport(xml) ?: return "error"
        val data = Regex("calendar-data[^>]*>([\\s\\S]*?)</[^>]*calendar-data>").find(body)?.groupValues?.get(1)
            ?.replace("&#13;", "")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
            ?: return "http$code without calendar-data"
        val instances = Regex("BEGIN:VEVENT[\\s\\S]*?END:VEVENT").findAll(unfold(data)).map { v ->
            v.value.lines().filter { l -> listOf("DTSTART", "RECURRENCE-ID", "RRULE", "EXDATE", "SUMMARY").any { l.startsWith(it) } }
                .joinToString(" ")
        }.toList()
        return if (instances.none { it.contains("RRULE") }) "expanded $instances" else "not expanded (stored object returned)"
    }

    private fun seriesOf(body: String) =
        ICalParser().parseAllEvents(body).getOrNull().orEmpty().firstOrNull { it.recurrenceId == null }

    private fun report(item: String, msg: String) = println(FixtureRedactor.redact("SERIES-TIMES|${config.name}|$item|$msg"))

    private fun unfold(s: String) = s.replace(Regex("\r?\n[ \t]"), "")

    // ---- the app ----

    private suspend fun discoverWritableCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) c.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val writable = c.listCalendars(home).getOrNull().orEmpty()
            .filter { !it.isReadOnly && (it.supportedComponents.isEmpty() || "VEVENT" in it.supportedComponents) }
            .map { it.url }
            .filter { !it.contains("inbox") && !it.contains("outbox") }
        return writable.firstOrNull { it.trimEnd('/').endsWith("/work") } ?: writable.firstOrNull()
    }

    private fun newDatabase(): KashCalDatabase {
        val context: Context = ApplicationProvider.getApplicationContext()
        return TestDatabases.inMemory(context).allowMainThreadQueries().build()
            .also { databases += it }
    }

    private suspend fun localCalendar(db: KashCalDatabase, url: String): Calendar {
        val accountId = db.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "series-times@example.test"))
        val id = db.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = url, displayName = "Series times", color = 0))
        return db.calendarsDao().getById(id)!!
    }

    private fun resolve(calendarUrl: String, href: String): String =
        if (href.startsWith("http")) href else URI(calendarUrl).resolve(href).toString()

    private fun coordinator(db: KashCalDatabase): EventCoordinator {
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        return EventCoordinator(
            eventWriter = EventWriter(db, generator),
            eventReader = EventReader(db),
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = accountRepository(db),
            // Side-effect collaborators only: the test pushes and pulls itself.
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = TestDataStoreFactory.createDefault(),
        )
    }

    private fun push(db: KashCalDatabase) = PushStrategy(
        calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
        eventsDao = db.eventsDao(),
        pendingOperationsDao = db.pendingOperationsDao(),
        accountRepository = accountRepository(db),
        attendeesDao = db.attendeesDao(),
        pendingCancelsDao = db.pendingCancelsDao(),
        icsSource = EventIcsStore(db),
    )

    private fun pullStrategy(db: KashCalDatabase): PullStrategy {
        val dataStore = mockk<KashCalDataStore>(relaxed = true)
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        return PullStrategy(
            database = db,
            calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
            eventsDao = db.eventsDao(),
            attendeesDao = db.attendeesDao(),
            occurrenceGenerator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault()),
            defaultQuirks = config.quirksFactory(creds?.serverUrl ?: config.defaultServerUrl ?: ""),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = accountRepository(db),
            reminderScheduler = mockk(relaxed = true),
            icsSource = EventIcsStore(db),
        )
    }

    private fun accountRepository(db: KashCalDatabase): AccountRepository = AccountRepositoryImpl(
        accountsDao = db.accountsDao(),
        addressBookDao = db.addressBookDao(),
        calendarsDao = db.calendarsDao(),
        eventsDao = db.eventsDao(),
        pendingOperationsDao = db.pendingOperationsDao(),
        // Never read here: the test hands the client its credentials itself.
        credentialManager = mockk(),
        reminderScheduler = mockk(relaxed = true),
        workManager = mockk(relaxed = true),
        contactSystemAccountRegistrar = mockk(relaxed = true),
        contactsProviderRepository = FakeContactsProviderRepository(),
    )
}
