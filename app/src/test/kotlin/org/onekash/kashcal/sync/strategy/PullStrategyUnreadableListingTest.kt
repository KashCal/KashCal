package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.network.HostileDavServer
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.notification.InviteNotifier
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A sync answered with a web page (a hotspot login, a proxy error), a garbled
 * listing or a listing the server cut short never deletes local events: the pull
 * returns an error, not "the calendar is empty". Real pull, real client, real
 * database; only the server is hostile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PullStrategyUnreadableListingTest {

    private lateinit var database: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient
    private lateinit var pullStrategy: PullStrategy
    private val dataStore: KashCalDataStore = TestDataStoreFactory.createDefault()
    private val calendarRepository: CalendarRepository = mockk(relaxed = true)
    private val accountRepository: AccountRepository = mockk(relaxed = true)
    private val inviteNotifier: InviteNotifier = mockk(relaxed = true)
    private val reminderScheduler: ReminderScheduler = mockk(relaxed = true)

    private val account = Account(id = 1L, provider = AccountProvider.CALDAV, email = "me@example.test")
    private val tomorrow = System.currentTimeMillis() + 86_400_000L

    @Before
    fun setup() = runTest {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        client = OkHttpCalDavClientFactory().createClient(
            Credentials(username = "u", password = "p", serverUrl = base), DefaultQuirks(base)
        )
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries().build()
        database.accountsDao().insert(account)
        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = calendarRepository,
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), dataStore),
            defaultQuirks = DefaultQuirks(base),
            dataStore = dataStore,
            inviteNotifier = inviteNotifier,
            accountRepository = accountRepository,
            reminderScheduler = reminderScheduler
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    /** A synced calendar with two synced events the server holds. */
    private suspend fun calendarWithTwoEvents(syncToken: String?): Calendar {
        val calendar = Calendar(
            id = 5L, accountId = account.id, caldavUrl = server.url("/cal/").toString(),
            displayName = "Work", color = 0, syncToken = syncToken
        )
        database.calendarsDao().insert(calendar)
        for (name in listOf("a", "b")) {
            database.eventsDao().insert(
                Event(
                    uid = "uid-$name", calendarId = calendar.id, title = "Event $name",
                    startTs = tomorrow, endTs = tomorrow + 3_600_000L, timezone = "UTC", isAllDay = false,
                    status = "CONFIRMED", dtstamp = 1_000L, syncStatus = SyncStatus.SYNCED,
                    caldavUrl = server.url("/cal/$name.ics").toString(), etag = "etag-$name"
                )
            )
        }
        return calendar
    }

    private suspend fun localTitles(calendar: Calendar) =
        database.eventsDao().getByCalendarIdInRange(calendar.id, 0L, Long.MAX_VALUE).map { it.title }.sorted()

    /** Answer each request by what it asks for; [reply] picks the response. */
    private fun serve(reply: (RecordedRequest, String) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = reply(request, request.body.readUtf8())
        }
    }

    @Test
    fun `a full sync answered with a login page deletes nothing`() = runTest {
        val calendar = calendarWithTwoEvents(syncToken = null)
        serve { _, _ -> HostileDavServer.loginPage() }

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("an error, not a sync: $result", result is PullResult.Error)
        assertEquals(listOf("Event a", "Event b"), localTitles(calendar))
    }

    @Test
    fun `a full sync answered with a garbled listing deletes nothing`() = runTest {
        val calendar = calendarWithTwoEvents(syncToken = null)
        serve { _, _ -> HostileDavServer.garbledMultistatus() }

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("an error, not a sync: $result", result is PullResult.Error)
        assertEquals(listOf("Event a", "Event b"), localTitles(calendar))
    }

    @Test
    fun `an expired sync token followed by a login page deletes nothing`() = runTest {
        // The etag-fallback path: the token is refused (403), so the pull lists etags
        // with a calendar-query and, when that fails, runs a full pull. The hotspot
        // answers both with its page.
        val calendar = calendarWithTwoEvents(syncToken = "token-1")
        serve { _, body ->
            if (body.contains("sync-collection")) MockResponse().setResponseCode(403)
            else HostileDavServer.loginPage()
        }

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("an error, not a sync: $result", result is PullResult.Error)
        assertEquals(listOf("Event a", "Event b"), localTitles(calendar))
    }

    @Test
    fun `a listing the server cut short never removes the events past the cut`() = runTest {
        val calendar = calendarWithTwoEvents(syncToken = null)
        val onlyA = HostileDavServer.member("/cal/a.ics", "etag-a")
        serve { _, body ->
            when {
                body.contains("getctag") -> HostileDavServer.multistatus("")
                body.contains("sync-token") -> HostileDavServer.multistatus("")
                // The listing holds "a" and a 507 marker: "b" was cut off, not deleted.
                else -> HostileDavServer.truncatedListing("/cal/", onlyA)
            }
        }

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("an error, not a sync: $result", result is PullResult.Error)
        assertEquals(listOf("Event a", "Event b"), localTitles(calendar))
    }
}
