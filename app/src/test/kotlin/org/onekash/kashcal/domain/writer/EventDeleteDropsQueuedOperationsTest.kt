package org.onekash.kashcal.domain.writer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.adapter.ContactSystemAccountRegistrar
import org.onekash.kashcal.sync.contacts.FakeContactsProviderRepository
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deleting an event row drops the queued operations that need it, through the trigger in
 * [PendingOperationTriggers]; these tests run the main routes that delete rows. A self-contained
 * DELETE (server URL and source calendar captured at queue time) outlives its row, and a
 * cross-account move's CREATE and linked DELETE go together so the original is never deleted
 * without its copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventDeleteDropsQueuedOperationsTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private var accountId = 0L
    private var calendarId = 0L

    @Before
    fun setUp() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context)
            .addCallback(PendingOperationTriggers.callback())
            .allowMainThreadQueries()
            .build()
        val generator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()
        )
        eventWriter = EventWriter(database, generator)
        accountId = database.accountsDao().insert(Account(provider = AccountProvider.ICLOUD, email = "owner@example.test"))
        calendarId = insertCalendar(accountId, "https://caldav.example.test/home/")
    }

    @After
    fun tearDown() = database.close()

    private suspend fun insertCalendar(accountId: Long, url: String) =
        database.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = url, displayName = "Cal", color = 0))

    private suspend fun insertSynced(
        uid: String,
        calendar: Long = calendarId,
        originalEventId: Long? = null,
    ): Event {
        val now = System.currentTimeMillis()
        val id = database.eventsDao().insert(
            Event(
                uid = uid,
                calendarId = calendar,
                title = "Synced",
                startTs = now,
                endTs = now + 3_600_000,
                dtstamp = now,
                caldavUrl = "https://caldav.example.test/home/$uid.ics",
                etag = "\"1\"",
                syncStatus = SyncStatus.SYNCED,
                originalEventId = originalEventId,
                originalInstanceTime = originalEventId?.let { now },
            )
        )
        return database.eventsDao().getById(id)!!
    }

    private suspend fun queueRsvp(event: Event) {
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = event.id,
                operation = PendingOperation.OPERATION_UPDATE,
                partstatOnly = true,
                partstatTarget = "ACCEPTED",
                targetUrl = event.caldavUrl,
            )
        )
    }

    private suspend fun ops() = database.pendingOperationsDao().getAll()

    private suspend fun orphanedOps() = ops().filter { database.eventsDao().getById(it.eventId) == null }

    // ==================== Fresh installs ====================

    @Test
    fun `the trigger callback and the app's create callback both install the trigger`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertTrue(TestDatabases.triggerExists(database, PendingOperationTriggers.EVENT_DELETE_TRIGGER))
        val withAppCallback = TestDatabases.inMemory(context)
            .addCallback(KashCalDatabase.testCallback())
            .allowMainThreadQueries()
            .build()
        try {
            assertTrue(TestDatabases.triggerExists(withAppCallback, PendingOperationTriggers.EVENT_DELETE_TRIGGER))
        } finally {
            withAppCallback.close()
        }
    }

    // ==================== Routes that delete event rows ====================

    @Test
    fun `deleting a never-synced event leaves no queued operation`() = runTest {
        val now = System.currentTimeMillis()
        val created = eventWriter.createEvent(
            Event(uid = "", calendarId = calendarId, title = "Offline", startTs = now, endTs = now + 3_600_000, dtstamp = now)
        )
        eventWriter.updateEvent(created.copy(title = "Offline, edited"))

        eventWriter.deleteEvent(created.id)

        assertEquals(emptyList<PendingOperation>(), ops())
    }

    @Test
    fun `removing a calendar leaves no queued operation for its events`() = runTest {
        val event = insertSynced(uid = "in-removed-calendar")
        eventWriter.updateEvent(event.copy(title = "Edited before the calendar went away"))

        CalendarRepositoryImpl(database.calendarsDao()).deleteCalendar(calendarId)

        assertEquals(emptyList<PendingOperation>(), ops())
    }

    @Test
    fun `removing an account leaves no queued operation, including exceptions and pending deletes`() = runTest {
        val master = insertSynced(uid = "series")
        val exception = insertSynced(uid = "series", originalEventId = master.id)
        queueRsvp(exception)
        val deleted = insertSynced(uid = "deleted")
        eventWriter.deleteEvent(deleted.id)
        eventWriter.updateEvent(master.copy(title = "Edited"))
        val credentialManager = mockk<CredentialManager>(relaxed = true)
        coEvery { credentialManager.hasCredentials(any()) } returns false
        val repository = AccountRepositoryImpl(
            accountsDao = database.accountsDao(),
            addressBookDao = database.addressBookDao(),
            calendarsDao = database.calendarsDao(),
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            credentialManager = credentialManager,
            reminderScheduler = mockk<ReminderScheduler>(relaxed = true),
            workManager = mockk<WorkManager>(relaxed = true),
            contactSystemAccountRegistrar = mockk<ContactSystemAccountRegistrar>(relaxed = true),
            contactsProviderRepository = FakeContactsProviderRepository(),
        )

        repository.deleteAccount(accountId)

        assertEquals(emptyList<PendingOperation>(), ops())
    }

    @Test
    fun `deleting a series master drops its changed occurrences' queued operations`() = runTest {
        val master = insertSynced(uid = "series")
        val exception = insertSynced(uid = "series", originalEventId = master.id)
        queueRsvp(exception)

        database.eventsDao().deleteById(master.id)

        assertEquals(emptyList<PendingOperation>(), ops())
    }

    @Test
    fun `a server deletion of a synced event drops its queued RSVP`() = runTest {
        val event = insertSynced(uid = "server-deleted")
        queueRsvp(event)

        // Pull's route for a server-side deletion of a row with no pending changes.
        database.eventsDao().deleteById(event.id)

        assertEquals(emptyList<PendingOperation>(), ops())
    }

    @Test
    fun `the lookback cleanup drops queued RSVPs of the events it removes`() = runTest {
        val event = insertSynced(uid = "old")
        queueRsvp(event)

        eventWriter.cleanupEventsOutsideLookback(event.endTs + 1)

        assertEquals(emptyList<PendingOperation>(), ops())
    }

    // ==================== Operations that outlive their row ====================

    @Test
    fun `a move DELETE to a local calendar survives the local row and stays ready`() = runTest {
        val event = insertSynced(uid = "to-local")
        val localAccount = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local"))
        val local = insertCalendar(localAccount, "local://default")
        eventWriter.moveEventToCalendar(event.id, local)

        eventWriter.deleteEvent(event.id, isLocal = true)

        val left = ops()
        assertEquals(1, left.size)
        assertEquals(PendingOperation.OPERATION_DELETE, left.single().operation)
        assertEquals(event.caldavUrl, left.single().targetUrl)
        assertEquals(left, database.pendingOperationsDao().getReadyOperations(Long.MAX_VALUE))
    }

    @Test
    fun `a DELETE folded into an RSVP has no source calendar and goes with its row`() = runTest {
        val event = insertSynced(uid = "rsvp-then-delete")
        queueRsvp(event)
        eventWriter.deleteEvent(event.id)
        assertEquals(listOf(PendingOperation.OPERATION_DELETE), ops().map { it.operation })

        CalendarRepositoryImpl(database.calendarsDao()).deleteCalendar(calendarId)

        assertEquals(emptyList<PendingOperation>(), ops())
    }

    @Test
    fun `a cross-account move whose new row is removed drops the CREATE and its DELETE together`() = runTest {
        val event = insertSynced(uid = "cross")
        val otherAccount = database.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "other@example.test"))
        val target = insertCalendar(otherAccount, "https://dav.example.test/cal/")
        eventWriter.moveEventToCalendar(event.id, target)
        assertEquals(2, ops().size)

        // Not the user's delete: the target calendar going away removes the moved row.
        CalendarRepositoryImpl(database.calendarsDao()).deleteCalendar(target)

        assertEquals(emptyList<PendingOperation>(), ops())
        assertEquals(emptyList<PendingOperation>(), database.pendingOperationsDao().getReadyOperations(Long.MAX_VALUE))
    }

    // ==================== Writes that must not trip the trigger ====================

    @Test
    fun `an upsert of an existing event keeps its queued operations`() = runTest {
        val event = insertSynced(uid = "upserted")
        eventWriter.updateEvent(event.copy(title = "Edited"))
        val before = ops()

        database.eventsDao().upsert(database.eventsDao().getById(event.id)!!.copy(title = "Pulled"))

        assertEquals(before, ops())
    }

    @Test
    fun `a user delete of a synced event keeps exactly its DELETE`() = runTest {
        val event = insertSynced(uid = "control")
        eventWriter.updateEvent(event.copy(title = "Edited"))

        eventWriter.deleteEvent(event.id)

        assertEquals(listOf(PendingOperation.OPERATION_DELETE), ops().map { it.operation })
        assertEquals(emptyList<PendingOperation>(), orphanedOps())
    }

    // ==================== Deleting right after a move ====================

    private suspend fun otherCalendar(provider: AccountProvider): Long {
        val owner = if (provider == AccountProvider.ICLOUD) accountId
        else database.accountsDao().insert(Account(provider = provider, email = "other@example.test"))
        val url = if (provider == AccountProvider.LOCAL) "local://default" else "https://dav.example.test/${provider.name.lowercase()}/"
        return insertCalendar(owner, url)
    }

    @Test
    fun `deleting right after a same-account move queues a DELETE of the original`() = runTest {
        val event = insertSynced(uid = "moved-same")
        val target = otherCalendar(AccountProvider.ICLOUD)
        eventWriter.moveEventToCalendar(event.id, target)
        val dao = database.pendingOperationsDao()
        val move = dao.getAll().single()
        // A MOVE that already failed a few times: the DELETE must start fresh.
        dao.update(move.copy(retryCount = 3, nextRetryAt = Long.MAX_VALUE, lastError = "503", failedAt = 1L, lifetimeResetAt = 1L))
        val before = System.currentTimeMillis()

        eventWriter.deleteEvent(event.id)

        val delete = ops().single()
        assertEquals(PendingOperation.OPERATION_DELETE, delete.operation)
        assertEquals(event.caldavUrl, delete.targetUrl)
        assertEquals(calendarId, delete.sourceCalendarId)
        assertEquals(PendingOperation.STATUS_PENDING, delete.status)
        assertEquals(0, delete.retryCount)
        assertEquals(0L, delete.nextRetryAt)
        assertEquals(null, delete.lastError)
        assertEquals(null, delete.failedAt)
        assertTrue(delete.lifetimeResetAt >= before)
        assertEquals(PendingOperation.MOVE_PHASE_DELETE, delete.movePhase)
        assertEquals(null, delete.targetCalendarId)
        assertEquals(false, delete.partstatOnly)
        assertEquals(null, delete.partstatTarget)
        assertEquals(listOf(delete), dao.getReadyOperations(Long.MAX_VALUE))
    }

    @Test
    fun `deleting right after a cross-account move keeps and frees the DELETE of the original`() = runTest {
        val event = insertSynced(uid = "moved-cross")
        val target = otherCalendar(AccountProvider.CALDAV)
        eventWriter.moveEventToCalendar(event.id, target)
        val before = System.currentTimeMillis()

        eventWriter.deleteEvent(event.id)

        val delete = ops().single()
        assertEquals(PendingOperation.OPERATION_DELETE, delete.operation)
        assertEquals(event.caldavUrl, delete.targetUrl)
        assertEquals(null, delete.linkedMoveId)
        assertTrue(delete.lifetimeResetAt >= before)
        assertEquals(listOf(delete), database.pendingOperationsDao().getReadyOperations(Long.MAX_VALUE))
    }

    @Test
    fun `a MOVE already running is not converted and goes with its row`() = runTest {
        val event = insertSynced(uid = "moved-running")
        val target = otherCalendar(AccountProvider.ICLOUD)
        eventWriter.moveEventToCalendar(event.id, target)
        val dao = database.pendingOperationsDao()
        dao.markInProgress(dao.getAll().single().id, System.currentTimeMillis())

        eventWriter.deleteEvent(event.id)

        assertEquals(emptyList<PendingOperation>(), ops())
    }

    @Test
    fun `deleting right after a local-to-synced move leaves nothing queued`() = runTest {
        val local = otherCalendar(AccountProvider.LOCAL)
        val now = System.currentTimeMillis()
        val created = eventWriter.createEvent(
            Event(uid = "", calendarId = local, title = "Local", startTs = now, endTs = now + 3_600_000, dtstamp = now),
            isLocal = true,
        )
        eventWriter.moveEventToCalendar(created.id, calendarId)

        eventWriter.deleteEvent(created.id)

        assertEquals(emptyList<PendingOperation>(), ops())
    }
}
