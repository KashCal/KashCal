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
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * End-to-end proof, against a real Cyrus (the CalDAV engine Fastmail runs),
 * that the client recovers from the exact server-driven ETag drift behind
 * issue #311.
 *
 * The reporter's freeze needs a *scheduling object* (ORGANIZER present): after
 * an attendee reply is auto-processed, Cyrus rewrites the organizer's copy and
 * the ETag drifts while the Schedule-Tag stays stable (RFC 6638 §3.2.10). A
 * later edit or delete built from the pulled (now stale) ETag then 412s. Every
 * other Cyrus round-trip test uses a plain, ORGANIZER-free event, so none of
 * them ever drift and none exercise the 412-recovery path.
 *
 * This test drives the real production chain:
 *   organizer PUT (scheduling object) -> pull into real Room
 *     -> a second user ACCEPTS (server drifts the organizer ETag)
 *     -> queue an edit / a delete against the stale pulled ETag
 *     -> PushStrategy.pushForCalendar drains it
 *     -> assert no 412 warning and the server reflects the outcome
 *
 * A 412 that reached the caller surfaces as a pushWarning containing "412"
 * (the frozen-link symptom); recovery means the strategy refetched the ETag and
 * retried once. Cyrus-only (needs the scheduling pipeline + a second local
 * user); silently skipped when the container/creds are absent.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*CyrusScheduleDriftRecoveryTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CyrusScheduleDriftRecoveryTest {

    private val config = CalDavServerConfig.CYRUS

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var pullStrategy: PullStrategy
    private lateinit var pushStrategy: PushStrategy
    private lateinit var occurrenceGenerator: OccurrenceGenerator

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val createdEventUrls = mutableListOf<Pair<String, String>>()

    // A far-future anchor so strict servers don't reject "event in the past".
    private val dayMs = 86_400_000L
    private val startMs = ((System.currentTimeMillis() / dayMs) + 21) * dayMs + 9 * 3_600_000L
    private val icsUtc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Second local Cyrus user whose accept drives the organizer-copy drift. The
    // test image seeds user1..user5, any password.
    private val organizerUser = "user1"
    private val attendeeUser = "user2"
    private val attendeeMailto = "mailto:user2@example.com"
    private val organizerMailto = "mailto:user1@example.com"

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

    private fun localCalendarFor(calendarUrl: String): Calendar {
        val accountId = runBlocking {
            database.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = "$organizerUser@example.com")
            )
        }
        val calendarId = runBlocking {
            database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = calendarUrl,
                    displayName = "Cyrus drift",
                    color = 0xFF0000FF.toInt()
                )
            )
        }
        return runBlocking { database.calendarsDao().getById(calendarId)!! }
    }

    // A scheduling object: ORGANIZER=user1 with user2 invited. This is the class
    // of event that drifts; a plain event never enters the scheduling pipeline.
    private fun schedulingObjectIcs(uid: String, partstat: String): String =
        """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Cyrus Drift//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsUtc.format(Date(startMs))}
DTSTART:${icsUtc.format(Date(startMs))}
DTEND:${icsUtc.format(Date(startMs + 3_600_000L))}
SUMMARY:Cyrus drift scheduling object
ORGANIZER;CN=User One:$organizerMailto
ATTENDEE;CN=User One;ROLE=CHAIR;PARTSTAT=ACCEPTED:$organizerMailto
ATTENDEE;CN=User Two;ROLE=REQ-PARTICIPANT;PARTSTAT=$partstat;RSVP=TRUE:$attendeeMailto
END:VEVENT
END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

    /**
     * A second CalDavClient authenticated as [attendeeUser], used to accept the
     * invitation from the attendee's side. Built the same way the loader builds
     * the organizer client, but with the attendee's username.
     */
    private fun attendeeClient(): CalDavClient {
        val quirks = config.quirksFactory(creds!!.serverUrl)
        return OkHttpCalDavClientFactory().createClient(
            Credentials(
                username = attendeeUser,
                password = creds!!.password,
                serverUrl = creds!!.davEndpoint
            ),
            quirks
        )
    }

    /**
     * Have [attendeeUser] accept the invitation, which makes Cyrus auto-update
     * the organizer's copy and drift its ETag. Returns true if the drift
     * actually happened (delivery + auto-process succeeded), false if the local
     * container lacks scheduling-delivery rights — in which case the caller
     * skips rather than asserting on a drift that never occurred.
     */
    private fun triggerDriftViaAttendeeAccept(uid: String, organizerUrl: String): Boolean = runBlocking {
        val organizerClient = client!!
        val etagBefore = organizerClient.fetchEtag(organizerUrl).getOrNull()

        // Discover the attendee's own calendar home and find the delivered copy
        // of this UID (the invite filename is server-assigned, so match by body).
        val ac = attendeeClient()
        val endpoint = creds!!.davEndpoint
        val base = if (config.usesWellKnownDiscovery) {
            ac.discoverWellKnown(endpoint).getOrNull() ?: endpoint
        } else endpoint
        val principal = ac.discoverPrincipal(base).getOrNull()
            ?: return@runBlocking false.also { println("CYRUS DRIFT: no attendee principal") }
        val home = ac.discoverCalendarHome(principal).getOrNull()?.firstOrNull()
            ?: return@runBlocking false.also { println("CYRUS DRIFT: no attendee calendar home") }
        val attendeeCal = ac.listCalendars(home).getOrNull()
            ?.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
            ?: return@runBlocking false.also { println("CYRUS DRIFT: no attendee calendar") }

        // Resolve any relative href (e.g. "/dav/calendars/...") against the
        // server origin — fetchEvent needs an absolute URL.
        val origin = creds!!.serverUrl.trimEnd('/')
            .let { Regex("""^(https?://[^/]+)""").find(it)?.groupValues?.get(1) ?: it }
        fun absolute(href: String) = if (href.startsWith("http")) href else origin + href

        val hrefs = ac.fetchAllEtags(attendeeCal).getOrNull() ?: emptyList()
        val attendeeEventUrl = hrefs.map { absolute(it.first) }.firstOrNull { href ->
            ac.fetchEvent(href).getOrNull()?.icalData?.contains("UID:$uid") == true
        }
        if (attendeeEventUrl == null) {
            println("CYRUS DRIFT: invite not delivered to $attendeeUser — cannot drift (delivery rights?)")
            return@runBlocking false
        }
        // Accept as the attendee, using the attendee copy's current etag for the
        // If-Match PUT (an empty etag would send If-Match: "" and 412).
        val attendeeEtag = ac.fetchEtag(attendeeEventUrl).getOrNull().orEmpty()
        ac.updateEvent(attendeeEventUrl, schedulingObjectIcs(uid, "ACCEPTED"), attendeeEtag)
        Thread.sleep(1000)
        val etagAfter = organizerClient.fetchEtag(organizerUrl).getOrNull()
        val drifted = etagBefore != null && etagAfter != null && etagBefore != etagAfter
        println("CYRUS DRIFT: organizer etag $etagBefore -> $etagAfter (drifted=$drifted)")
        drifted
    }

    @Test
    fun `delete of a drifted scheduling object recovers on Cyrus`() = runBlocking {
        assumeReady()
        assumeTrue("Not Cyrus", config.name == "Cyrus")
        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "cyrus-drift-del-${System.currentTimeMillis()}-${UUID.randomUUID()}"

        // 1. Organizer creates the scheduling object on the server.
        val createResult = client!!.createEvent(calendarUrl!!, uid, schedulingObjectIcs(uid, "NEEDS-ACTION"))
        assumeTrue("create failed: ${(createResult as? CalDavResult.Error)?.message}", createResult.isSuccess())
        val (url, createEtag) = createResult.getOrNull()!!
        trackEvent(url, createEtag)

        // 2. Pull it into Room (captures caldavUrl + the pre-drift etag).
        val calendar = localCalendarFor(calendarUrl)
        pullStrategy.pull(calendar, forceFullSync = true, client = client!!)
        val pulled = database.eventsDao().getByUid(uid).firstOrNull()
        assumeTrue("event did not pull into Room", pulled != null)

        // 3. Drift the organizer copy's etag via an attendee accept.
        val drifted = triggerDriftViaAttendeeAccept(uid, url)
        assumeTrue("drift did not occur on this container (delivery rights) — skipping", drifted)

        // 4. Queue a delete against the STALE pulled etag and drain it.
        eventWriter.deleteEvent(pulled!!.id, isLocal = false)
        val pushResult = pushStrategy.pushForCalendar(calendar, client!!)

        // 5. The delete must recover: no 412 warning, one event deleted, and the
        //    resource is actually gone from the server.
        assertTrue("push must succeed, got $pushResult", pushResult is PushResult.Success)
        val success = pushResult as PushResult.Success
        assertFalse(
            "delete hit a 412 (frozen link) instead of recovering: ${success.pushWarnings}",
            success.pushWarnings.any { it.contains("412") }
        )
        assertTrue("expected the delete to be pushed, got $success", success.eventsDeleted >= 1)
        val gone = client!!.fetchEvent(url)
        assertTrue("resource must be gone from the server after recovered delete, got $gone", gone.isNotFound())
        println("CYRUS: delete of drifted scheduling object recovered end-to-end")
    }

    @Test
    fun `edit of a drifted scheduling object recovers on Cyrus`() = runBlocking {
        assumeReady()
        assumeTrue("Not Cyrus", config.name == "Cyrus")
        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "cyrus-drift-edit-${System.currentTimeMillis()}-${UUID.randomUUID()}"

        val createResult = client!!.createEvent(calendarUrl!!, uid, schedulingObjectIcs(uid, "NEEDS-ACTION"))
        assumeTrue("create failed: ${(createResult as? CalDavResult.Error)?.message}", createResult.isSuccess())
        val (url, createEtag) = createResult.getOrNull()!!
        trackEvent(url, createEtag)

        val calendar = localCalendarFor(calendarUrl)
        pullStrategy.pull(calendar, forceFullSync = true, client = client!!)
        val pulled = database.eventsDao().getByUid(uid).firstOrNull()
        assumeTrue("event did not pull into Room", pulled != null)

        val drifted = triggerDriftViaAttendeeAccept(uid, url)
        assumeTrue("drift did not occur on this container (delivery rights) — skipping", drifted)

        val newTitle = "Cyrus drift (edited after reply)"
        eventWriter.updateEvent(pulled!!.copy(title = newTitle), isLocal = false)
        val pushResult = pushStrategy.pushForCalendar(calendar, client!!)

        assertTrue("push must succeed, got $pushResult", pushResult is PushResult.Success)
        val success = pushResult as PushResult.Success
        assertFalse(
            "edit hit a 412 (frozen link) instead of recovering: ${success.pushWarnings}",
            success.pushWarnings.any { it.contains("412") }
        )
        assertTrue("expected the edit to be pushed, got $success", success.eventsUpdated >= 1)
        val stored = client!!.fetchEvent(url).getOrNull()!!.icalData
        assertTrue(
            "edit must round-trip to the server after drift; body: ${FixtureRedactor.redact(stored)}",
            stored.replace(Regex("""\r?\n[ \t]"""), "").contains(newTitle)
        )
        client!!.fetchEtag(url).getOrNull()?.let { trackEvent(url, it) }
        println("CYRUS: edit of drifted scheduling object recovered end-to-end")
    }
}
