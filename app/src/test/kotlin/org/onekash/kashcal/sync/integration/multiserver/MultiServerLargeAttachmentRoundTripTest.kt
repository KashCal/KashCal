package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import java.util.UUID

/**
 * Checks that an event with a large inline attachment (RFC 5545 §3.8.1.1 BINARY) syncs into
 * KashCal, reads back from Room within Android's 2 MB cursor window, and keeps its attachment on
 * the server after an edit made in KashCal.
 *
 * Runs a 600,000-char attachment on every server, and a 1,200,000-char one where the server's
 * stored body stays under 1.5 MB; Xandikos re-encodes inline attachments to about 1.8 times
 * their size, past what one row can hold. A server that refuses the body (iCloud 507, Baikal
 * 413, Zoho 415, Stalwart's 512 KB limit) skips by assumption with its status.
 *
 * Real Pull and Push strategies over a real in-memory Room database. Deletes every event it
 * created by its own URL. Failure messages carry lengths and status codes, never bodies.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerLargeAttachmentRoundTripTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerLargeAttachmentRoundTripTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val UID_PREFIX = "large-attach-${System.currentTimeMillis()}-"
        private const val DAY_MS = 86_400_000L
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 21) * DAY_MS + 9 * 3_600_000L
        private const val ONE_ROW_LIMIT_CHARS = 1_500_000
        private val icsUtc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private lateinit var database: KashCalDatabase
    private lateinit var pullStrategy: PullStrategy
    private lateinit var pushStrategy: PushStrategy
    private lateinit var eventWriter: EventWriter
    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val created = mutableListOf<String>()

    @Before
    fun setup() {
        CalDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries().build()
        val dataStore = TestDataStoreFactory.createDefault()
        val generator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), dataStore)
        eventWriter = EventWriter(database, generator)
        val accountRepository = AccountRepositoryImpl(
            accountsDao = database.accountsDao(), addressBookDao = database.addressBookDao(),
            calendarsDao = database.calendarsDao(), eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            credentialManager = mockk(relaxed = true), reminderScheduler = mockk(relaxed = true),
            workManager = mockk(relaxed = true), contactSystemAccountRegistrar = mockk(relaxed = true),
            contactsProviderRepository = mockk(relaxed = true)
        )
        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = generator,
            defaultQuirks = config.quirksFactory(creds?.serverUrl ?: config.defaultServerUrl ?: ""),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = accountRepository,
            reminderScheduler = mockk(relaxed = true)
        )
        pushStrategy = PushStrategy(
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = accountRepository,
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao()
        )
    }

    @After
    fun cleanup() = runBlocking {
        client?.let { c -> created.forEach { runCatching { c.deleteEvent(it, null) } } }
        if (::database.isInitialized) database.close()
        unmockkAll()
    }

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
    }

    private suspend fun discoverCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) c.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return c.listCalendars(home).getOrNull()
            ?.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }

    private fun fold(line: String): String = buildString {
        var i = 0
        while (i < line.length) {
            val end = minOf(line.length, i + if (i == 0) 75 else 74)
            if (i > 0) append("\r\n ")
            append(line, i, end)
            i = end
        }
    }

    private fun body(uid: String, attachChars: Int): String {
        val bytes = ByteArray(attachChars / 4 * 3).also { Random(attachChars.toLong()).nextBytes(it) }
        val start = icsUtc.format(Date(START_MS))
        val end = icsUtc.format(Date(START_MS + 3_600_000L))
        return listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Other Client//Large Attachment//EN",
            "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:$start", "DTSTART:$start", "DTEND:$end",
            "SUMMARY:Large attachment", "DESCRIPTION:Short notes",
            fold("ATTACH;FMTTYPE=application/pdf;ENCODING=BASE64;VALUE=BINARY;X-FILENAME=a.pdf:" +
                Base64.getEncoder().encodeToString(bytes)),
            "END:VEVENT", "END:VCALENDAR",
        ).joinToString("\r\n") + "\r\n"
    }

    private fun unfold(ics: String) = UnknownPropertyLineCompare.unfold(ics)

    private fun attachValue(ics: String): String? =
        unfold(ics).lines().firstOrNull { it.startsWith("ATTACH") }?.substringAfter(':')?.trimEnd()

    private fun roundTrip(attachChars: Int, requireFitsOneRow: Boolean) = runBlocking {
        assumeReady()
        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)
        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-$attachChars"

        val put = client!!.createEvent(calendarUrl!!, uid, body(uid, attachChars))
        val refusal = (put as? CalDavResult.Error)?.code
        // Size refusals seen on the test servers; any other create failure fails the test.
        assumeTrue(
            "${config.name} refused a $attachChars-char attachment: $refusal",
            refusal !in setOf(507, 413, 415, 412)
        )
        assertTrue("${config.name}: create failed with $refusal", put.isSuccess())
        val url = put.getOrNull()!!.first
        created += url
        val before = client!!.fetchEvent(url).getOrNull()?.icalData
        assertNotNull("${config.name}: the created event must be fetchable", before)
        val serverAttach = attachValue(before!!)
        assertNotNull("${config.name}: the server must keep the attachment", serverAttach)
        if (requireFitsOneRow) {
            assumeTrue(
                "${config.name} stores the body as ${before.length} chars, past one row",
                before.length < ONE_ROW_LIMIT_CHARS
            )
        }

        val calendar = database.calendarsDao().getById(
            database.calendarsDao().insert(
                Calendar(
                    accountId = database.accountsDao().insert(
                        Account(provider = AccountProvider.CALDAV, email = "large@example.test")
                    ),
                    caldavUrl = calendarUrl, displayName = "Large", color = 0xFF0000FF.toInt()
                )
            )
        )!!
        val pull = pullStrategy.pull(calendar, forceFullSync = true, client = client!!)
        assertTrue("${config.name}: pull must succeed, got ${pull.javaClass.simpleName}", pull is PullResult.Success)

        val row = try {
            database.eventsDao().getMasterByUidAndCalendar(uid, calendar.id)
        } catch (e: SQLiteBlobTooBigException) {
            fail("${config.name}: the pulled row (body ${before.length} chars) must read back")
            null
        }
        assertNotNull("${config.name}: the event must be stored", row)

        eventWriter.updateEvent(row!!.copy(title = "Large attachment (edited)"), isLocal = false)
        val push = pushStrategy.pushForCalendar(calendar, client!!)
        assertTrue("${config.name}: push must succeed, got ${push.javaClass.simpleName}", push is PushResult.Success)
        assertEquals("${config.name}: the edit must be pushed", 1, (push as PushResult.Success).eventsUpdated)

        val after = client!!.fetchEvent(url).getOrNull()?.icalData
        assertNotNull(after)
        assertTrue(
            "${config.name}: the server must hold the edited title",
            unfold(after!!).lines().any { it.trimEnd() == "SUMMARY:Large attachment (edited)" }
        )
        val afterAttach = attachValue(after)
        assertEquals(
            "${config.name}: the attachment must come back unchanged " +
                "(${serverAttach!!.length} chars before, ${afterAttach?.length} after)",
            serverAttach, afterAttach
        )
    }

    @Test
    fun `a 600 KB inline attachment syncs, reads back and survives an edit`() =
        roundTrip(600_000, requireFitsOneRow = false)

    @Test
    fun `a 1_2 MB inline attachment syncs, reads back and survives an edit`() =
        roundTrip(1_200_000, requireFitsOneRow = true)
}
