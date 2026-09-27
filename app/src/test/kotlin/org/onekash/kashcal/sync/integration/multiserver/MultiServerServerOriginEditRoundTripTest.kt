package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Checks that editing an event created on the server by another client, then pulled, syncs
 * back. When it doesn't, the link between the local row and the server freezes and later edits
 * and deletes stop reconciling in either direction. Reported against a Cyrus-backed provider
 * (issue #311) as a `Push … conflict (412)`.
 *
 * Unlike a round trip over an event the app created, which never leaves the app's own
 * serialized body and etag, this drives the chain the reporter hit:
 *
 *   raw server PUT (a body the app didn't serialize)
 *     -> PullStrategy.pull (into real Room: caldavUrl, etag, rawIcal)
 *     -> EventWriter.updateEvent or editSingleOccurrence (queues a pending op)
 *     -> PushStrategy.pushForCalendar (drains it with an If-Match PUT)
 *     -> fetch back from the server and check the edit landed
 *
 * A 412 on the drain (an If-Match from the pulled etag that no longer matches), or a push that
 * reports success while the server body stays unchanged, fails the test: either reproduces the
 * frozen link.
 *
 * Uses a real in-memory Room database and the real Pull and Push strategies, so the etag,
 * caldavUrl and rawIcal are the ones the strategies persist. The other collaborators are relaxed
 * mocks: the data store (its reminder and lookback flows stubbed), PullStrategy's account
 * repository, the invite notifier, the reminder schedulers, the credential manager, WorkManager
 * and the contacts collaborators.
 *
 * Runs on every configured server and skips one without credentials or unreachable ([assumeReady]),
 * or where discovery, the server-side create or the pull fails. The bodies have no ORGANIZER or
 * ATTENDEE, so no server routes them through iTIP delivery and the fetched body shows the edit as
 * sent.
 *
 * Mutates only events this run created (unique UID prefix), and cleanup deletes only those
 * hrefs. ICS bodies in failure messages are PII-redacted.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerServerOriginEditRoundTripTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerServerOriginEditRoundTripTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val classStartMs = System.currentTimeMillis()
        private val UID_PREFIX = "server-origin-edit-$classStartMs-"
        private const val DAY_MS = 86_400_000L
        // 21 days out, so a strict server doesn't reject an event in the past.
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 21) * DAY_MS + 9 * 3_600_000L

        private val icsUtc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var pullStrategy: PullStrategy
    private lateinit var pushStrategy: PushStrategy
    private lateinit var occurrenceGenerator: OccurrenceGenerator

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val createdEventUrls = mutableListOf<Pair<String, String>>()

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries().build()

        occurrenceGenerator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(),
            TestDataStoreFactory.createDefault()
        )
        eventWriter = EventWriter(database, occurrenceGenerator)

        val dataStore = mockk<KashCalDataStore>(relaxed = true)
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)
        // PullStrategy reads the sync lookback; the relaxed default isn't a working Flow and
        // the pull fails. Int.MAX_VALUE means "All".
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
            reminderScheduler = mockk(relaxed = true)
        )

        pushStrategy = PushStrategy(
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = AccountRepositoryImpl(
                accountsDao = database.accountsDao(),
                addressBookDao = database.addressBookDao(),
                calendarsDao = database.calendarsDao(),
                eventsDao = database.eventsDao(),
                pendingOperationsDao = database.pendingOperationsDao(),
                credentialManager = mockk(relaxed = true),
                reminderScheduler = mockk(relaxed = true),
                workManager = mockk(relaxed = true),
                contactSystemAccountRegistrar = mockk(relaxed = true),
                contactsProviderRepository = mockk(relaxed = true)
            ),
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao()
        )

        CalDavTestServerLoader.createClient(config)?.let {
            client = it.first; creds = it.second
        }
    }

    @After
    fun cleanup() = runBlocking {
        client?.let { c ->
            for ((url, etag) in createdEventUrls.reversed()) {
                try { c.deleteEvent(url, etag) } catch (_: Exception) { /* best-effort */ }
            }
        }
        if (::database.isInitialized) database.close()
        unmockkAll()
    }

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
    }

    private suspend fun discoverCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(endpoint).getOrNull() ?: endpoint
        } else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return c.listCalendars(home).getOrNull()
            ?.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }

    private fun trackEvent(url: String, etag: String) {
        createdEventUrls.removeAll { it.first == url }
        createdEventUrls.add(Pair(url, etag))
    }

    private fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")
    private fun summaryOf(ics: String): String? =
        unfold(ics).lines().firstOrNull { it.trimStart().startsWith("SUMMARY") }
            ?.substringAfter(':')?.trim()

    /**
     * Inserts a CalDAV account and a calendar row for [calendarUrl], so pulled events land under it
     * and the push routes to it.
     */
    private fun localCalendarFor(calendarUrl: String): Calendar {
        val accountId = runBlocking {
            database.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = "server-origin@example.test")
            )
        }
        val calendarId = runBlocking {
            database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = calendarUrl,
                    displayName = "Server-origin",
                    color = 0xFF0000FF.toInt()
                )
            )
        }
        return runBlocking { database.calendarsDao().getById(calendarId)!! }
    }

    // The single event the reporter created on the server. No ORGANIZER or ATTENDEE, so no server
    // routes it through iTIP.
    private fun singleEventIcs(uid: String): String =
        """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Server Origin Edit//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsUtc.format(Date(START_MS))}
DTSTART:${icsUtc.format(Date(START_MS))}
DTEND:${icsUtc.format(Date(START_MS + 3_600_000L))}
SUMMARY:Server-origin single
END:VEVENT
END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

    private fun recurringMasterIcs(uid: String): String =
        """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Server Origin Edit//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsUtc.format(Date(START_MS))}
DTSTART:${icsUtc.format(Date(START_MS))}
DTEND:${icsUtc.format(Date(START_MS + 3_600_000L))}
RRULE:FREQ=WEEKLY;COUNT=5
SUMMARY:Server-origin recurring
END:VEVENT
END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

    // ---- editing a server-created single event syncs back ----

    @Test
    fun `editing a server-created single event syncs back to the server`() = runBlocking {
        assumeReady()
        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-single"

        // 1. Create the event on the server with a body the app didn't serialize.
        val createResult = client!!.createEvent(calendarUrl!!, uid, singleEventIcs(uid))
        assumeTrue("create failed on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess())
        val (url, createEtag) = createResult.getOrNull()!!
        trackEvent(url, createEtag)

        // 2. Pull it in: the step a round trip over an app-created event skips.
        val calendar = localCalendarFor(calendarUrl)
        val pullResult = pullStrategy.pull(calendar, forceFullSync = true, client = client!!)
        val pulled = database.eventsDao().getByUid(uid).firstOrNull()
        assumeTrue("event did not pull into Room on ${config.name} (pull=$pullResult)", pulled != null)
        assertTrue("${config.name}: pulled event must carry the server href",
            pulled!!.caldavUrl != null)
        assertFalse("${config.name}: pulled event must carry a non-empty server etag",
            pulled.etag.isNullOrEmpty())

        // 3. Edit the pulled event through the normal write path. It's a synced CalDAV event
        //    (isLocal = false), so this queues an UPDATE for the push to drain; a local-calendar
        //    event (isLocal = true) wouldn't queue one.
        val newTitle = "Server-origin single (edited)"
        eventWriter.updateEvent(pulled.copy(title = newTitle), isLocal = false)

        // 4. Drain the pending op: an If-Match PUT built from the pulled etag.
        val pushResult = pushStrategy.pushForCalendar(calendar, client!!)
        assertPushClean(pushResult)

        // 5. The server body must show the edit (no frozen link).
        val stored = client!!.fetchEvent(url).getOrNull()!!.icalData
        assertTrue(
            "${config.name}: edit must round-trip to the server, got SUMMARY=" +
                "'${summaryOf(stored)}' — body: ${FixtureRedactor.redact(stored)}",
            summaryOf(stored) == newTitle
        )
        // Track the latest etag for cleanup.
        client!!.fetchEtag(url).getOrNull()?.let { trackEvent(url, it) }
        println("SERVER-ORIGIN ${config.name}: single-event edit synced back")
    }

    // ---- editing one instance of a server-created recurring series syncs back ----

    @Test
    fun `editing one instance of a server-created recurring series syncs back`() = runBlocking {
        assumeReady()
        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-recurring"

        // 1. Create the recurring master on the server.
        val createResult = client!!.createEvent(calendarUrl!!, uid, recurringMasterIcs(uid))
        assumeTrue("create failed on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess())
        val (url, createEtag) = createResult.getOrNull()!!
        trackEvent(url, createEtag)

        // 2. Pull it in.
        val calendar = localCalendarFor(calendarUrl)
        val pullResult = pullStrategy.pull(calendar, forceFullSync = true, client = client!!)
        val master = database.eventsDao().getByUid(uid).firstOrNull { it.originalEventId == null }
        assumeTrue("master did not pull into Room on ${config.name} (pull=$pullResult)", master != null)
        assumeTrue("${config.name}: pulled master must be recurring", master!!.isRecurring)

        // 3. Edit the second occurrence, one week after the pulled DTSTART.
        val occurrenceTimeMs = master.startTs + 7 * DAY_MS
        val newTitle = "Server-origin recurring (instance edited)"
        eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrenceTimeMs,
            modifiedEvent = master.copy(title = newTitle),
            isLocal = false
        )

        // 4. Drain: the exception goes up bundled with the master in an If-Match PUT.
        val pushResult = pushStrategy.pushForCalendar(calendar, client!!)
        assertPushClean(pushResult)

        // 5. The server body must carry the edited title, which only the exception has.
        val stored = client!!.fetchEvent(url).getOrNull()!!.icalData
        assertTrue(
            "${config.name}: instance edit must round-trip; server body: " +
                FixtureRedactor.redact(stored),
            unfold(stored).contains(newTitle)
        )
        client!!.fetchEtag(url).getOrNull()?.let { trackEvent(url, it) }
        println("SERVER-ORIGIN ${config.name}: recurring-instance edit synced back")
    }

    /**
     * Fails unless [result] is a Success with no pushErrors, no 412 conflict among its
     * pushWarnings, and at least one event updated. A 412 lands in pushWarnings (the frozen-link
     * symptom); a failure that won't be retried lands in pushErrors.
     */
    private fun assertPushClean(result: PushResult) {
        assertTrue(
            "${config.name}: push must succeed, got $result",
            result is PushResult.Success
        )
        val success = result as PushResult.Success
        assertTrue(
            "${config.name}: push reported permanent failures: ${success.pushErrors}",
            success.pushErrors.isEmpty()
        )
        assertFalse(
            "${config.name}: push hit a 412 conflict (frozen link): ${success.pushWarnings}",
            success.pushWarnings.any { it.contains("412") }
        )
        assertTrue(
            "${config.name}: expected the update to be pushed, got $success",
            success.eventsUpdated >= 1
        )
    }
}
