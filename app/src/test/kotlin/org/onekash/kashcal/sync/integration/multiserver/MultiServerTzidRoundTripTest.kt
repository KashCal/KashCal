package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.EventIcsStore
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.util.resolveWindowsZone
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Pulls Exchange-shaped events from every test server and checks each is stored at the instant
 * its own VTIMEZONE gives (#441): a Windows zone ID, `TZID=UTC` with Exchange's VTIMEZONE UTC, the
 * Windows display name `(UTC+08:00) Beijing, Chongqing, Hong Kong, Urumqi` (its VTIMEZONE escapes
 * the commas; iCloud drops that VTIMEZONE), and `(UTC-06:00) Central Time (US & Canada)`.
 *
 * On the test servers, iCloud, Stalwart, Baikal, Nextcloud, SOGo and Cyrus keep `TZID=UTC` as sent;
 * Radicale stores `TZID=UTC:...Z`; Xandikos and Zoho rewrite to `Z`; Zoho also rewrites the
 * display names to IANA zones. Bodies carry no METHOD: Baikal and Nextcloud answer 415 and Cyrus
 * 403 to a calendar object with one (RFC 4791 §4.1).
 *
 * The device zone is pinned to Asia/Tokyo so a fallback to it shows. Events go through the real
 * PullStrategy into Room; on iCloud only this run's own hrefs are fetched by multiget and stored
 * through the mapper. Cleanup deletes only the URLs this run created.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration --tests '*MultiServerTzidRoundTripTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerTzidRoundTripTest(private val config: CalDavServerConfig) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }
        private val DAY: LocalDate = LocalDate.now(ZoneOffset.UTC).plusDays(21)
        private val LOCAL = DAY.toString().replace("-", "") + "T100000"
        private val LOCAL_END = DAY.toString().replace("-", "") + "T110000"
        private const val BEIJING = "(UTC+08:00) Beijing, Chongqing, Hong Kong, Urumqi"
        private const val US_CENTRAL = "(UTC-06:00) Central Time (US & Canada)"
    }

    private lateinit var database: KashCalDatabase
    private lateinit var pullStrategy: PullStrategy
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val created = mutableListOf<String>()
    private val savedZone = java.util.TimeZone.getDefault()

    @Before
    fun setup() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        ICalDateTime.customTimezoneResolver = ::resolveWindowsZone
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        CalDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
        val dataStore = mockk<KashCalDataStore>(relaxed = true)
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = config.quirksFactory(creds?.serverUrl ?: config.defaultServerUrl ?: ""),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            icsSource = EventIcsStore(database),
        )
    }

    @After
    fun cleanup() = runBlocking {
        client?.let { c -> for (u in created.reversed()) try { c.deleteEvent(u, c.fetchEtag(u).getOrNull()) } catch (_: Exception) {} }
        if (::database.isInitialized) database.close()
        java.util.TimeZone.setDefault(savedZone)
        ICalDateTime.customTimezoneResolver = null
        unmockkAll()
    }

    private val vtzCentral = """
        BEGIN:VTIMEZONE
        TZID:Central Standard Time
        BEGIN:STANDARD
        DTSTART:16010101T020000
        TZOFFSETFROM:-0500
        TZOFFSETTO:-0600
        RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=1SU;BYMONTH=11
        END:STANDARD
        BEGIN:DAYLIGHT
        DTSTART:16010101T020000
        TZOFFSETFROM:-0600
        TZOFFSETTO:-0500
        RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=2SU;BYMONTH=3
        END:DAYLIGHT
        END:VTIMEZONE
    """.trimIndent()

    private fun vtzFixed(tzid: String, off: String) = """
        BEGIN:VTIMEZONE
        TZID:$tzid
        BEGIN:STANDARD
        DTSTART:16010101T000000
        TZOFFSETFROM:$off
        TZOFFSETTO:$off
        END:STANDARD
        BEGIN:DAYLIGHT
        DTSTART:16010101T000000
        TZOFFSETFROM:$off
        TZOFFSETTO:$off
        END:DAYLIGHT
        END:VTIMEZONE
    """.trimIndent()

    private fun body(uid: String, vtz: String, tzParam: String) = (
        "BEGIN:VCALENDAR\nPRODID:Microsoft Exchange Server 2010\nVERSION:2.0\n$vtz\n" +
            "BEGIN:VEVENT\nUID:$uid\nSUMMARY:Exchange zone round trip\nDTSTAMP:20261119T000000Z\n" +
            "DTSTART;TZID=$tzParam:$LOCAL\nDTEND;TZID=$tzParam:$LOCAL_END\n" +
            "X-MICROSOFT-CDO-BUSYSTATUS:BUSY\nEND:VEVENT\nEND:VCALENDAR\n"
        ).replace("\n", "\r\n")

    @Test
    fun `Exchange zone forms are stored at their VTIMEZONE offsets`() = runBlocking {
        assumeTrue("${config.name} credentials", client != null && creds != null)
        assumeTrue("${config.name} reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) c.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val home = c.discoverPrincipal(caldavUrl).getOrNull()?.let { c.discoverCalendarHome(it).getOrNull()?.firstOrNull() }
        val cal = home?.let { c.listCalendars(it).getOrNull() }.orEmpty()
            .filter { !it.url.contains("inbox") && !it.url.contains("outbox") }
            .filter { !it.isReadOnly && (it.supportedComponents.isEmpty() || "VEVENT" in it.supportedComponents) }
            .let { l -> l.firstOrNull { it.url.trimEnd('/').endsWith("/work") } ?: l.firstOrNull() }
        assumeTrue("${config.name} calendar", cal != null)
        val calendarUrl = cal!!.url

        val ldt = LocalDateTime.parse(LOCAL, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        val cases = listOf(
            Triple("windows-id", body("", vtzCentral, "Central Standard Time"), ldt.atZone(ZoneId.of("America/Chicago")).toInstant().toEpochMilli()),
            Triple("tzid-utc", body("", vtzFixed("UTC", "+0000"), "UTC"), ldt.toInstant(ZoneOffset.UTC).toEpochMilli()),
            Triple("display-name", body("", vtzFixed("(UTC+08:00) Beijing\\, Chongqing\\, Hong Kong\\, Urumqi", "+0800"), "\"$BEIJING\""), ldt.toInstant(ZoneOffset.ofHours(8)).toEpochMilli()),
            Triple("us-display-name", body("", vtzCentral.replace("TZID:Central Standard Time", "TZID:$US_CENTRAL"), "\"$US_CENTRAL\""), ldt.atZone(ZoneId.of("America/Chicago")).toInstant().toEpochMilli()),
        )
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "zone-round-trip@example.test"))
        val calendarId = database.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = calendarUrl, displayName = "Exchange zones", color = 0))
        val calendar = database.calendarsDao().getById(calendarId)!!

        // Case name to (UID, created URL, expected start). Rows are found by UID: a server may list
        // the resource under a differently encoded href than the one the PUT returned.
        val uids = mutableMapOf<String, Triple<String, String, Long>>()
        for ((name, template, expected) in cases) {
            val uid = "zone-round-trip-${config.name.lowercase()}-$name-${UUID.randomUUID()}"
            val b = template.replace("UID:\r\n", "UID:$uid\r\n")
            val r = c.createEvent(calendarUrl, uid, b)
            assertTrue(
                "${config.name} $name: PUT refused ${(r as? CalDavResult.Error)?.let { "${it.code} ${it.message}" }}",
                r.isSuccess()
            )
            val url = r.getOrNull()!!.first.also { created += it }
            uids[name] = Triple(uid, url, expected)
            if (config.name == CalDavServerConfig.ICLOUD.name) {
                val href = java.net.URI(url).rawPath
                val f = c.fetchEventsByHref(calendarUrl, listOf(href)).getOrNull()?.singleOrNull()
                    ?: error("${config.name} $name: multiget must return this run's own event")
                val parsed = ICalParser().parseAllEvents(f.icalData).getOrNull()!!.first()
                database.eventsDao().insert(ICalEventMapper.toEntity(parsed, f.icalData, calendar.id, url, f.etag).event)
            }
        }
        if (config.name != CalDavServerConfig.ICLOUD.name) {
            // Zoho's listing can lag a fresh PUT by seconds, so pull again until it lists them.
            for (attempt in 1..5) {
                pullStrategy.pull(calendar, forceFullSync = true, client = c)
                if (uids.values.all { (uid, _, _) -> database.eventsDao().getByUid(uid).isNotEmpty() }) break
                if (attempt < 5) kotlinx.coroutines.delay(3_000)
            }
        }
        for ((name, case) in uids) {
            val (uid, url, expected) = case
            val row = database.eventsDao().getByUid(uid).firstOrNull()
            assertTrue("${config.name} $name: pulled into Room", row != null)
            val stored = c.fetchEvent(url).getOrNull()?.icalData.orEmpty()
            assertEquals(
                "${config.name} $name stored in ${row!!.timezone}; server copy: ${FixtureRedactor.redact(stored)}",
                expected,
                row.startTs
            )
        }
    }
}
