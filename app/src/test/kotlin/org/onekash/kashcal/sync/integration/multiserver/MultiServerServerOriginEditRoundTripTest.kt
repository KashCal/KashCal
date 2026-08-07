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
 * Regression coverage for a class of bug where editing an event that
 * ORIGINATED ON THE SERVER (created by another client, then pulled) fails to
 * sync back — the local↔server link freezes and further edits/deletes stop
 * reconciling in either direction. Reported against a Cyrus-backed provider
 * (issue #311), where the observable symptom was a `Push … conflict (412)`.
 *
 * The distinction that matters: every other round-trip test in this package
 * edits an event KashCal itself CREATED (it never leaves the client's own
 * serialized body / etag). This one instead drives the exact production chain
 * the reporter hits:
 *
 *   raw server PUT (server-origin event, NOT KashCal serialized)
 *     -> PullStrategy.pull  (ingest into real Room: caldavUrl + etag + rawIcal)
 *     -> EventWriter.updateEvent / editSingleOccurrence  (queues a pending op)
 *     -> PushStrategy.pushForCalendar  (drains it via an If-Match PUT)
 *     -> fetch back from the server and assert the edit landed
 *
 * A 412 on the drain (stale/mismatched If-Match built from the pulled etag),
 * or a push that reports success without the server body changing, fails the
 * test — either would reproduce the frozen-link report.
 *
 * Uses a REAL in-memory Room DB and the REAL Pull/Push strategies so the
 * pulled etag, caldavUrl, and rawIcal are the ones the strategies actually
 * persist — not fixtures. Only side-effect collaborators (credential store,
 * reminder scheduler, WorkManager, invite notifier) are relaxed mocks.
 *
 * Parameterized across every configured server so the pulled-event edit path
 * is exercised wherever creds are present; each case gates on reachability and
 * silently skips otherwise (see assumeReady). The plain single-event body has
 * no ORGANIZER/ATTENDEE, so no server routes it through iTIP scheduling
 * delivery — the fetched body echoes the edit directly.
 *
 * Safety: only mutates events created by this run (unique uid prefix); cleanup
 * deletes only those hrefs. Failure-message ICS bodies are PII-redacted.
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
        // Far-future anchor so strict servers don't reject "event in the past".
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
        // PullStrategy reads the sync-lookback window; without a real Flow the
        // relaxed default is non-functional and the pull fails. MAX = "All".
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
     * Insert a local account + calendar row pointing at a real server calendar
     * URL, so the pulled events land under it and the push routes to it.
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

    // A plain, invitee-free VEVENT — the "single event" the reporter creates on
    // the server. No ORGANIZER/ATTENDEE so no server reroutes it through iTIP.
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

        // 1. Create the event ON THE SERVER (server-origin, not KashCal-serialized).
        val createResult = client!!.createEvent(calendarUrl!!, uid, singleEventIcs(uid))
        assumeTrue("create failed on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess())
        val (url, createEtag) = createResult.getOrNull()!!
        trackEvent(url, createEtag)

        // 2. Pull it into KashCal (this is the leg every other test skips).
        val calendar = localCalendarFor(calendarUrl)
        val pullResult = pullStrategy.pull(calendar, forceFullSync = true, client = client!!)
        val pulled = database.eventsDao().getByUid(uid).firstOrNull()
        assumeTrue("event did not pull into Room on ${config.name} (pull=$pullResult)", pulled != null)
        assertTrue("${config.name}: pulled event must carry the server href",
            pulled!!.caldavUrl != null)
        assertFalse("${config.name}: pulled event must carry a non-empty server etag",
            pulled.etag.isNullOrEmpty())

        // 3. Edit the pulled event through the normal write path. It's a synced
        //    CalDAV event (isLocal = false), so this queues a PENDING_UPDATE for
        //    the push to drain — a device-only (isLocal = true) event wouldn't.
        val newTitle = "Server-origin single (edited)"
        eventWriter.updateEvent(pulled.copy(title = newTitle), isLocal = false)

        // 4. Drain the pending op — the If-Match PUT built from the pulled etag.
        val pushResult = pushStrategy.pushForCalendar(calendar, client!!)
        assertPushClean(pushResult)

        // 5. The server body must reflect the edit (no frozen link).
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

        // 1. Create the recurring master ON THE SERVER.
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

        // 3. Edit the SECOND occurrence (a weekly step from the pulled DTSTART).
        val occurrenceTimeMs = master.startTs + 7 * DAY_MS
        val newTitle = "Server-origin recurring (instance edited)"
        eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrenceTimeMs,
            modifiedEvent = master.copy(title = newTitle),
            isLocal = false
        )

        // 4. Drain — the exception is bundled with the master via an If-Match PUT.
        val pushResult = pushStrategy.pushForCalendar(calendar, client!!)
        assertPushClean(pushResult)

        // 5. The server body must now carry the edited override (RECURRENCE-ID VEVENT).
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
     * A push that reached the server has zero pushErrors and no 412 warning.
     * A 412 conflict surfaces as a pushWarning (the frozen-link symptom); a
     * hard failure surfaces as pushErrors. Either fails the round-trip.
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
