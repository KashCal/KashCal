package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
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
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Tests that an edit the server answers with a redirect reaches the new location, and the
 * event's stored URL follows it, so the next edit goes straight there. Drives the real push
 * over the real client (as the factory builds it) and a real database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyRedirectedWriteTest {

    private lateinit var database: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient
    private lateinit var pushStrategy: PushStrategy
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var eventId = 0L

    private val ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:uid-redirect@kashcal.onekash.org
        DTSTAMP:20260101T100000Z
        DTSTART:20261225T140000Z
        DTEND:20261225T150000Z
        SUMMARY:Edited
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

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
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "me@example.test"))
        val calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = server.url("/cal/").toString(), displayName = "Cal", color = -1)
        )
        eventId = database.eventsDao().insert(
            Event(
                uid = "uid-redirect@kashcal.onekash.org", calendarId = calendarId, title = "Edited",
                startTs = 1_798_207_200_000L, endTs = 1_798_210_800_000L, timezone = "UTC", isAllDay = false,
                status = "CONFIRMED", dtstamp = 1_000L, sequence = 0, syncStatus = SyncStatus.PENDING_UPDATE,
                caldavUrl = server.url("/cal/uid-redirect@kashcal.onekash.org.ics").toString(), etag = "e1"
            )
        )
        val calendarRepository = mockk<CalendarRepository>()
        val accountRepository = mockk<AccountRepository>()
        coEvery { calendarRepository.getCalendarById(calendarId) } returns database.calendarsDao().getById(calendarId)
        coEvery { calendarRepository.getCalendarsByIds(any()) } returns listOf(database.calendarsDao().getById(calendarId)!!)
        coEvery { accountRepository.getAccountById(any()) } returns database.accountsDao().getById(accountId)
        pushStrategy = PushStrategy(
            calendarRepository = calendarRepository,
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = accountRepository,
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao()
        )
        database.pendingOperationsDao().insert(
            PendingOperation(eventId = eventId, operation = PendingOperation.OPERATION_UPDATE, status = PendingOperation.STATUS_PENDING)
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    /** Serves [routes] by "METHOD path"; anything else is a 404. */
    private fun serve(routes: Map<String, () -> MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.path}"
                seen += key
                return routes[key]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun `an edit the server redirects lands at the new URL and the event remembers it`() = runTest {
        serve(
            mapOf(
                "PUT /cal/uid-redirect@kashcal.onekash.org.ics" to {
                    MockResponse().setResponseCode(301).setHeader("Location", "/moved/uid-redirect.ics")
                },
                "PUT /moved/uid-redirect.ics" to { MockResponse().setResponseCode(204).setHeader("ETag", "\"e2\"") },
                "GET /moved/uid-redirect.ics" to { MockResponse().setResponseCode(200).setHeader("ETag", "\"e2\"").setBody(ics) },
            )
        )

        pushStrategy.pushAll(client)

        val event = database.eventsDao().getById(eventId)!!
        assertEquals(server.url("/moved/uid-redirect.ics").toString(), event.caldavUrl)
        assertEquals("e2", event.etag)
        assertEquals(SyncStatus.SYNCED, event.syncStatus)
        assertTrue("the edit reached the new location as a PUT: $seen", "PUT /moved/uid-redirect.ics" in seen)
        assertTrue("never turned into a GET of the old URL: $seen", "GET /cal/uid-redirect@kashcal.onekash.org.ics" !in seen)
    }

    @Test
    fun `an edit retried after a conflict and then redirected also remembers the new URL`() = runTest {
        var puts = 0
        serve(
            mapOf(
                "PUT /cal/uid-redirect@kashcal.onekash.org.ics" to {
                    // First the stale etag conflicts; the retry, built on the server's copy, is
                    // redirected.
                    if (puts++ == 0) MockResponse().setResponseCode(412)
                    else MockResponse().setResponseCode(307).setHeader("Location", "/moved/uid-redirect.ics")
                },
                "GET /cal/uid-redirect@kashcal.onekash.org.ics" to {
                    MockResponse().setResponseCode(200).setHeader("ETag", "\"e9\"").setBody(ics)
                },
                "PUT /moved/uid-redirect.ics" to { MockResponse().setResponseCode(204).setHeader("ETag", "\"e10\"") },
                "GET /moved/uid-redirect.ics" to { MockResponse().setResponseCode(200).setHeader("ETag", "\"e10\"").setBody(ics) },
            )
        )

        val result = pushStrategy.pushAll(client) as PushResult.Success

        val event = database.eventsDao().getById(eventId)!!
        assertEquals(server.url("/moved/uid-redirect.ics").toString(), event.caldavUrl)
        // A merged retry keeps the old etag so this cycle's pull fetches the merged copy.
        assertEquals("e1", event.etag)
        assertTrue(eventId in result.refetchEventIds)
    }

    @Test
    fun `an edit the server takes where it was sent keeps the stored URL exactly as it was`() = runTest {
        val stored = database.eventsDao().getById(eventId)!!.caldavUrl
        serve(
            mapOf(
                "PUT /cal/uid-redirect@kashcal.onekash.org.ics" to { MockResponse().setResponseCode(204).setHeader("ETag", "\"e2\"") },
                "GET /cal/uid-redirect@kashcal.onekash.org.ics" to { MockResponse().setResponseCode(200).setHeader("ETag", "\"e2\"").setBody(ics) },
            )
        )

        pushStrategy.pushAll(client)

        val event = database.eventsDao().getById(eventId)!!
        assertEquals(stored, event.caldavUrl)
        assertEquals("e2", event.etag)
    }
}
