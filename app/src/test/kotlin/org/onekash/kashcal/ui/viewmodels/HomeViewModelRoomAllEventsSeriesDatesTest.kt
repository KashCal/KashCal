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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.parseIso8601DurationToMinutes
import org.onekash.kashcal.ui.components.toFormDateFields
import org.onekash.kashcal.ui.components.withAllDay
import org.onekash.kashcal.ui.components.withDateFields
import org.onekash.kashcal.ui.components.withTimedStart
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.TimezoneUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Tests an "All events" save made from a form opened on a later occurrence of a
 * recurring Room event, driven through [HomeViewModel.saveEvent] over a real
 * in-memory Room database, the real [EventCoordinator], [EventWriter] and
 * [OccurrenceGenerator]. The scope sheet tests call [HomeViewModel.requestFormSave]
 * and `computeEditScopeOptions`.
 *
 * The form shows the opened occurrence's date; the series starts earlier.
 * RFC 5545 section 3.8.5.3 makes DTSTART the first instance of the set, so the
 * save must keep the series' own first date: writing the opened date would drop
 * the occurrences before it and add as many after the end.
 *
 * Fixture: a weekly Tuesday 10:00 America/New_York series with COUNT=4 starting
 * 5 Mar 2024 (EST). New York moves to EDT on 10 Mar, so the third occurrence
 * (19 Mar), where the form is opened, is on the other side of a DST change from
 * the first. The all-day tests use a weekly COUNT=4 all-day series from the
 * same date.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeViewModelRoomAllEventsSeriesDatesTest {

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
    private var otherCalendarId = 0L

    @Before
    fun setUp() {
        pinPhoneZone(NEW_YORK)
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
            // Strict: the save paths never read subscriptions or contact calendars.
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
            otherCalendarId = db.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "https://dav.example.test/cal/home/", displayName = "Home", color = 0)
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        TimeZone.setDefault(savedZone)
    }

    // ---- non-date changes keep the series' dates ----

    @Test
    fun `renaming from a later occurrence renames the series and keeps its dates`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val form = openForm(id, occurrence(3)).copy(title = "Daily sync")

        assertTrue(saveAllEvents(form).isSuccess)

        val saved = event(id)
        assertEquals("Daily sync", saved.title)
        assertEquals(occurrence(1), saved.startTs)
        assertEquals(occurrence(1) + HOUR_MS, saved.endTs)
        assertEquals("FREQ=WEEKLY;COUNT=4", saved.rrule)
        assertEquals(NEW_YORK, saved.timezone)
        assertEquals(listOf(occurrence(1), occurrence(2), occurrence(3), occurrence(4)), occurrenceStarts(id))
    }

    @Test
    fun `other field edits from a later occurrence apply to the series and keep its dates`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val guest = Attendee(eventId = 0, address = "mailto:guest@example.test", partstat = "NEEDS-ACTION", sortOrder = 0)
        val form = openForm(id, occurrence(3)).copy(
            location = "Room 9",
            description = "new notes",
            reminders = listOf(30),
            eventColor = 0xFF00AA00.toInt(),
            transp = "TRANSPARENT",
            categories = listOf("Team", "Weekly"),
            attendees = listOf(guest),
            attendeesEdited = true,
        )

        assertTrue(saveAllEvents(form).isSuccess)

        val saved = event(id)
        assertEquals("Room 9", saved.location)
        assertEquals("new notes", saved.description)
        assertEquals(listOf("-PT30M"), saved.reminders)
        assertEquals(0xFF00AA00.toInt(), saved.color)
        assertEquals("TRANSPARENT", saved.transp)
        assertEquals(listOf("Team", "Weekly"), saved.categories)
        assertEquals(listOf("mailto:guest@example.test"), db.attendeesDao().getForEventOnce(id).map { it.address })
        assertEquals(occurrence(1), saved.startTs)
        assertEquals(occurrence(1) + HOUR_MS, saved.endTs)
        assertEquals(listOf(occurrence(1), occurrence(2), occurrence(3), occurrence(4)), occurrenceStarts(id))
    }

    @Test
    fun `a new time from a later occurrence moves every occurrence to it across the DST change`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val opened = openForm(id, occurrence(3))
        val form = opened.withTimedStart(opened.dateMillis, 11, 30, defaultDurationMinutes = 60)

        assertTrue(saveAllEvents(form).isSuccess)

        val saved = event(id)
        val newFirst = newYork(LocalDate.of(2024, 3, 5), 11, 30)
        assertEquals(newFirst, saved.startTs)
        assertEquals(newFirst + HOUR_MS, saved.endTs)
        val starts = occurrenceStarts(id).map { Instant.ofEpochMilli(it).atZone(ZoneId.of(NEW_YORK)).toLocalDateTime() }
        assertEquals(
            listOf(5, 12, 19, 26).map { LocalDateTime.of(2024, 3, it, 11, 30) },
            starts,
        )
    }

    @Test
    fun `renaming an all-day series from a later occurrence keeps its dates`() = runTest(dispatcher) {
        for (days in listOf(1, 2)) {
            val id = seedAllDaySeries(days)
            val before = event(id)
            val form = openForm(id, allDayOccurrence(3)).copy(title = "Offsite $days")

            assertTrue(saveAllEvents(form).isSuccess)

            val saved = event(id)
            assertEquals("Offsite $days", saved.title)
            assertEquals(before.startTs, saved.startTs)
            assertEquals(before.endTs, saved.endTs)
            assertEquals(
                listOf(allDayOccurrence(1), allDayOccurrence(2), allDayOccurrence(3), allDayOccurrence(4)),
                occurrenceStarts(id),
            )
        }
    }

    @Test
    fun `turning all-day on from a later occurrence starts the all-day series on its first date`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val form = openForm(id, occurrence(3)).withAllDay(true, defaultReminderTimed = 15, defaultReminderAllDay = 1440)

        assertTrue(saveAllEvents(form).isSuccess)

        val saved = event(id)
        assertTrue(saved.isAllDay)
        assertNull(saved.timezone)
        assertEquals(allDayOccurrence(1), saved.startTs)
        assertEquals(
            listOf(allDayOccurrence(1), allDayOccurrence(2), allDayOccurrence(3), allDayOccurrence(4)),
            occurrenceStarts(id),
        )
    }

    @Test
    fun `turning all-day off from a later occurrence starts the timed series on its first date`() = runTest(dispatcher) {
        val id = seedAllDaySeries(days = 1)
        val toggled = openForm(id, allDayOccurrence(3))
            .withAllDay(false, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
        val form = toggled.withTimedStart(toggled.dateMillis, 9, 0, defaultDurationMinutes = 60)
            .let { it.copy(endHour = 10, endMinute = 0, endDateMillis = it.dateMillis, endOffsetHintTs = null) }

        assertTrue(saveAllEvents(form).isSuccess)

        val saved = event(id)
        assertFalse(saved.isAllDay)
        // The form's zone is the phone's when an all-day event is switched to timed.
        val zone = TimezoneUtils.resolveZone(form.timezone)
        assertEquals(ZonedDateTime.of(LocalDate.of(2024, 3, 5), LocalTime.of(9, 0), zone).toInstant().toEpochMilli(), saved.startTs)
        val dates = occurrenceStarts(id).map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        assertEquals(listOf(5, 12, 19, 26).map { LocalDate.of(2024, 3, it) }, dates)
    }

    // ---- an unchanged save ----

    @Test
    fun `an unchanged save from a later occurrence stores the same series as one from the first`() = runTest(dispatcher) {
        val later = seedTimedSeries(uid = "later@example.test")
        val first = seedTimedSeries(uid = "first@example.test")
        val beforeLater = event(later)
        val occurrencesBefore = db.occurrencesDao().getForEvent(later).map { it.id to it.startTs }.sortedBy { it.second }

        assertTrue(saveAllEvents(openForm(later, occurrence(3))).isSuccess)
        assertTrue(saveAllEvents(openForm(first, occurrence(1))).isSuccess)

        val saved = event(later)
        assertEquals(beforeLater.startTs, saved.startTs)
        assertEquals(beforeLater.endTs, saved.endTs)
        assertEquals(beforeLater.isAllDay, saved.isAllDay)
        assertEquals(beforeLater.timezone, saved.timezone)
        assertEquals(beforeLater.rrule, saved.rrule)
        assertEquals(beforeLater.title, saved.title)
        assertEquals(beforeLater.location, saved.location)
        assertEquals(beforeLater.description, saved.description)
        assertEquals(beforeLater.reminders, saved.reminders)
        assertEquals(beforeLater.color, saved.color)
        assertEquals(beforeLater.transp, saved.transp)
        assertEquals(beforeLater.categories, saved.categories)
        assertEquals(beforeLater.sequence, saved.sequence)
        // Not regenerated: the same rows at the same times.
        assertEquals(
            occurrencesBefore,
            db.occurrencesDao().getForEvent(later).map { it.id to it.startTs }.sortedBy { it.second },
        )
        assertEquals(listOf(PendingOperation.OPERATION_UPDATE), operations(later))
        assertEquals(operations(first), operations(later))
    }

    // ---- a date change on a later occurrence ----

    @Test
    fun `moving a later occurrence to another day is refused for all events and writes nothing`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val before = event(id)
        val occurrencesBefore = occurrenceStarts(id)
        val opened = openForm(id, occurrence(3))
        val moved = opened.withTimedStart(opened.dateMillis + DAY_MS, 10, 0, defaultDurationMinutes = 60)

        assertTrue(saveAllEvents(moved).isFailure)
        assertTrue(saveAllEvents(moved.copy(title = "Renamed", selectedCalendarId = otherCalendarId)).isFailure)

        assertEquals(before, event(id))
        assertEquals(occurrencesBefore, occurrenceStarts(id))
        assertTrue(db.pendingOperationsDao().getAllOnce().isEmpty())
    }

    // ---- a calendar change ----

    @Test
    fun `renaming into another calendar from a later occurrence moves the series and keeps its dates`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val form = openForm(id, occurrence(3)).copy(title = "Moved", selectedCalendarId = otherCalendarId)

        assertTrue(saveAllEvents(form).isSuccess)

        val saved = event(id)
        assertEquals(otherCalendarId, saved.calendarId)
        assertEquals("Moved", saved.title)
        assertEquals(occurrence(1), saved.startTs)
        assertEquals(listOf(occurrence(1), occurrence(2), occurrence(3), occurrence(4)), occurrenceStarts(id))
    }

    // ---- from the first occurrence ----

    @Test
    fun `renaming from the first occurrence keeps the series' dates`() = runTest(dispatcher) {
        val id = seedTimedSeries()

        assertTrue(saveAllEvents(openForm(id, occurrence(1)).copy(title = "Daily sync")).isSuccess)

        assertEquals("Daily sync", event(id).title)
        assertEquals(occurrence(1), event(id).startTs)
        assertEquals(listOf(occurrence(1), occurrence(2), occurrence(3), occurrence(4)), occurrenceStarts(id))
    }

    @Test
    fun `moving the first occurrence to another day moves the series`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val opened = openForm(id, occurrence(1))
        val moved = opened.withTimedStart(opened.dateMillis + DAY_MS, 10, 0, defaultDurationMinutes = 60)

        assertTrue(saveAllEvents(moved).isSuccess)

        val wednesday = newYork(LocalDate.of(2024, 3, 6), 10, 0)
        assertEquals(wednesday, event(id).startTs)
        assertEquals(wednesday, occurrenceStarts(id).first())
    }

    @Test
    fun `moving the first occurrence a week later moves the series to the same time a week on`() = runTest(dispatcher) {
        // Another device pulling this edit sees the series start move by whole weeks at the same
        // local time, the case Recent changes words with dates rather than the time of day.
        val id = seedTimedSeries()
        val opened = openForm(id, occurrence(1))
        val moved = opened.withTimedStart(opened.dateMillis + 7 * DAY_MS, 10, 0, defaultDurationMinutes = 60)

        assertTrue(saveAllEvents(moved).isSuccess)

        // 12 Mar is after the change to EDT: same 10:00 local, one hour less than 7 x 24 h.
        assertEquals(newYork(LocalDate.of(2024, 3, 12), 10, 0), event(id).startTs)
    }

    // ---- dates are read in the event's zone ----

    @Test
    fun `renaming from a later occurrence on a phone in another zone keeps the series' dates`() = runTest(dispatcher) {
        pinPhoneZone("Asia/Tokyo")
        val id = seedTimedSeries()

        assertTrue(saveAllEvents(openForm(id, occurrence(3)).copy(title = "Daily sync")).isSuccess)

        assertEquals(occurrence(1), event(id).startTs)
        assertEquals(listOf(occurrence(1), occurrence(2), occurrence(3), occurrence(4)), occurrenceStarts(id))
    }

    // ---- other scopes ----

    @Test
    fun `moving a later occurrence to another day for this event only creates an exception there`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val opened = openForm(id, occurrence(3))
        val moved = opened.withTimedStart(opened.dateMillis + DAY_MS, 10, 0, defaultDurationMinutes = 60)

        assertTrue(viewModel.saveEvent(moved, EditScope.THIS_EVENT).isSuccess)

        assertEquals(occurrence(1), event(id).startTs)
        val exception = db.eventsDao().getByUid(event(id).uid).single { it.originalEventId == id }
        assertEquals(occurrence(3), exception.originalInstanceTime)
        assertEquals(newYork(LocalDate.of(2024, 3, 20), 10, 0), exception.startTs)
    }

    // ---- the scope sheet ----

    @Test
    fun `the scope sheet withholds all events when a later occurrence moved to another day`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val opened = openForm(id, occurrence(3))
        val moved = opened.withTimedStart(opened.dateMillis + DAY_MS, 10, 0, defaultDurationMinutes = 60)

        val options = scopeOptions(id, moved, occurrence(3))

        assertEquals(
            mapOf(EditScope.THIS_EVENT to true, EditScope.THIS_AND_FUTURE to true, EditScope.ALL_EVENTS to false),
            options,
        )
    }

    @Test
    fun `the scope sheet offers all events for other edits from a later occurrence`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val opened = openForm(id, occurrence(3))
        val expected =
            mapOf(EditScope.THIS_EVENT to true, EditScope.THIS_AND_FUTURE to true, EditScope.ALL_EVENTS to true)

        assertEquals(expected, scopeOptions(id, opened.copy(title = "Daily sync"), occurrence(3)))
        assertEquals(
            expected,
            scopeOptions(id, opened.withTimedStart(opened.dateMillis, 11, 30, defaultDurationMinutes = 60), occurrence(3)),
        )
    }

    @Test
    fun `the scope sheet offers all events for a date change on the first occurrence`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val opened = openForm(id, occurrence(1))
        val moved = opened.withTimedStart(opened.dateMillis + DAY_MS, 10, 0, defaultDurationMinutes = 60)

        assertEquals(
            mapOf(EditScope.THIS_EVENT to true, EditScope.THIS_AND_FUTURE to false, EditScope.ALL_EVENTS to true),
            scopeOptions(id, moved, occurrence(1)),
        )
    }

    @Test
    fun `the scope sheet for a detached exception offers only this event`() = runTest(dispatcher) {
        val id = seedTimedSeries()
        val master = event(id)
        val exceptionId = db.eventsDao().insert(
            master.copy(
                id = 0, rrule = null, originalEventId = id, originalInstanceTime = occurrence(3),
                startTs = occurrence(3) + HOUR_MS, endTs = occurrence(3) + 2 * HOUR_MS,
            )
        )
        val exception = event(exceptionId)
        val form = openForm(exceptionId, occurrence(3)).copy(title = "Moved one")

        val pending = requestSave(form, occurrence(3), exception, isDetachedException = true)

        assertEquals(
            mapOf(EditScope.THIS_EVENT to true, EditScope.THIS_AND_FUTURE to false, EditScope.ALL_EVENTS to false),
            optionsFor(pending),
        )
    }

    // ---- fixtures ----

    private suspend fun TestScope.scopeOptions(
        id: Long,
        form: EventFormState,
        occurrenceTs: Long,
    ): Map<EditScope, Boolean> = optionsFor(requestSave(form, occurrenceTs, event(id), isDetachedException = false))

    /** Stages the save the way the event form does for a Room event it loaded as [loaded]. */
    private fun TestScope.requestSave(
        form: EventFormState,
        occurrenceTs: Long,
        loaded: Event,
        isDetachedException: Boolean,
    ): PendingFormSave {
        viewModel.requestFormSave(
            formState = form,
            occurrenceTs = occurrenceTs,
            originalRrule = loaded.rrule,
            masterStartTs = loaded.startTs,
            isDetachedException = isDetachedException,
            isRecurringDevice = false,
            loadedIsAllDay = loaded.isAllDay,
        )
        advanceUntilIdle()
        return viewModel.uiState.value.pendingFormSave!!
    }

    private fun optionsFor(pending: PendingFormSave): Map<EditScope, Boolean> = computeEditScopeOptions(
        context = pending.toScopeContext(),
        originalRrule = pending.originalRrule,
        currentRrule = pending.formState.rrule,
        resources = ApplicationProvider.getApplicationContext<Context>().resources,
    ).associate { it.scope to it.enabled }

    private fun pinPhoneZone(id: String) = TimeZone.setDefault(TimeZone.getTimeZone(id))

    private fun newYork(date: LocalDate, hour: Int, minute: Int): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), ZoneId.of(NEW_YORK)).toInstant().toEpochMilli()

    /** The [n]th (1-based) occurrence of the timed series. */
    private fun occurrence(n: Int): Long = newYork(LocalDate.of(2024, 3, 5).plusWeeks(n - 1L), 10, 0)

    /** The [n]th (1-based) occurrence of the all-day series, as stored (UTC midnight). */
    private fun allDayOccurrence(n: Int): Long =
        LocalDate.of(2024, 3, 5).plusWeeks(n - 1L).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private suspend fun seedTimedSeries(uid: String = "series@example.test"): Long = seed(
        Event(
            uid = uid, calendarId = calendarId, title = "Standup",
            location = "Room 4", description = "notes", reminders = listOf("-PT15M"), alarmCount = 1,
            categories = listOf("Team"), color = 0xFF0000FF.toInt(),
            startTs = occurrence(1), endTs = occurrence(1) + HOUR_MS, timezone = NEW_YORK,
            rrule = "FREQ=WEEKLY;COUNT=4", dtstamp = 1, createdAt = 1, updatedAt = 1,
        )
    )

    private suspend fun seedAllDaySeries(days: Int): Long {
        val start = allDayOccurrence(1)
        return seed(
            Event(
                uid = "all-day-$days@example.test", calendarId = calendarId, title = "Offsite",
                location = "Lodge", description = "notes", reminders = listOf("-PT15H"), alarmCount = 1,
                startTs = start, endTs = DateTimeUtils.utcMidnightToEndOfDay(start + (days - 1) * DAY_MS),
                isAllDay = true, timezone = null,
                rrule = "FREQ=WEEKLY;COUNT=4", dtstamp = 1, createdAt = 1, updatedAt = 1,
            )
        )
    }

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
        db.occurrencesDao().getForEvent(id).map { it.startTs }.sorted()

    private suspend fun operations(id: Long): List<String> =
        db.pendingOperationsDao().getForEvent(id).map { it.operation }

    /** The form as the event form loads a Room event opened on [occurrenceTs]. */
    private suspend fun openForm(id: Long, occurrenceTs: Long): EventFormState {
        val e = event(id)
        return EventFormState().withDateFields(e.toFormDateFields(occurrenceTs)).copy(
            title = e.title,
            selectedCalendarId = e.calendarId,
            isAllDay = e.isAllDay,
            timezone = e.timezone?.takeIf { TimezoneUtils.resolveZoneOrNull(it) != null },
            location = e.location.orEmpty(),
            description = e.description.orEmpty(),
            rrule = e.rrule,
            reminders = e.reminders.orEmpty().mapNotNull { parseIso8601DurationToMinutes(it) },
            editingEventId = id,
            isEditMode = true,
            editingOccurrenceTs = occurrenceTs,
            transp = e.transp,
            eventColor = e.color,
            categories = e.categories.orEmpty(),
        )
    }

    private suspend fun TestScope.saveAllEvents(form: EventFormState): Result<Event> {
        val result = viewModel.saveEvent(form, EditScope.ALL_EVENTS)
        advanceUntilIdle()
        return result
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
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}
