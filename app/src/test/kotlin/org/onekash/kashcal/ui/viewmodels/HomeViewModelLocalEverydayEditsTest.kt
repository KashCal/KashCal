package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.toFormDateFields
import org.onekash.kashcal.ui.components.withDateFields
import org.onekash.kashcal.ui.components.withTimedStart
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * The everyday edits on a local (offline) calendar, through HomeViewModel over a real
 * database: a one-off edited in the form and dragged; one occurrence of a series renamed,
 * moved and deleted; a changed occurrence edited again, dragged and deleted. Each checks
 * what the calendar then shows and that nothing is queued for a server. The same edits on
 * a CalDAV calendar run live in
 * [org.onekash.kashcal.sync.integration.multiserver.MultiServerEverydayEditsTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeViewModelLocalEverydayEditsTest {

    private data class Shown(val start: Long, val end: Long, val title: String)

    private val zone = ZoneId.of("America/New_York")
    private val savedZone: TimeZone = TimeZone.getDefault()
    private val monday: LocalDate = LocalDate.of(2027, 1, 4)
    private lateinit var db: KashCalDatabase
    private lateinit var coordinator: EventCoordinator
    private lateinit var viewModel: HomeViewModel
    private var calendarId = 0L

    @Before
    fun setUp() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        val reader = EventReader(db)
        val initializer = LocalCalendarInitializer(db)
        coordinator = EventCoordinator(
            eventWriter = EventWriter(db, generator),
            eventReader = reader,
            occurrenceGenerator = generator,
            localCalendarInitializer = initializer,
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = AccountRepositoryImpl(
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
            ),
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = TestDataStoreFactory.createDefault(),
        )
        calendarId = initializer.ensureLocalCalendarExists()
        val device = FakeCalendarProviderRepository()
        val factory = DeviceHomeViewModelTestFactory(eventCoordinator = coordinator, eventReader = reader)
        viewModel = factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), Dispatchers.IO)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        TimeZone.setDefault(savedZone)
        unmockkAll()
    }

    @Test
    fun `a one-off edited in the form keeps the edited fields`() = runBlocking<Unit> {
        val id = create(rrule = null)
        val newDay = monday.plusDays(2)

        save(openForm(id, null).withTimedStart(midnight(newDay), 14, 30, 60).copy(
            title = "Edited", location = "Room 4", description = "Notes",
        ), null)

        val e = db.eventsDao().getById(id)!!
        assertEquals(listOf("Edited", "Room 4", "Notes"), listOf(e.title, e.location, e.description))
        assertShown(listOf(Shown(at(newDay, 14, 30), at(newDay, 15, 30), "Edited")))
    }

    @Test
    fun `a one-off dragged to a new time and then another day moves each time`() = runBlocking<Unit> {
        val id = create(rrule = null)

        drag(id, at(monday, 10, 0), monday, 15, 0, null)
        assertShown(listOf(Shown(at(monday, 15, 0), at(monday, 16, 0), "Local")))
        drag(id, at(monday, 15, 0), monday.plusDays(3), 9, 0, null)
        assertShown(listOf(Shown(at(monday.plusDays(3), 9, 0), at(monday.plusDays(3), 10, 0), "Local")))
    }

    @Test
    fun `one occurrence renamed, moved to another day, dragged and deleted`() = runBlocking<Unit> {
        val id = create("FREQ=WEEKLY;COUNT=5")

        save(openForm(id, occurrence(2)).copy(title = "Renamed"), EditScope.THIS_EVENT)
        val opened = openForm(id, occurrence(3))
        save(opened.withTimedStart(opened.dateMillis + 86_400_000L, 10, 0, 60), EditScope.THIS_EVENT)
        drag(id, occurrence(4), day(4), 16, 0, EditScope.THIS_EVENT)
        viewModel.requestDeleteRoom(db.eventsDao().getById(id)!!, occurrence(5), occurrence(1), isDetachedException = false, isAllDay = false)
        viewModel.confirmDelete(EditScope.THIS_EVENT)
        waitFor { runBlocking { db.occurrencesDao().getForEvent(id).single { it.startTs == occurrence(5) }.isCancelled } }

        assertShown(listOf(
            series(1),
            series(2, "Renamed"),
            Shown(at(day(3).plusDays(1), 10, 0), at(day(3).plusDays(1), 11, 0), "Local"),
            Shown(at(day(4), 16, 0), at(day(4), 17, 0), "Local"),
        ))
    }

    @Test
    fun `a changed occurrence edited again, dragged, then deleted from its form`() = runBlocking<Unit> {
        val id = create("FREQ=WEEKLY;COUNT=5")
        save(openForm(id, occurrence(2)).copy(title = "Renamed"), EditScope.THIS_EVENT)

        val exception = exception(id)
        val opened = openForm(exception.id, exception.originalInstanceTime)
        save(opened.withTimedStart(opened.dateMillis, 11, 30, 60).copy(title = "Again"), EditScope.THIS_EVENT)
        assertEquals("one changed occurrence", 1, db.eventsDao().getByUid(exception.uid).count { it.originalEventId != null })
        assertShown((1..5).map { if (it == 2) Shown(at(day(2), 11, 30), at(day(2), 12, 30), "Again") else series(it) })

        drag(id, at(day(2), 11, 30), day(2).plusDays(1), 12, 0, null)
        assertShown((1..5).map { if (it == 2) Shown(at(day(2).plusDays(1), 12, 0), at(day(2).plusDays(1), 13, 0), "Again") else series(it) })

        assertTrue(viewModel.handleRoomEventFormDelete(exception.id, exception.originalInstanceTime).isSuccess)
        assertShown((1..5).filter { it != 2 }.map { series(it) })
        assertEquals("no changed occurrence left", 0, db.eventsDao().getByUid(exception.uid).count { it.originalEventId != null })
    }

    /** Checks what the calendar views read for the five weeks, and that nothing is queued. */
    private suspend fun assertShown(expected: List<Shown>) {
        val got = EventReader(db).getOccurrencesWithEventsInRange(occurrence(1), occurrence(5) + 7 * 86_400_000L)
            .filter { !it.occurrence.isCancelled }
            .map { Shown(it.occurrence.startTs, it.occurrence.endTs, it.event.title) }
            .sortedBy { it.start }
        assertEquals(expected.sortedBy { it.start }, got)
        assertEquals("a local calendar queues nothing", 0, db.pendingOperationsDao().getAll().size)
    }

    private fun day(n: Int): LocalDate = monday.plusWeeks(n - 1L)
    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
    private fun occurrence(n: Int): Long = at(day(n), 10, 0)
    private fun series(n: Int, title: String = "Local"): Shown = Shown(occurrence(n), occurrence(n) + 3_600_000L, title)
    private fun midnight(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private suspend fun create(rrule: String?): Long {
        val start = occurrence(1)
        return coordinator.createEvent(
            Event(uid = "local-${System.nanoTime()}", calendarId = calendarId, title = "Local", startTs = start,
                endTs = start + 3_600_000L, timezone = zone.id, rrule = rrule, dtstamp = 1, createdAt = 1, updatedAt = 1),
            calendarId = calendarId,
        ).id
    }

    private suspend fun exception(masterId: Long): Event =
        db.eventsDao().getByUid(db.eventsDao().getById(masterId)!!.uid).single { it.originalEventId == masterId }

    private suspend fun save(form: EventFormState, scope: EditScope?) {
        val result = viewModel.saveEvent(form, scope)
        assertTrue("save: ${result.exceptionOrNull()}", result.isSuccess)
    }

    private suspend fun drag(id: Long, occurrenceTs: Long, date: LocalDate, hour: Int, minute: Int, scope: EditScope?) {
        val master = db.eventsDao().getById(id)!!
        val occurrence = db.occurrencesDao().getForEvent(id).single { it.startTs == occurrenceTs }
        val shown = occurrence.exceptionEventId?.let { db.eventsDao().getById(it) } ?: master
        viewModel.rescheduleEvent(DisplayEvent.Room(shown, occurrence, null), date, hour * 60 + minute)
        if (scope != null) {
            waitFor { viewModel.uiState.value.pendingDragReschedule?.blockedScopes != null }
            viewModel.confirmReschedule(scope)
        }
        waitFor { viewModel.pendingMoveState.value != null }
        viewModel.endDragUndoWindow()
        waitFor { viewModel.dragSaveSettled }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out")
            Thread.sleep(10)
        }
    }

    private suspend fun openForm(id: Long, occurrenceTs: Long?): EventFormState {
        val e = db.eventsDao().getById(id)!!
        return EventFormState().withDateFields(e.toFormDateFields(occurrenceTs)).copy(
            title = e.title,
            selectedCalendarId = e.calendarId,
            timezone = e.timezone,
            location = e.location.orEmpty(),
            description = e.description.orEmpty(),
            rrule = e.rrule,
            editingEventId = id,
            isEditMode = true,
            editingOccurrenceTs = occurrenceTs,
            editingZone = e.timezone,
        )
    }
}
