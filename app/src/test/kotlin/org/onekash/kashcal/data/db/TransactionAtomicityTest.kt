package org.onekash.kashcal.data.db

import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import androidx.room3.RoomDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that a write made through [KashCalDatabase.runInTransaction] is all or nothing: an
 * event row and the pending operation queued with it are written together or not at all, and a
 * failure in a nested transaction that reaches the outer one undoes the outer writes too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class TransactionAtomicityTest {

    private lateinit var database: KashCalDatabase
    private var calendarId = 0L

    @Before
    fun setup() = runBlocking {
        database = TestDatabases.inMemory(ApplicationProvider.getApplicationContext())
            .addCallback(KashCalDatabase.testCallback())
            .allowMainThreadQueries()
            .build()
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local"))
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "local://atomicity", displayName = "A", color = 0)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun event(uid: String) = Event(
        uid = uid, calendarId = calendarId, title = "T-$uid", startTs = 1_000_000L, endTs = 2_000_000L, dtstamp = 0L
    )

    private suspend fun eventCount() = database.eventsDao().getAllMasterEventsForCalendar(calendarId).size
    private suspend fun operationCount() = database.pendingOperationsDao().getAll().size

    @Test
    fun `a failure inside runInTransaction leaves neither the event nor its operation`(): Unit = runBlocking {
        try {
            database.runInTransaction {
                val id = database.eventsDao().insert(event("a"))
                database.pendingOperationsDao().insert(
                    PendingOperation(eventId = id, operation = PendingOperation.OPERATION_CREATE)
                )
                throw DeliberateFailure()
            }
            fail("expected the failure to reach the caller")
        } catch (_: DeliberateFailure) {
        }
        assertEquals(0, eventCount())
        assertEquals(0, operationCount())
    }

    @Test
    fun `a nested failure that reaches the outer transaction undoes the outer writes`(): Unit = runBlocking {
        try {
            database.runInTransaction {
                database.eventsDao().insert(event("outer"))
                database.runInTransaction {
                    database.eventsDao().insert(event("inner"))
                    throw DeliberateFailure()
                }
            }
            fail("expected the failure to reach the caller")
        } catch (_: DeliberateFailure) {
        }
        assertEquals(0, eventCount())
    }

    @Test
    fun `nested writes that succeed commit with the outer transaction`(): Unit = runBlocking {
        database.runInTransaction {
            database.eventsDao().insert(event("outer"))
            database.runInTransaction { database.eventsDao().insert(event("inner")) }
        }
        assertEquals(2, eventCount())
    }

    @Test
    fun `isInAppTransaction is true inside runInTransaction, also after a dispatcher switch`(): Unit = runBlocking {
        assertEquals(false, database.isInAppTransaction())
        val inside = database.runInTransaction {
            val direct = database.isInAppTransaction()
            val afterSwitch = withContext(Dispatchers.Default) { database.isInAppTransaction() }
            direct to afterSwitch
        }
        assertEquals(true to true, inside)
        assertEquals(false, database.isInAppTransaction())
    }

    @Test
    fun `a cancelled caller leaves nothing of its transaction`(): Unit = runBlocking {
        val firstWritten = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            database.runInTransaction {
                database.eventsDao().insert(event("c1"))
                firstWritten.complete(Unit)
                gate.await()
                database.eventsDao().insert(event("c2"))
            }
        }
        firstWritten.await()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals(0, eventCount())
    }

    @Test
    fun `nested transactions don't hold up reads while the outer one is open`(): Unit = runBlocking {
        // A file database in WAL mode, as production opens it, so readers don't wait for the
        // writer; in memory there is one connection.
        val name = "atomicity-wal.db"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(name)
        val walDb = TestDatabases.file(context, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        try {
            val accountId = walDb.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local"))
            val cal = walDb.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "local://wal", displayName = "W", color = 0)
            )
            val nestedDone = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val writer = launch(Dispatchers.Default) {
                walDb.runInTransaction {
                    repeat(4) { i ->
                        walDb.runInTransaction { walDb.eventsDao().insert(event("n$i").copy(calendarId = cal)) }
                    }
                    nestedDone.complete(Unit)
                    gate.await()
                }
            }
            nestedDone.await()
            val readFinished = withTimeoutOrNull(3_000) {
                walDb.calendarsDao().getById(cal)
                true
            }
            gate.complete(Unit)
            writer.join()
            assertEquals("a read waited for the open transaction", true, readFinished)
        } finally {
            walDb.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `an event write whose occurrence step fails leaves no event and no queued operation`(): Unit = runBlocking {
        val generator = mockk<OccurrenceGenerator>()
        coEvery { generator.generateOccurrences(any(), any(), any()) } throws DeliberateFailure()
        val writer = EventWriter(database, generator)
        try {
            writer.createEvent(event("w"), isLocal = false)
            fail("expected the failure to reach the caller")
        } catch (_: DeliberateFailure) {
        }
        assertEquals(0, eventCount())
        assertEquals(0, operationCount())
    }

    @Test
    fun `an event write that succeeds stores the event and queues its operation together`(): Unit = runBlocking {
        val generator = mockk<OccurrenceGenerator>()
        coEvery { generator.generateOccurrences(any(), any(), any()) } returns 1
        val created = EventWriter(database, generator).createEvent(event("ok"), isLocal = false)
        assertEquals(1, eventCount())
        assertTrue(database.pendingOperationsDao().getAll().any { it.eventId == created.id })
    }

    /**
     * Marks the failure each test throws on purpose. A test catches only this, so an unrelated
     * error (Room refusing the call, for example) fails the test instead of passing as the
     * expected failure.
     */
    private class DeliberateFailure : RuntimeException("deliberate test failure")
}
