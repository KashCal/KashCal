package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.EventIcsStore
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that resolving a conflict with the server's version stores the server's body as the
 * resource's ICS ([EventIcsStore]) with the etag that goes with it, even when the body is past
 * the 2 MB cursor window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ConflictResolverEventIcsTest {

    private lateinit var database: KashCalDatabase
    private lateinit var store: EventIcsStore
    private lateinit var resolver: ConflictResolver
    private val client: CalDavClient = mockk()
    private var calendarId = 0L
    private val url = "https://caldav.example.test/cal/s.ics"

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        store = EventIcsStore(database)
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "a@example.test"))
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "https://caldav.example.test/cal/", displayName = "C", color = 0)
        )
        resolver = ConflictResolver(
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            occurrenceGenerator = OccurrenceGenerator(
                database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()
            ),
            database = database,
            icsSource = store,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `taking the server's version stores its body with its etag`() = runTest {
        val id = database.eventsDao().insert(
            Event(
                uid = "uid-c", calendarId = calendarId, title = "Local", startTs = 2_000_000_000_000,
                endTs = 2_000_003_600_000, dtstamp = 0, caldavUrl = url, etag = "etag-1",
                syncStatus = SyncStatus.PENDING_UPDATE,
            )
        )
        store.write(id, "OLD BODY")
        val opId = database.pendingOperationsDao().insert(
            PendingOperation(eventId = id, operation = PendingOperation.OPERATION_UPDATE)
        )
        val big = "X-BIG-ATTACHMENT:" + "A".repeat(2_500_000)
        val server = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:uid-c\r\n" +
            "DTSTAMP:20330518T033320Z\r\nDTSTART:20330518T033320Z\r\nDTEND:20330518T043320Z\r\n" +
            "SUMMARY:Server\r\n" + big.chunked(74).joinToString("\r\n ") + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        coEvery { client.fetchEvent(url) } returns CalDavResult.success(CalDavEvent("s.ics", url, "etag-9", server))

        val result = resolver.resolve(database.pendingOperationsDao().getById(opId)!!, client = client)

        assertTrue("resolved: $result", result is ConflictResult.ServerVersionKept)
        val row = database.eventsDao().getById(id)!!
        assertEquals("Server", row.title)
        assertEquals("etag-9", row.etag)
        assertEquals(server, store.read(row))
    }
}
