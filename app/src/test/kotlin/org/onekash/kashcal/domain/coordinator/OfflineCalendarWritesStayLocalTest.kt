package org.onekash.kashcal.domain.coordinator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.onekash.kashcal.testutil.insertOfflineCalendar

/**
 * Every write through [EventCoordinator] to a calendar of the offline account stays on the
 * phone: nothing is queued and no sync is requested, and rows are SYNCED (for the series edits
 * that stamp PENDING_UPDATE, in the same state as on the built-in calendar). Runs on the built-in
 * calendar and on a second offline calendar at a `local://calendar/<uuid>` URL, over real Room
 * with the pending-operation trigger installed. A CalDAV calendar is the control that still
 * queues and syncs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OfflineCalendarWritesStayLocalTest {

    private lateinit var db: KashCalDatabase
    private lateinit var coordinator: EventCoordinator
    private val syncScheduler: SyncScheduler = mockk(relaxed = true)

    private var builtInId = 0L
    private var secondId = 0L
    private var caldavId = 0L

    private val day = 86_400_000L
    private val start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS).toEpochMilli()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context)
            .addCallback(PendingOperationTriggers.callback())
            .allowMainThreadQueries()
            .build()
        val generator = OccurrenceGenerator(
            db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault()
        )
        val accounts = AccountRepositoryImpl(
            accountsDao = db.accountsDao(),
            addressBookDao = db.addressBookDao(),
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            pendingOperationsDao = db.pendingOperationsDao(),
            credentialManager = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            workManager = mockk(relaxed = true),
            contactSystemAccountRegistrar = mockk(relaxed = true),
            contactsProviderRepository = mockk(relaxed = true),
        )
        val dataStore = mockk<KashCalDataStore> {
            every { defaultReminderMinutes } returns flowOf(15)
            every { defaultAllDayReminder } returns flowOf(1440)
        }
        coordinator = EventCoordinator(
            eventWriter = EventWriter(db, generator),
            eventReader = EventReader(db),
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = accounts,
            syncScheduler = syncScheduler,
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = dataStore,
        )
        runBlocking {
            builtInId = coordinator.ensureLocalCalendarExists()
            secondId = db.insertOfflineCalendar()
            val caldavAccount = db.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = "user@example.test")
            )
            caldavId = db.calendarsDao().insert(
                Calendar(
                    accountId = caldavAccount,
                    caldavUrl = "https://dav.example.test/cal/personal/",
                    displayName = "Personal",
                    color = 0
                )
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private val offlineCalendars get() = listOf(builtInId, secondId)

    private fun oneOff(calendarId: Long, title: String = "Dentist") = Event(
        uid = "offline-${UUID.randomUUID()}@example.test",
        calendarId = calendarId,
        title = title,
        startTs = start,
        endTs = start + 3_600_000,
        dtstamp = start,
    )

    private fun daily(calendarId: Long) = oneOff(calendarId, "Standup").copy(rrule = "FREQ=DAILY;COUNT=5")

    private suspend fun url(calendarId: Long) = db.calendarsDao().getById(calendarId)!!.caldavUrl

    private suspend fun rows(calendarId: Long): List<Event> {
        val masters = db.eventsDao().getAllMasterEventsForCalendar(calendarId)
        return masters + masters.flatMap { db.eventsDao().getExceptionsForMaster(it.id) }
    }

    /** Asserts nothing is queued for upload and no sync was asked for. */
    private suspend fun assertNothingQueued(calendarId: Long) {
        assertEquals("${url(calendarId)}: queued operations", emptyList<PendingOperation>(), db.pendingOperationsDao().getAllOnce())
        verify(exactly = 0) { syncScheduler.requestExpeditedSync(any()) }
    }

    /** Asserts every row in [calendarId] is SYNCED and nothing left the phone. */
    private suspend fun assertNothingLeavesThePhone(calendarId: Long) {
        rows(calendarId).forEach { assertEquals("${url(calendarId)}: ${it.title}", SyncStatus.SYNCED, it.syncStatus) }
        assertNothingQueued(calendarId)
    }

    /**
     * Runs [op] on each offline calendar and asserts nothing is queued and the second calendar's
     * rows end in the same sync states as the built-in's.
     *
     * Used for the series edits that go through
     * [org.onekash.kashcal.data.db.dao.EventsDao.updateExdate] or
     * [org.onekash.kashcal.data.db.dao.EventsDao.updateRrule], which stamp PENDING_UPDATE on any
     * calendar; with nothing queued that state is never pushed.
     */
    private suspend fun assertSameAsBuiltIn(op: suspend (calendarId: Long) -> Unit) {
        val states = offlineCalendars.map { calendarId ->
            op(calendarId)
            assertNothingQueued(calendarId)
            rows(calendarId).map { it.title to it.syncStatus }
        }
        assertEquals(states[0], states[1])
    }

    @Test
    fun `creating an event on an offline calendar stays on the phone`() = runBlocking {
        for (calendarId in offlineCalendars) {
            val created = coordinator.createEvent(oneOff(calendarId), calendarId)
            assertEquals(SyncStatus.SYNCED, db.eventsDao().getById(created.id)!!.syncStatus)
            assertNothingLeavesThePhone(calendarId)
        }
    }

    @Test
    fun `editing an event on an offline calendar stays on the phone`() = runBlocking {
        for (calendarId in offlineCalendars) {
            val created = coordinator.createEvent(oneOff(calendarId), calendarId)
            coordinator.updateEvent(created.copy(title = "Dentist moved"))
            assertEquals("Dentist moved", db.eventsDao().getById(created.id)!!.title)
            assertNothingLeavesThePhone(calendarId)
        }
    }

    @Test
    fun `editing one occurrence on an offline calendar stays on the phone`() = runBlocking {
        for (calendarId in offlineCalendars) {
            val series = coordinator.createRecurringEvent(daily(calendarId), calendarId)
            val exception = coordinator.editSingleOccurrence(series.id, start + day) { it.copy(title = "Standup late") }
            assertEquals(series.id, exception.originalEventId)
            assertEquals(SyncStatus.SYNCED, db.eventsDao().getById(exception.id)!!.syncStatus)
            assertNothingLeavesThePhone(calendarId)
        }
    }

    @Test
    fun `editing this and future occurrences on an offline calendar stays on the phone`() = runBlocking {
        assertSameAsBuiltIn { calendarId ->
            val series = coordinator.createRecurringEvent(daily(calendarId), calendarId)
            val newSeries = coordinator.editThisAndFuture(series.id, start + 2 * day) { it.copy(title = "Standup v2") }
            assertTrue("split into a new series", newSeries.id != series.id)
            assertEquals(SyncStatus.SYNCED, db.eventsDao().getById(newSeries.id)!!.syncStatus)
            assertEquals(2, db.eventsDao().getAllMasterEventsForCalendar(calendarId).size)
        }
    }

    @Test
    fun `deleting an event on an offline calendar stays on the phone`() = runBlocking {
        for (calendarId in offlineCalendars) {
            val created = coordinator.createEvent(oneOff(calendarId), calendarId)
            coordinator.deleteEvent(created.id)
            assertNull("${url(calendarId)}: removed, not marked for a server delete", db.eventsDao().getById(created.id))
            assertNothingLeavesThePhone(calendarId)
        }
    }

    @Test
    fun `deleting one occurrence on an offline calendar stays on the phone`() = runBlocking {
        assertSameAsBuiltIn { calendarId ->
            val series = coordinator.createRecurringEvent(daily(calendarId), calendarId)
            coordinator.deleteSingleOccurrence(series.id, start + day)
            assertNotNull(db.eventsDao().getById(series.id)!!.exdate)
        }
    }

    @Test
    fun `deleting this and future occurrences on an offline calendar stays on the phone`() = runBlocking {
        assertSameAsBuiltIn { calendarId ->
            val series = coordinator.createRecurringEvent(daily(calendarId), calendarId)
            coordinator.deleteThisAndFuture(series.id, start + 2 * day)
            assertEquals(2, db.occurrencesDao().getForEvent(series.id).size)
        }
    }

    @Test
    fun `moving between offline calendars stays on the phone in both directions`() = runBlocking {
        val created = coordinator.createEvent(oneOff(builtInId), builtInId)

        coordinator.moveEventToCalendar(created.id, secondId)
        assertEquals(secondId, db.eventsDao().getById(created.id)!!.calendarId)
        assertNothingLeavesThePhone(secondId)

        coordinator.moveEventToCalendar(created.id, builtInId)
        assertEquals(builtInId, db.eventsDao().getById(created.id)!!.calendarId)
        assertNothingLeavesThePhone(builtInId)
    }

    @Test
    fun `importing a plain file into a second offline calendar stays on the phone`() = runBlocking {
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN",
            "BEGIN:VEVENT", "UID:plain-import@example.test", "DTSTAMP:20261001T100000Z",
            "DTSTART:20261101T100000Z", "DTEND:20261101T110000Z", "SUMMARY:Imported",
            "END:VEVENT", "END:VCALENDAR", ""
        ).joinToString("\r\n")

        val result = coordinator.importIcsFile(requireNotNull(IcsParserService.parseForImport(ics)), secondId)

        assertEquals("$result", 1, result.created)
        assertEquals("Imported", db.eventsDao().getAllMasterEventsForCalendar(secondId).single().title)
        assertNothingLeavesThePhone(secondId)
    }

    @Test
    fun `moving from an offline calendar to a CalDAV calendar queues its upload and requests one sync`() = runBlocking {
        val created = coordinator.createEvent(oneOff(secondId), secondId)

        coordinator.moveEventToCalendar(created.id, caldavId)

        val moved = db.eventsDao().getById(created.id)!!
        assertEquals(caldavId, moved.calendarId)
        assertEquals(SyncStatus.PENDING_CREATE, moved.syncStatus)
        assertEquals(
            listOf(PendingOperation.OPERATION_CREATE),
            db.pendingOperationsDao().getForEvent(created.id).map { it.operation }
        )
        verify(exactly = 1) { syncScheduler.requestExpeditedSync(any()) }
    }

    @Test
    fun `creating an event on a CalDAV calendar still queues it and requests a sync`() = runBlocking {
        val created = coordinator.createEvent(oneOff(caldavId), caldavId)

        assertEquals(SyncStatus.PENDING_CREATE, db.eventsDao().getById(created.id)!!.syncStatus)
        assertEquals(
            listOf(PendingOperation.OPERATION_CREATE),
            db.pendingOperationsDao().getForEvent(created.id).map { it.operation }
        )
        verify(exactly = 1) { syncScheduler.requestExpeditedSync(any()) }
    }

    @Test
    fun `deleting a second offline calendar's row removes its events, occurrences and queued ops`() = runBlocking {
        val created = coordinator.createRecurringEvent(daily(secondId), secondId)
        // Nothing queues on an offline calendar, so seed a row for the delete to clear.
        db.pendingOperationsDao().insert(
            PendingOperation(eventId = created.id, operation = PendingOperation.OPERATION_UPDATE)
        )
        assertEquals(1, db.pendingOperationsDao().getForEvent(created.id).size)

        db.calendarsDao().deleteById(secondId)

        assertNull(db.eventsDao().getById(created.id))
        assertEquals(0, db.occurrencesDao().getForEvent(created.id).size)
        assertEquals(0, db.pendingOperationsDao().getForEvent(created.id).size)
    }
}
