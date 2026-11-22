package org.onekash.kashcal.sync.strategy

import android.content.Context
import org.onekash.kashcal.data.db.EventIcsStore
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A user deletes a just-moved event while a push that has already read its MOVE is still on an
 * earlier operation. `EventWriter.deleteEvent` turns the MOVE (still PENDING in the database) into
 * a DELETE of the original; when the push then runs its in-memory MOVE, the server copy has a new
 * URL, so the push points that DELETE at it instead of writing the MOVE back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PushStrategyMoveWriteBackTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var pushStrategy: PushStrategy
    private val client = mockk<CalDavClient>()
    private lateinit var home: Calendar
    private lateinit var work: Calendar

    @Before
    fun setUp() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context)
            .addCallback(PendingOperationTriggers.callback())
            .allowMainThreadQueries()
            .build()
        eventWriter = EventWriter(
            database,
            OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        )
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.ICLOUD, email = "owner@example.test"))
        home = database.calendarsDao().getById(
            database.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = "https://dav.example.test/home/", displayName = "Home", color = 0))
        )!!
        work = database.calendarsDao().getById(
            database.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = "https://dav.example.test/work/", displayName = "Work", color = 0))
        )!!
        val calendarRepository = CalendarRepositoryImpl(database.calendarsDao())
        val accountRepository = mockk<AccountRepository>()
        coEvery { accountRepository.getAccountById(any()) } returns database.accountsDao().getById(accountId)
        pushStrategy = PushStrategy(
            calendarRepository = calendarRepository,
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = accountRepository,
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao(),
            icsSource = EventIcsStore(database),
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun insertSynced(uid: String, calendar: Calendar): Event {
        val now = System.currentTimeMillis()
        val id = database.eventsDao().insert(
            Event(
                uid = uid, calendarId = calendar.id, title = uid, startTs = now, endTs = now + 3_600_000,
                dtstamp = now, caldavUrl = "${calendar.caldavUrl}$uid.ics", etag = "\"1\"", syncStatus = SyncStatus.SYNCED,
            )
        )
        return database.eventsDao().getById(id)!!
    }

    /**
     * Queues a delete of an unrelated event in [calendar] first, so the push reaches it before the
     * MOVE; its server DELETE is where the user's delete of `movedEventId` lands.
     */
    private suspend fun earlierDeleteThatTriggersUserDelete(calendar: Calendar, movedEventId: Long) {
        val other = insertSynced("other", calendar)
        eventWriter.deleteEvent(other.id)
        val op = database.pendingOperationsDao().getForEvent(other.id).single()
        database.pendingOperationsDao().update(op.copy(createdAt = 0L))
        coEvery { client.deleteEvent(other.caldavUrl!!, any()) } coAnswers {
            eventWriter.deleteEvent(movedEventId)
            CalDavResult.Success(Unit)
        }
    }

    @Test
    fun `a WebDAV MOVE that lands after the user's delete retargets the DELETE at the moved copy`() = runTest {
        val event = insertSynced("moved", home)
        eventWriter.moveEventToCalendar(event.id, work.id)
        val moveOpId = database.pendingOperationsDao().getForEvent(event.id).single().id
        earlierDeleteThatTriggersUserDelete(home, event.id)
        val movedUrl = "${work.caldavUrl}moved.ics"
        coEvery { client.moveEvent(event.caldavUrl!!, work.caldavUrl, event.uid) } returns CalDavResult.Success(movedUrl to "\"2\"")

        pushStrategy.pushForCalendar(home, client)

        val op = database.pendingOperationsDao().getById(moveOpId)!!
        assertEquals(PendingOperation.OPERATION_DELETE, op.operation)
        assertEquals(movedUrl, op.targetUrl)
        assertEquals(work.id, op.sourceCalendarId)
        assertEquals(PendingOperation.STATUS_PENDING, op.status)
    }

    @Test
    fun `a CREATE-then-DELETE move that lands after the user's delete retargets the DELETE at the copy`() = runTest {
        val event = insertSynced("moved", home)
        eventWriter.moveEventToCalendar(event.id, work.id)
        val dao = database.pendingOperationsDao()
        val move = dao.getForEvent(event.id).single()
        dao.update(move.copy(movePhase = PendingOperation.MOVE_PHASE_CREATE))
        earlierDeleteThatTriggersUserDelete(work, event.id)
        val createdUrl = "${work.caldavUrl}moved.ics"
        coEvery { client.createEvent(work.caldavUrl, event.uid, any()) } returns CalDavResult.Success(createdUrl to "\"2\"")
        coEvery { client.fetchEtag(event.caldavUrl!!) } returns CalDavResult.Success("\"1\"")
        coEvery { client.deleteEvent(event.caldavUrl!!, "\"1\"") } returns CalDavResult.Success(Unit)

        pushStrategy.pushForCalendar(work, client)

        val op = dao.getById(move.id)!!
        assertEquals(PendingOperation.OPERATION_DELETE, op.operation)
        assertEquals(createdUrl, op.targetUrl)
        assertEquals(work.id, op.sourceCalendarId)
        assertEquals(PendingOperation.STATUS_PENDING, op.status)
    }

    @Test
    fun `a CREATE-then-DELETE move that can't delete the original deletes both copies later`() = runTest {
        val event = insertSynced("moved", home)
        eventWriter.moveEventToCalendar(event.id, work.id)
        val dao = database.pendingOperationsDao()
        val move = dao.getForEvent(event.id).single()
        dao.update(move.copy(movePhase = PendingOperation.MOVE_PHASE_CREATE))
        earlierDeleteThatTriggersUserDelete(work, event.id)
        val createdUrl = "${work.caldavUrl}moved.ics"
        coEvery { client.createEvent(work.caldavUrl, event.uid, any()) } returns CalDavResult.Success(createdUrl to "\"2\"")
        coEvery { client.fetchEtag(event.caldavUrl!!) } returns CalDavResult.Success("\"1\"")
        coEvery { client.deleteEvent(event.caldavUrl!!, "\"1\"") } returns CalDavResult.error(503, "unavailable")

        pushStrategy.pushForCalendar(work, client)

        val left = dao.getAll().sortedBy { it.id }
        assertEquals(listOf(PendingOperation.OPERATION_DELETE, PendingOperation.OPERATION_DELETE), left.map { it.operation })
        assertEquals(listOf(event.caldavUrl, createdUrl), left.map { it.targetUrl })
        assertEquals(listOf(move.sourceCalendarId, work.id), left.map { it.sourceCalendarId })
        assertEquals(listOf(PendingOperation.STATUS_PENDING, PendingOperation.STATUS_PENDING), left.map { it.status })
    }

    @Test
    fun `a MOVE whose body PUT fails stays queued as an UPDATE of the copy`() = runTest {
        val event = insertSynced("moved", home)
        eventWriter.moveEventToCalendar(event.id, work.id)
        val moveOpId = database.pendingOperationsDao().getForEvent(event.id).single().id
        val movedUrl = "${work.caldavUrl}moved.ics"
        coEvery { client.moveEvent(event.caldavUrl!!, work.caldavUrl, event.uid) } returns CalDavResult.Success(movedUrl to "\"2\"")
        coEvery { client.updateEvent(movedUrl, any(), any()) } returns CalDavResult.error(500, "server error", isRetryable = true)

        pushStrategy.pushForCalendar(home, client)

        val op = database.pendingOperationsDao().getById(moveOpId)!!
        assertEquals(PendingOperation.OPERATION_UPDATE, op.operation)
        assertEquals(null, op.targetUrl)
        assertEquals(null, op.targetCalendarId)
        assertEquals(PendingOperation.STATUS_PENDING, op.status)
        val row = database.eventsDao().getById(event.id)!!
        assertEquals(movedUrl, row.caldavUrl)
        assertEquals(SyncStatus.PENDING_UPDATE, row.syncStatus)
    }

    @Test
    fun `a MOVE that lands with nobody deleting pushes the current body to the copy`() = runTest {
        val event = insertSynced("moved", home)
        eventWriter.moveEventToCalendar(event.id, work.id)
        val movedUrl = "${work.caldavUrl}moved.ics"
        coEvery { client.moveEvent(event.caldavUrl!!, work.caldavUrl, event.uid) } returns CalDavResult.Success(movedUrl to "\"2\"")
        coEvery { client.updateEvent(movedUrl, any(), any()) } returns CalDavResult.Success("\"3\"")

        pushStrategy.pushForCalendar(home, client)

        assertEquals(emptyList<PendingOperation>(), database.pendingOperationsDao().getAll())
        val row = database.eventsDao().getById(event.id)!!
        assertEquals(movedUrl, row.caldavUrl)
        assertEquals(SyncStatus.SYNCED, row.syncStatus)
    }
}
