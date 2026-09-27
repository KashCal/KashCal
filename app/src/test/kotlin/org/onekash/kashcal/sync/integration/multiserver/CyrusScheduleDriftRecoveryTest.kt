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
 * Proves end to end, against a real Cyrus (the CalDAV engine Fastmail runs), that the client
 * recovers from the server-driven ETag drift behind #311.
 *
 * The drift needs a scheduling object (ORGANIZER present): after an attendee reply is
 * auto-processed, Cyrus rewrites the organizer's copy and the ETag changes while the
 * Schedule-Tag doesn't (RFC 6638 §3.2.10). A later edit or delete sent with the pulled, now
 * stale, ETag then gets a 412. A test without an attendee reply never drifts, so it never
 * reaches the 412 recovery.
 *
 * The chain under test:
 *   organizer PUT (scheduling object) -> pull into Room
 *     -> a second user accepts (the server drifts the organizer ETag)
 *     -> queue an edit or a delete against the stale pulled ETag
 *     -> [PushStrategy.pushForCalendar] drains it
 *     -> assert no 412 warning and that the server shows the outcome, and for the edit that
 *        the attendee's ACCEPTED is still there
 *
 * A 412 that reached the caller shows up as a push warning containing "412" (the frozen-link
 * symptom); recovery means one retry: an edit built on the server's current copy, a delete sent
 * with the refetched ETag. Cyrus only (it needs the scheduling pipeline and a second local user);
 * skipped when the credentials are missing or the server is unreachable, no calendar is found, the
 * create fails, the event doesn't pull into Room, or the accept doesn't drift the ETag, as when the
 * invite never reaches the second user: the test image's scheduling delivery can refuse the store
 * with a 403.
 *
 * The last test reaches the same end state without the scheduling pipeline: a direct write
 * standing in for another client puts the attendee's ACCEPTED into the organizer's copy, which
 * moves the strong ETag, and the edit queued against the pulled ETag must keep that answer. It
 * skips on the same assumptions, with a failed direct write in place of the drift.
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

    // Three weeks ahead so strict servers don't reject an event in the past.
    private val dayMs = 86_400_000L
    private val startMs = ((System.currentTimeMillis() / dayMs) + 21) * dayMs + 9 * 3_600_000L
    private val icsUtc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // user2 is the second local Cyrus user, whose accept drifts the organizer's copy. The test
    // image seeds user1..user5 and accepts any password.
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

    // A scheduling object: ORGANIZER user1 with user2 invited. Only this kind of event drifts;
    // a plain event never enters the scheduling pipeline.
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
     * Returns a [CalDavClient] authenticated as [attendeeUser], for accepting the invitation.
     * Built as [CalDavTestServerLoader.createClient] builds the organizer's, with the
     * attendee's username.
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
     * Has [attendeeUser] accept the invitation, which makes Cyrus update the organizer's copy
     * and drift its ETag. Returns true only when the organizer ETag read before and after
     * the accept are both present and differ. Returns false when the attendee's principal,
     * home or calendar isn't found or the invite wasn't delivered (the container may lack
     * delivery rights); the caller then skips instead of asserting on a drift that never
     * happened.
     */
    private fun triggerDriftViaAttendeeAccept(uid: String, organizerUrl: String): Boolean = runBlocking {
        val organizerClient = client!!
        val etagBefore = organizerClient.fetchEtag(organizerUrl).getOrNull()

        // Find the delivered copy of this UID in the attendee's calendar. The server picks the
        // invite's filename, so match by body.
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

        // fetchEvent needs an absolute URL, so a relative href (e.g. "/dav/calendars/...") is
        // resolved against the server origin.
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
        // Accept as the attendee with the attendee copy's current etag as If-Match; an empty
        // etag would send If-Match: "" and get a 412.
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

        // 4. Queue a delete against the stale pulled etag and drain it.
        eventWriter.deleteEvent(pulled!!.id, isLocal = false)
        val pushResult = pushStrategy.pushForCalendar(calendar, client!!)

        // 5. The delete must recover: no 412 warning, at least one event deleted, and the
        //    resource gone from the server.
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
        // The drift was the attendee's answer; the recovered edit must not send it back.
        val attendeeLine = stored.replace(Regex("""\r?\n[ \t]"""), "").lines()
            .first { it.startsWith("ATTENDEE") && it.contains(attendeeMailto, ignoreCase = true) }
        assertTrue(
            "the attendee's ACCEPTED must survive the edit; line: ${FixtureRedactor.redact(attendeeLine)}",
            attendeeLine.contains("PARTSTAT=ACCEPTED", ignoreCase = true)
        )
        client!!.fetchEtag(url).getOrNull()?.let { trackEvent(url, it) }
        println("CYRUS: edit of drifted scheduling object recovered end-to-end")
    }

    @Test
    fun `edit after the attendee's answer reached the organizer's copy keeps the answer on Cyrus`() = runBlocking<Unit> {
        // The same end state as the drift above, written directly as another client would instead
        // of by Cyrus's scheduling pipeline, which the test image can't always deliver through: the
        // organizer's copy gains the attendee's ACCEPTED and a new strong etag, and the edit
        // queued against the pulled etag meets a 412.
        assumeReady()
        assumeTrue("Not Cyrus", config.name == "Cyrus")
        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "cyrus-answer-edit-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val createResult = client!!.createEvent(calendarUrl!!, uid, schedulingObjectIcs(uid, "NEEDS-ACTION"))
        assumeTrue("create failed: ${(createResult as? CalDavResult.Error)?.message}", createResult.isSuccess())
        val (url, createEtag) = createResult.getOrNull()!!
        trackEvent(url, createEtag)

        val calendar = localCalendarFor(calendarUrl)
        pullStrategy.pull(calendar, forceFullSync = true, client = client!!)
        val pulled = database.eventsDao().getByUid(uid).firstOrNull()
        assumeTrue("event did not pull into Room", pulled != null)

        val current = client!!.fetchEvent(url).getOrNull()!!
        val answered = current.icalData.replace(Regex("""\r?\n[ \t]"""), "").lines().joinToString("\r\n") { line ->
            if (line.startsWith("ATTENDEE") && line.contains(attendeeMailto, ignoreCase = true)) {
                line.replace(Regex("PARTSTAT=[A-Z-]+", RegexOption.IGNORE_CASE), "PARTSTAT=ACCEPTED")
            } else line
        }
        val written = client!!.updateEvent(url, answered, current.etag ?: client!!.fetchEtag(url).getOrNull().orEmpty())
        assumeTrue("the answer could not be written: $written", written.isSuccess())
        trackEvent(url, written.getOrNull()!!)

        val newTitle = "Cyrus answered (edited after)"
        eventWriter.updateEvent(pulled!!.copy(title = newTitle), isLocal = false)
        val pushResult = pushStrategy.pushForCalendar(calendar, client!!)

        assertTrue("push must succeed, got $pushResult", pushResult is PushResult.Success)
        val success = pushResult as PushResult.Success
        assertFalse("edit hit a 412 instead of recovering: ${success.pushWarnings}", success.pushWarnings.any { it.contains("412") })
        val stored = client!!.fetchEvent(url).getOrNull()!!.icalData.replace(Regex("""\r?\n[ \t]"""), "")
        assertTrue("the edit must reach the server; body: ${FixtureRedactor.redact(stored)}", stored.contains(newTitle))
        val attendeeLine = stored.lines().first { it.startsWith("ATTENDEE") && it.contains(attendeeMailto, ignoreCase = true) }
        assertTrue(
            "the attendee's ACCEPTED must survive the edit; line: ${FixtureRedactor.redact(attendeeLine)}",
            attendeeLine.contains("PARTSTAT=ACCEPTED", ignoreCase = true)
        )
        client!!.fetchEtag(url).getOrNull()?.let { trackEvent(url, it) }
    }

}
