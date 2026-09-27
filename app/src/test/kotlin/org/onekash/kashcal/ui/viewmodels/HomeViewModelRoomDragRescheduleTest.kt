package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Tests drag-to-reschedule of Room events through [HomeViewModel.rescheduleEvent]
 * and [HomeViewModel.confirmReschedule] over a real in-memory Room database, the
 * real [EventCoordinator], [EventWriter] and [OccurrenceGenerator].
 *
 * The drop arrives as the timeline gives it: a date and a start minute in the
 * phone's zone. Fixture: a weekly Monday 10:00 America/New_York series with
 * COUNT=4 starting 4 Mar 2024 (EST); New York moves to EDT on 10 Mar, between the
 * first and the second occurrence. The two ignored tests record same-day
 * all-events drags that still lose an edited occurrence or an UNTIL series'
 * last occurrence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeViewModelRoomDragRescheduleTest {

    private val dispatcher = StandardTestDispatcher()
    private val savedZone: TimeZone = TimeZone.getDefault()

    private lateinit var db: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private lateinit var coordinator: EventCoordinator
    private lateinit var reader: EventReader
    private val viewModel: HomeViewModel by lazy {
        val device = FakeCalendarProviderRepository()
        val factory = DeviceHomeViewModelTestFactory(eventCoordinator = coordinator, eventReader = reader)
        factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }
    private var calendarId = 0L

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(NEW_YORK))
        Dispatchers.setMain(dispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        val writer = EventWriter(db, generator)
        reader = EventReader(db)
        coordinator = EventCoordinator(
            eventWriter = writer,
            eventReader = reader,
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            // Strict: the drag paths never read subscriptions or contact calendars.
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = realAccountRepository(),
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = TestDataStoreFactory.createDefault(),
        )
        runBlocking {
            val accountId = db.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = "me@example.test")
            )
            calendarId = db.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "https://dav.example.test/cal/work/", displayName = "Work", color = 0)
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        TimeZone.setDefault(savedZone)
    }

    // ---- this event, same-day and one-off drags ----

    @Test
    fun `a this event drag to another day moves only that occurrence`() = runTest(dispatcher) {
        val id = seedWeeklySeries()

        drag(id, occurrence(2), LocalDate.of(2024, 3, 12), 10, 0, EditScope.THIS_EVENT)

        val master = event(id)
        assertEquals("the series keeps its rule", RULE, master.rrule)
        assertEquals("the series keeps its start", occurrence(1), master.startTs)
        val exception = db.eventsDao().getExceptionsForMaster(id).single()
        assertEquals("the moved occurrence", newYork(LocalDate.of(2024, 3, 12), 10, 0), exception.startTs)
        assertEquals("it replaces the 11 Mar occurrence", occurrence(2), exception.originalInstanceTime)
        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `a same-day time drag with all events moves every occurrence to the new time`() = runTest(dispatcher) {
        val id = seedWeeklySeries()

        drag(id, occurrence(2), LocalDate.of(2024, 3, 11), 11, 0, EditScope.ALL_EVENTS)

        val master = event(id)
        assertEquals(RULE, master.rrule)
        assertEquals((1..4).map { occurrence(it, hour = 11) }, occurrenceStarts(id))
    }

    @Test
    fun `a same-day time drag with this and future splits the series at the occurrence`() = runTest(dispatcher) {
        val id = seedWeeklySeries()

        drag(id, occurrence(3), LocalDate.of(2024, 3, 18), 11, 0, EditScope.THIS_AND_FUTURE)

        assertEquals("the old series ends before the split", listOf(occurrence(1), occurrence(2)), occurrenceStarts(id))
        val newSeries = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single { it.id != id }
        assertEquals(occurrence(3, hour = 11), newSeries.startTs)
        assertEquals(listOf(occurrence(3, hour = 11), occurrence(4, hour = 11)), occurrenceStarts(newSeries.id))
    }

    @Test
    fun `a one-off event dragged to another day moves to that day and time`() = runTest(dispatcher) {
        val start = newYork(LocalDate.of(2024, 3, 11), 10, 0)
        val id = seed(event(uid = "one-off@example.test", start = start, rrule = null))

        drag(id, start, LocalDate.of(2024, 3, 13), 14, 30, EditScope.THIS_EVENT)

        assertEquals(newYork(LocalDate.of(2024, 3, 13), 14, 30), event(id).startTs)
        assertEquals(newYork(LocalDate.of(2024, 3, 13), 15, 30), event(id).endTs)
    }

    // ---- a cross-day move of the series ----

    @Test
    fun `an all events drag to the next day moves the series and its rule`() = runTest(dispatcher) {
        val id = seedWeeklySeries()
        val sequenceBefore = event(id).sequence

        drag(id, occurrence(2), LocalDate.of(2024, 3, 12), 10, 0, EditScope.ALL_EVENTS)

        val master = event(id)
        assertEquals("FREQ=WEEKLY;BYDAY=TU;COUNT=4", master.rrule)
        assertEquals(tuesday(1), master.startTs)
        assertEquals((1..4).map { tuesday(it) }, occurrenceStarts(id))
        assertEquals(listOf("UPDATE"), operations(id))
        assertTrue("SEQUENCE is raised", master.sequence > sequenceBefore)
        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `a this and future drag to the next day starts a Tuesday series and keeps the total`() = runTest(dispatcher) {
        val id = seed(event(uid = "six@example.test", start = occurrence(1), rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=6"))

        drag(id, occurrence(3), LocalDate.of(2024, 3, 19), 10, 0, EditScope.THIS_AND_FUTURE)

        assertEquals("the old series ends before the split", listOf(occurrence(1), occurrence(2)), occurrenceStarts(id))
        val newSeries = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single { it.id != id }
        assertEquals(tuesday(3), newSeries.startTs)
        assertEquals((3..6).map { tuesday(it) }, occurrenceStarts(newSeries.id))
        assertTrue(newSeries.rrule!!.contains("BYDAY=TU"))
    }

    @Test
    fun `an all events drag to another day and time moves every occurrence to that local time`() = runTest(dispatcher) {
        val id = seedWeeklySeries()

        drag(id, occurrence(2), LocalDate.of(2024, 3, 12), 11, 30, EditScope.ALL_EVENTS)

        assertEquals((1..4).map { tuesday(it, 11, 30) }, occurrenceStarts(id))
    }

    @Test
    fun `an until series moved a day keeps its last occurrence`() = runTest(dispatcher) {
        val id = seed(event(uid = "until-move@example.test", start = occurrence(1), rrule = untilRule()))

        drag(id, occurrence(2), LocalDate.of(2024, 3, 12), 10, 0, EditScope.ALL_EVENTS)

        assertEquals((1..4).map { tuesday(it) }, occurrenceStarts(id))
    }

    @Test
    fun `a drag on a phone in another zone moves the series by the event's own days and clock`() = runTest(dispatcher) {
        val id = seedWeeklySeries()
        // The phone is in Berlin; the series is at 10:00 New York, 15:00 in Berlin on 12 Mar.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))

        drag(id, occurrence(2), LocalDate.of(2024, 3, 12), 15, 0, EditScope.ALL_EVENTS)

        assertEquals("FREQ=WEEKLY;BYDAY=TU;COUNT=4", event(id).rrule)
        assertEquals((1..4).map { tuesday(it) }, occurrenceStarts(id))
    }

    @Test
    fun `a series with no timezone moves in the phone's zone`() = runTest(dispatcher) {
        val id = seed(event(uid = "floating@example.test", start = occurrence(1), rrule = RULE).copy(timezone = null))

        drag(id, occurrence(2), LocalDate.of(2024, 3, 12), 10, 0, EditScope.ALL_EVENTS)

        assertEquals((1..4).map { tuesday(it) }, occurrenceStarts(id))
    }

    @Test
    fun `a this and future drag to an earlier day keeps the dragged occurrence when an earlier one was deleted`() = runTest(dispatcher) {
        // Mon, Wed, Fri from 4 Mar, nine in all; Mon 11 Mar deleted. Wed 13 is dropped on Mon 11.
        val id = seed(event(uid = "mwf@example.test", start = occurrence(1), rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=9"))
        coordinator.deleteSingleOccurrence(id, occurrence(2))
        val wed13 = newYork(LocalDate.of(2024, 3, 13), 10, 0)

        drag(id, wed13, LocalDate.of(2024, 3, 11), 10, 0, EditScope.THIS_AND_FUTURE)

        val newSeries = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single { it.id != id }
        assertEquals(
            "the five moved occurrences, the dragged one first",
            listOf(11, 13, 16, 18, 20).map { newYork(LocalDate.of(2024, 3, it), 10, 0) },
            occurrenceStarts(newSeries.id),
        )
        assertNull("the old series' deleted date doesn't travel", newSeries.exdate)
    }

    // ---- what a cross-day drop can't do ----

    @Test
    fun `a monthly 29th dropped on the 31st offers neither series scope and refuses all events`() = runTest(dispatcher) {
        val start = newYork(LocalDate.of(2024, 1, 29), 10, 0)
        val id = seed(event(uid = "monthly@example.test", start = start, rrule = "FREQ=MONTHLY;BYMONTHDAY=29;COUNT=6"))

        val blocked = stage(id, start, LocalDate.of(2024, 1, 31), 10, 0)
        assertEquals(setOf(EditScope.ALL_EVENTS, EditScope.THIS_AND_FUTURE), blocked)

        confirm(EditScope.ALL_EVENTS)
        assertEquals("nothing written", start, event(id).startTs)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=29;COUNT=6", event(id).rrule)
        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULE_FAILED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `a deleted occurrence blocks all events, and this and future only when it lies ahead`() = runTest(dispatcher) {
        val earlier = seedWeeklySeries()
        coordinator.deleteSingleOccurrence(earlier, occurrence(1))
        assertEquals(setOf(EditScope.ALL_EVENTS), stage(earlier, occurrence(3), LocalDate.of(2024, 3, 19), 10, 0))
        viewModel.cancelPendingReschedule()

        val ahead = seed(event(uid = "ahead@example.test", start = occurrence(1), rrule = RULE))
        coordinator.deleteSingleOccurrence(ahead, occurrence(4))
        assertEquals(
            setOf(EditScope.ALL_EVENTS, EditScope.THIS_AND_FUTURE),
            stage(ahead, occurrence(3), LocalDate.of(2024, 3, 19), 10, 0),
        )
    }

    @Test
    fun `an edited occurrence ahead blocks both series scopes`() = runTest(dispatcher) {
        val id = seedWeeklySeries()
        coordinator.editSingleOccurrence(id, occurrence(4)) { it.copy(title = "Standup (edited)", rrule = null) }

        assertEquals(
            setOf(EditScope.ALL_EVENTS, EditScope.THIS_AND_FUTURE),
            stage(id, occurrence(3), LocalDate.of(2024, 3, 19), 10, 0),
        )
    }

    @Test
    fun `a this and future confirm on a blocked drop splits nothing`() = runTest(dispatcher) {
        val id = seedWeeklySeries()
        coordinator.editSingleOccurrence(id, occurrence(4)) { it.copy(title = "Standup (edited)", rrule = null) }
        assertTrue(EditScope.THIS_AND_FUTURE in stage(id, occurrence(3), LocalDate.of(2024, 3, 19), 10, 0))

        confirm(EditScope.THIS_AND_FUTURE)

        assertEquals("no new series", listOf(id), db.eventsDao().getAllMasterEventsForCalendar(calendarId).map { it.id })
        assertEquals(RULE, event(id).rrule)
        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULE_FAILED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `an added date blocks all events`() = runTest(dispatcher) {
        val rdate = newYork(LocalDate.of(2024, 3, 13), 10, 0).toString()
        val id = seed(event(uid = "rdate@example.test", start = occurrence(1), rrule = RULE).copy(rdate = rdate))

        assertTrue(EditScope.ALL_EVENTS in stage(id, occurrence(2), LocalDate.of(2024, 3, 12), 10, 0))
    }

    @Test
    fun `the series is checked again when the move is confirmed`() = runTest(dispatcher) {
        val id = seedWeeklySeries()
        assertEquals(emptySet<EditScope>(), stage(id, occurrence(2), LocalDate.of(2024, 3, 12), 10, 0))
        // A sync deletes an occurrence between the drop and the confirm.
        coordinator.deleteSingleOccurrence(id, occurrence(4))

        confirm(EditScope.ALL_EVENTS)

        assertEquals("nothing written", RULE, event(id).rrule)
        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULE_FAILED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `a series a sync moved while the sheet was open is refused, not moved from the old day`() = runTest(dispatcher) {
        val id = seedWeeklySeries()
        assertEquals(emptySet<EditScope>(), stage(id, occurrence(2), LocalDate.of(2024, 3, 12), 10, 0))
        // A sync moves the whole series to Tuesdays before the confirm.
        val moved = event(id).copy(startTs = tuesday(1), endTs = tuesday(1) + HOUR_MS, rrule = "FREQ=WEEKLY;BYDAY=TU;COUNT=6")
        db.eventsDao().update(moved)
        generator.regenerateOccurrences(moved)

        confirm(EditScope.ALL_EVENTS)

        assertEquals("nothing written", "FREQ=WEEKLY;BYDAY=TU;COUNT=6", event(id).rrule)
        assertEquals(tuesday(1), event(id).startTs)
        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULE_FAILED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
    }

    // ---- when the scope sheet knows ----

    @Test
    fun `a same-day drop is staged with nothing blocked straight away`() = runTest(dispatcher) {
        val id = seedWeeklySeries()
        coordinator.deleteSingleOccurrence(id, occurrence(4))
        val e = event(id)
        viewModel.rescheduleEvent(shown(e, occurrence(2)), LocalDate.of(2024, 3, 11), 11 * 60)

        assertEquals("known without waiting", emptySet<EditScope>(), viewModel.uiState.value.pendingDragReschedule!!.blockedScopes)
    }

    @Test
    fun `a cross-day drop is staged with the series scopes unknown until checked`() = runTest(dispatcher) {
        val id = seedWeeklySeries()
        viewModel.rescheduleEvent(shown(event(id), occurrence(2)), LocalDate.of(2024, 3, 12), 10 * 60)

        assertNull("not yet known", viewModel.uiState.value.pendingDragReschedule!!.blockedScopes)
        awaitUntil("the check") { viewModel.uiState.value.pendingDragReschedule?.blockedScopes != null }
        assertEquals(emptySet<EditScope>(), viewModel.uiState.value.pendingDragReschedule!!.blockedScopes)
    }

    @Test
    fun `a cancelled drop never comes back when its check finishes`() = runTest(dispatcher) {
        val id = seedWeeklySeries()
        viewModel.rescheduleEvent(shown(event(id), occurrence(2)), LocalDate.of(2024, 3, 12), 10 * 60)
        viewModel.cancelPendingReschedule()

        repeat(20) { advanceUntilIdle(); Thread.sleep(10) }

        assertNull(viewModel.uiState.value.pendingDragReschedule)
    }

    @Test
    fun `a newer drop keeps its own result`() = runTest(dispatcher) {
        val clean = seedWeeklySeries()
        val the29th = newYork(LocalDate.of(2024, 1, 29), 10, 0)
        val monthly = seed(event(uid = "monthly2@example.test", start = the29th, rrule = "FREQ=MONTHLY;BYMONTHDAY=29;COUNT=6"))
        viewModel.rescheduleEvent(shown(event(monthly), the29th), LocalDate.of(2024, 1, 31), 10 * 60)
        viewModel.rescheduleEvent(shown(event(clean), occurrence(2)), LocalDate.of(2024, 3, 12), 10 * 60)

        awaitUntil("the check") { viewModel.uiState.value.pendingDragReschedule?.blockedScopes != null }
        repeat(20) { advanceUntilIdle(); Thread.sleep(10) }

        val pending = viewModel.uiState.value.pendingDragReschedule!!
        assertEquals(clean, (pending.displayEvent as DisplayEvent.Room).event.id)
        assertEquals(emptySet<EditScope>(), pending.blockedScopes)
    }

    @Test
    fun `an earlier drop's check never lands on a newer drop`() = runTest(dispatcher) {
        val clean = seedWeeklySeries()
        val the29th = newYork(LocalDate.of(2024, 1, 29), 10, 0)
        val monthly = seed(event(uid = "monthly3@example.test", start = the29th, rrule = "FREQ=MONTHLY;BYMONTHDAY=29;COUNT=6"))
        // A cross-day drop whose check would block both series scopes...
        viewModel.rescheduleEvent(shown(event(monthly), the29th), LocalDate.of(2024, 1, 31), 10 * 60)
        // ...replaced, before that check runs, by a same-day drop that blocks nothing.
        viewModel.rescheduleEvent(shown(event(clean), occurrence(2)), LocalDate.of(2024, 3, 11), 11 * 60)

        repeat(20) { advanceUntilIdle(); Thread.sleep(10) }

        val pending = viewModel.uiState.value.pendingDragReschedule!!
        assertEquals(clean, (pending.displayEvent as DisplayEvent.Room).event.id)
        assertEquals("the earlier drop's result didn't land", emptySet<EditScope>(), pending.blockedScopes)
    }

    // ---- same-day all-events drags: known gaps, ignored ----

    @Ignore("A same-day time drag of all events moves the series start but not the edited occurrence's original time, so the edit no longer matches any occurrence of the series")
    @Test
    fun `a same-day time drag of all events keeps an edited occurrence attached to the series`() = runTest(dispatcher) {
        val id = seedWeeklySeries()
        coordinator.editSingleOccurrence(id, occurrence(3)) { it.copy(title = "Standup (edited)", rrule = null) }
        advanceUntilIdle()

        drag(id, occurrence(2), LocalDate.of(2024, 3, 11), 11, 0, EditScope.ALL_EVENTS)

        val exception = db.eventsDao().getExceptionsForMaster(id).single()
        assertEquals("the edit follows its occurrence to 11:00", occurrence(3, hour = 11), exception.originalInstanceTime)
    }

    @Ignore("A same-day later-time drag of all events keeps an UNTIL equal to the old last occurrence, so the moved last occurrence falls after it and disappears")
    @Test
    fun `a same-day later-time drag of all events keeps the last occurrence of an until series`() = runTest(dispatcher) {
        // UNTIL is exactly the last 10:00 occurrence, the form servers commonly write.
        val until = Instant.ofEpochMilli(occurrence(4)).atZone(ZoneId.of("UTC"))
        val rule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=%04d%02d%02dT%02d%02d00Z".format(
            until.year, until.monthValue, until.dayOfMonth, until.hour, until.minute,
        )
        val id = seed(event(uid = "until@example.test", start = occurrence(1), rrule = rule))

        drag(id, occurrence(2), LocalDate.of(2024, 3, 11), 11, 0, EditScope.ALL_EVENTS)

        assertEquals((1..4).map { occurrence(it, hour = 11) }, occurrenceStarts(id))
    }

    // ---- helpers ----

    /** The [n]th (1-based) occurrence moved to Tuesday, at [hour]:[minute] New York time. */
    private fun tuesday(n: Int, hour: Int = 10, minute: Int = 0): Long =
        newYork(LocalDate.of(2024, 3, 5).plusWeeks(n - 1L), hour, minute)

    /** A weekly Monday rule whose UNTIL is exactly the fourth occurrence, as servers write it. */
    private fun untilRule(): String {
        val until = Instant.ofEpochMilli(occurrence(4)).atZone(ZoneId.of("UTC"))
        return "FREQ=WEEKLY;BYDAY=MO;UNTIL=%04d%02d%02dT%02d%02d00Z".format(
            until.year, until.monthValue, until.dayOfMonth, until.hour, until.minute,
        )
    }

    private suspend fun operations(id: Long): List<String> =
        db.pendingOperationsDao().getForEvent(id).map { it.operation }

    private suspend fun shown(e: Event, occurrenceTs: Long): DisplayEvent.Room =
        DisplayEvent.Room(event = e, occurrence = db.occurrencesDao().getForEvent(e.id).single { it.startTs == occurrenceTs }, calendar = null)

    /** Drops without confirming and returns the scopes the sheet greys out once known. */
    private suspend fun TestScope.stage(id: Long, occurrenceTs: Long, date: LocalDate, hour: Int, minute: Int): Set<EditScope> {
        viewModel.rescheduleEvent(shown(event(id), occurrenceTs), date, hour * 60 + minute)
        awaitUntil("the check") { viewModel.uiState.value.pendingDragReschedule?.blockedScopes != null }
        return viewModel.uiState.value.pendingDragReschedule!!.blockedScopes!!
    }

    private suspend fun TestScope.confirm(scope: EditScope) {
        viewModel.confirmReschedule(scope)
        awaitUntil("the reschedule to finish") { viewModel.uiState.value.pendingSnackbarMessage != null }
    }

    private fun newYork(date: LocalDate, hour: Int, minute: Int): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), ZoneId.of(NEW_YORK)).toInstant().toEpochMilli()

    /** The [n]th (1-based) Monday occurrence of the series, at [hour] New York time. */
    private fun occurrence(n: Int, hour: Int = 10): Long = newYork(LocalDate.of(2024, 3, 4).plusWeeks(n - 1L), hour, 0)

    private fun event(uid: String, start: Long, rrule: String?) = Event(
        uid = uid, calendarId = calendarId, title = "Standup",
        startTs = start, endTs = start + HOUR_MS, timezone = NEW_YORK,
        rrule = rrule, dtstamp = 1, createdAt = 1, updatedAt = 1,
    )

    private suspend fun seedWeeklySeries(): Long = seed(event(uid = "series@example.test", start = occurrence(1), rrule = RULE))

    /** Stores [event] as a synced server event with its occurrences, and no queued work. */
    private suspend fun seed(event: Event): Long {
        val synced = event.copy(
            caldavUrl = "https://dav.example.test/cal/work/${event.uid}.ics",
            etag = "\"e1\"",
            syncStatus = SyncStatus.SYNCED,
        )
        val id = db.eventsDao().insert(synced)
        generator.regenerateOccurrences(synced.copy(id = id))
        return id
    }

    private suspend fun event(id: Long): Event = db.eventsDao().getById(id)!!

    private suspend fun occurrenceStarts(id: Long): List<Long> =
        db.occurrencesDao().getForEvent(id).filter { !it.isCancelled }.map { it.startTs }.sorted()

    /**
     * Drops the occurrence of [id] starting at [occurrenceTs] on [date] at [hour]:[minute] in the
     * phone's zone and picks [scope].
     */
    private suspend fun TestScope.drag(id: Long, occurrenceTs: Long, date: LocalDate, hour: Int, minute: Int, scope: EditScope) {
        val e = event(id)
        val occurrence = db.occurrencesDao().getForEvent(id).single { it.startTs == occurrenceTs }
        val shown = DisplayEvent.Room(event = e, occurrence = occurrence, calendar = null)
        viewModel.rescheduleEvent(shown, date, hour * 60 + minute)
        advanceUntilIdle()
        if (e.rrule != null) {
            viewModel.confirmReschedule(scope)
        }
        awaitUntil("the reschedule to finish") { viewModel.uiState.value.pendingSnackbarMessage != null }
        assertNull("the scope sheet is closed", viewModel.uiState.value.pendingDragReschedule)
    }

    /**
     * Waits for work the ViewModel launched: a split runs in a Room transaction
     * on Room's own executor, so the test scheduler alone can go idle first.
     */
    private suspend fun TestScope.awaitUntil(what: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            advanceUntilIdle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun realAccountRepository(): AccountRepository = AccountRepositoryImpl(
        accountsDao = db.accountsDao(),
        addressBookDao = db.addressBookDao(),
        calendarsDao = db.calendarsDao(),
        eventsDao = db.eventsDao(),
        pendingOperationsDao = db.pendingOperationsDao(),
        // Strict: organizer lookup only reads the account row.
        credentialManager = mockk(),
        reminderScheduler = mockk(relaxed = true),
        workManager = mockk(relaxed = true),
        contactSystemAccountRegistrar = mockk(relaxed = true),
        contactsProviderRepository = mockk(),
    )

    private companion object {
        const val NEW_YORK = "America/New_York"
        const val RULE = "FREQ=WEEKLY;BYDAY=MO;COUNT=4"
        const val HOUR_MS = 3_600_000L
    }
}
