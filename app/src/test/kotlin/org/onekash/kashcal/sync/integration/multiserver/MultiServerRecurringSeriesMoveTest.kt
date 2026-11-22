package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import org.onekash.kashcal.data.db.EventIcsStore
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.IcalDavRRuleEngine
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.parseIso8601DurationToMinutes
import org.onekash.kashcal.ui.components.toFormDateFields
import org.onekash.kashcal.ui.components.withDateFields
import org.onekash.kashcal.ui.components.withTimedStart
import org.onekash.kashcal.ui.components.withZonePick
import org.onekash.kashcal.ui.components.pickers.ZonePick
import org.onekash.kashcal.ui.viewmodels.DeviceHomeViewModelTestFactory
import org.onekash.kashcal.ui.viewmodels.EditScope
import org.onekash.kashcal.ui.viewmodels.HomeViewModel
import org.onekash.kashcal.ui.viewmodels.dragSaveSettled
import org.onekash.kashcal.ui.viewmodels.endDragUndoWindow
import org.onekash.kashcal.util.TimezoneUtils
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.TimeZone
import java.util.UUID

/**
 * Live round trips, on every configured CalDAV server, for moving a recurring
 * series from the timeline and from the event form:
 *
 *   KashCal creates the series -> push -> pull (the server's copy) -> act
 *   through HomeViewModel (a drag with a scope, or a form save) -> push ->
 *   fetch the server's body -> pull into a fresh database -> check what every
 *   client would now show.
 *
 * Covers: a cross-day drag of the whole series or of its future moves every
 * occurrence and rewrites the repeat rule to the new weekday (RFC 5545 section
 * 3.8.5.3); a move the rule can't express is refused and nothing is pushed; an
 * "All events" form save from a later occurrence keeps the series' own dates.
 * A series with a renamed, a retimed and a cancelled occurrence and an end date
 * keeps them through each kind of move: the renamed one moves and stays linked
 * (RECURRENCE-ID re-keyed, RFC 5545 section 3.8.4.4), the retimed one keeps its
 * time, the cancelled one stays cancelled (EXDATE moved) and the last day stays
 * (UNTIL re-expressed, section 3.3.10). A split ends the old series' changed
 * occurrences after it, so in the "This and future" cases those take the new
 * series' slots while the cancelled one stays cancelled.
 *
 * The weekly series start on a Monday at 10:00 America/New_York and cross the
 * next US switch to standard time, so every weekly case also crosses daylight
 * saving. Each case creates its own event (unique UID), deletes every series it
 * created afterwards, skips a server that has no credentials or doesn't answer,
 * and redacts addresses from failure messages.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerRecurringSeriesMoveTest*'
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerRecurringSeriesMoveTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private const val NEW_YORK = "America/New_York"
        private val UID_PREFIX = "series-move-${System.currentTimeMillis()}-"
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val CHICAGO = "America/Chicago"
        private const val TITLE = "Series move"
        private const val RENAMED = "Renamed occurrence"
    }

    private val savedZone: TimeZone = TimeZone.getDefault()
    private lateinit var db: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private lateinit var coordinator: EventCoordinator
    private lateinit var reader: EventReader
    private lateinit var pull: PullStrategy
    private lateinit var push: PushStrategy
    private lateinit var viewModel: HomeViewModel
    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val createdUids = mutableListOf<String>()
    private var calendarUrl: String? = null

    /**
     * The Monday 13 days before the next US switch to standard time that is at least three
     * weeks out, so a weekly series from it crosses the switch.
     */
    private val firstMonday: LocalDate = run {
        var year = LocalDate.now().year
        var fallBack = LocalDate.of(year, 11, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.SUNDAY))
        if (fallBack.isBefore(LocalDate.now().plusDays(21))) {
            year += 1
            fallBack = LocalDate.of(year, 11, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.SUNDAY))
        }
        fallBack.minusDays(13)
    }

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(NEW_YORK))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        reader = EventReader(db)
        coordinator = EventCoordinator(
            eventWriter = EventWriter(db, generator),
            eventReader = reader,
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = accountRepository(db),
            // Side-effect collaborators only: the test pushes and pulls itself.
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = TestDataStoreFactory.createDefault(),
        )
        pull = pullStrategyFor(db)
        push = PushStrategy(
            calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
            eventsDao = db.eventsDao(),
            pendingOperationsDao = db.pendingOperationsDao(),
            accountRepository = accountRepository(db),
            attendeesDao = db.attendeesDao(),
            pendingCancelsDao = db.pendingCancelsDao(),
            icsSource = EventIcsStore(db),
        )
        val device = FakeCalendarProviderRepository()
        val factory = DeviceHomeViewModelTestFactory(eventCoordinator = coordinator, eventReader = reader)
        viewModel = factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), Dispatchers.IO)
        CalDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
    }

    @After
    fun tearDown() = runBlocking {
        client?.let { c ->
            for (e in createdUids.flatMap { db.eventsDao().getByUid(it) }.filter { it.originalEventId == null }.distinctBy { it.caldavUrl }) {
                val url = e.caldavUrl ?: continue
                // Stalwart stores an '@' in a resource name as %40 and answers a DELETE on the
                // raw '@' URL with 404, which the client counts as success; the split's new
                // series has an '@' UID.
                for (u in listOfNotNull(url, url.replace("@", "%40").takeIf { it != url })) {
                    try {
                        c.deleteEvent(u, c.fetchEtag(u).getOrNull() ?: e.etag ?: "")
                    } catch (_: Exception) { /* best effort */ }
                }
            }
        }
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
        TimeZone.setDefault(savedZone)
        unmockkAll()
    }

    // ---- a cross-day drag moves the series and its rule ----

    @Test
    fun `an all events drag to the next day moves the series on the server`() = runBlocking<Unit> {
        val (calendar, id) = seedSeries("FREQ=WEEKLY;BYDAY=MO;COUNT=4", "drag-all")
        val sequenceBefore = sequenceOf(serverBody(id))

        drag(id, occurrence(2), day(2).plusDays(1), 10, 0, EditScope.ALL_EVENTS)
        pushClean(calendar)

        val body = serverBody(id)
        assertTrue("${config.name}: rule moved to Tuesday: ${FixtureRedactor.redact(body)}", rruleOf(body).contains("BYDAY=TU"))
        // Zoho drops SEQUENCE from what it stores; where a server keeps it, the move must raise it.
        val sequenceAfter = sequenceOf(body)
        if (sequenceAfter != null) {
            assertTrue("${config.name}: SEQUENCE raised on the server copy", sequenceAfter > (sequenceBefore ?: 0))
        }
        assertEquals("${config.name}: every occurrence on Tuesday at 10:00", (1..4).map { at(day(it).plusDays(1), 10, 0) }, freshOccurrences(id))
    }

    @Test
    fun `an all events drag to another day and time keeps the last occurrence of an until series`() = runBlocking<Unit> {
        val until = Instant.ofEpochMilli(occurrence(4)).atZone(ZoneId.of("UTC"))
        val rule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=%04d%02d%02dT%02d%02d00Z".format(until.year, until.monthValue, until.dayOfMonth, until.hour, until.minute)
        val (calendar, id) = seedSeries(rule, "drag-until")

        drag(id, occurrence(2), day(2).plusDays(1), 11, 30, EditScope.ALL_EVENTS)
        pushClean(calendar)

        val expected = (1..4).map { at(day(it).plusDays(1), 11, 30) }
        if (config.name == "Xandikos") {
            // Xandikos can't parse a UTC UNTIL on a zoned start in its time-range filter and
            // leaves the series out of every range query, so a fresh pull never sees it.
            // Check the stored rule.
            val body = serverBody(id)
            assertEquals("${config.name}: ${FixtureRedactor.redact(body)}", expected, expandBody(body))
        } else {
            assertEquals("${config.name}: four Tuesdays at 11:30, none lost", expected, freshOccurrences(id))
        }
    }

    @Test
    fun `a this and future drag to the next day keeps the total on the server`() = runBlocking<Unit> {
        val (calendar, id) = seedSeries("FREQ=WEEKLY;BYDAY=MO;COUNT=6", "drag-future")

        drag(id, occurrence(3), day(3).plusDays(1), 10, 0, EditScope.THIS_AND_FUTURE)
        pushClean(calendar)

        val expected = listOf(occurrence(1), occurrence(2)) + (3..6).map { at(day(it).plusDays(1), 10, 0) }
        val got = settled(expected) { freshOccurrencesForPrefix() }
        if (got != expected) assertEquals("${config.name}: two Mondays then four Tuesdays; ${seriesOnServer()}", expected, got)
    }

    @Test
    fun `a drag the rule can't express is refused and pushes nothing`() = runBlocking<Unit> {
        assumeReady()
        val the29th = firstMonday.withDayOfMonth(29).let { if (it.isBefore(firstMonday)) it.plusMonths(1) else it }
        val start = at(the29th, 10, 0)
        val (_, id) = seedSeries("FREQ=MONTHLY;BYMONTHDAY=${the29th.dayOfMonth};COUNT=4", "refused", start)
        val etagBefore = client!!.fetchEtag(db.eventsDao().getById(id)!!.caldavUrl!!).getOrNull()

        drag(id, start, the29th.plusDays(2), 10, 0, EditScope.ALL_EVENTS)

        assertEquals("${config.name}: nothing queued", emptyList<String>(), db.pendingOperationsDao().getForEvent(id).map { it.operation })
        assertEquals("${config.name}: server copy untouched", etagBefore, client!!.fetchEtag(db.eventsDao().getById(id)!!.caldavUrl!!).getOrNull())
    }

    // ---- an All events form save from a later occurrence keeps the series' dates ----

    @Test
    fun `renaming from a later occurrence keeps the series' dates on the server`() = runBlocking<Unit> {
        val (calendar, id) = seedSeries("FREQ=WEEKLY;COUNT=4", "rename-later")

        assertTrue(viewModel.saveEvent(openForm(id, occurrence(3)).copy(title = "Renamed"), EditScope.ALL_EVENTS).isSuccess)
        pushClean(calendar)

        assertEquals("${config.name}: same four occurrences", (1..4).map { occurrence(it) }, freshOccurrences(id))
    }

    @Test
    fun `a new time from a later occurrence moves every occurrence across daylight saving`() = runBlocking<Unit> {
        val (calendar, id) = seedSeries("FREQ=WEEKLY;COUNT=4", "time-later")
        val opened = openForm(id, occurrence(3))

        assertTrue(viewModel.saveEvent(opened.withTimedStart(opened.dateMillis, 10, 30, defaultDurationMinutes = 60), EditScope.ALL_EVENTS).isSuccess)
        pushClean(calendar)

        assertEquals("${config.name}: every occurrence at 10:30 local", (1..4).map { at(day(it), 10, 30) }, freshOccurrences(id))
    }

    @Test
    fun `renaming from the first occurrence keeps the series' dates on the server`() = runBlocking<Unit> {
        val (calendar, id) = seedSeries("FREQ=WEEKLY;COUNT=4", "rename-first")

        assertTrue(viewModel.saveEvent(openForm(id, occurrence(1)).copy(title = "Renamed"), EditScope.ALL_EVENTS).isSuccess)
        pushClean(calendar)

        assertEquals("${config.name}: same four occurrences", (1..4).map { occurrence(it) }, freshOccurrences(id))
    }

    // ---- a series with changed occurrences keeps them through a move ----

    @Test
    fun `an all events form time change keeps the changed occurrences`() = runBlocking<Unit> {
        val (calendar, id) = seedChangedSeries("form-time")
        val opened = openForm(id, occurrence(2))

        saveForm(opened.withTimedStart(opened.dateMillis, 11, 0, defaultDurationMinutes = 60), EditScope.ALL_EVENTS)
        pushClean(calendar)

        assertSchedule(id, allEventsMoved(days = 0, hour = 11))
    }

    @Test
    fun `an all events same-day drag keeps the changed occurrences`() = runBlocking<Unit> {
        val (calendar, id) = seedChangedSeries("drag-time-all")

        drag(id, occurrence(2), day(2), 11, 0, EditScope.ALL_EVENTS)
        pushClean(calendar)

        assertSchedule(id, allEventsMoved(days = 0, hour = 11))
    }

    @Test
    fun `a this and future same-day drag keeps the cancelled occurrence`() = runBlocking<Unit> {
        val (calendar, id) = seedChangedSeries("drag-time-future")

        drag(id, occurrence(2), day(2), 11, 0, EditScope.THIS_AND_FUTURE)
        pushClean(calendar)

        assertSchedule(id, futureMoved(from = 2, days = 0, hour = 11))
    }

    @Test
    fun `an all events form date change from the first occurrence keeps the changed occurrences`() = runBlocking<Unit> {
        val (calendar, id) = seedChangedSeries("form-date-all")
        val opened = openForm(id, occurrence(1))

        saveForm(opened.withTimedStart(opened.dateMillis + DAY_MS, 10, 0, defaultDurationMinutes = 60), EditScope.ALL_EVENTS)
        pushClean(calendar)

        assertSchedule(id, allEventsMoved(days = 1, hour = 10))
    }

    @Test
    fun `a this and future form date change keeps the opened occurrence and the cancelled one`() = runBlocking<Unit> {
        val (calendar, id) = seedChangedSeries("form-date-future")
        val opened = openForm(id, occurrence(2))

        saveForm(opened.withTimedStart(opened.dateMillis + DAY_MS, 10, 0, defaultDurationMinutes = 60), EditScope.THIS_AND_FUTURE)
        pushClean(calendar)

        assertSchedule(id, futureMoved(from = 2, days = 1, hour = 10))
    }

    @Test
    fun `an all events zone change keeps the clock time and the changed occurrences`() = runBlocking<Unit> {
        val (calendar, id) = seedChangedSeries("form-zone")
        val opened = openForm(id, occurrence(1))

        saveForm(opened.withZonePick(ZonePick.Set(CHICAGO), NEW_YORK), EditScope.ALL_EVENTS)
        pushClean(calendar)

        assertSchedule(id, allEventsMoved(days = 0, hour = 10, zone = CHICAGO))
    }

    /**
     * Seeds a six-week Monday series ending with an UNTIL (a COUNT on Xandikos, which leaves a
     * zoned series with a UTC UNTIL out of range queries), renames occurrence 3, moves
     * occurrence 4 to 14:00 and cancels occurrence 5, pushes, and pulls the server's copy back.
     */
    private suspend fun seedChangedSeries(name: String): Pair<Calendar, Long> {
        val until = Instant.ofEpochMilli(occurrence(6)).atZone(ZoneId.of("UTC"))
        val rule = if (config.name == "Xandikos") {
            "FREQ=WEEKLY;BYDAY=MO;COUNT=6"
        } else {
            "FREQ=WEEKLY;BYDAY=MO;UNTIL=%04d%02d%02dT%02d%02d00Z".format(until.year, until.monthValue, until.dayOfMonth, until.hour, until.minute)
        }
        val (calendar, id) = seedSeries(rule, name)
        coordinator.editSingleOccurrence(id, occurrence(3)) { it.copy(title = RENAMED) }
        coordinator.editSingleOccurrence(id, occurrence(4)) { it.copy(startTs = at(day(4), 14, 0), endTs = at(day(4), 15, 0)) }
        coordinator.deleteSingleOccurrence(id, occurrence(5))
        pushClean(calendar)
        pull.pull(calendar, forceFullSync = true, client = client!!)
        val uid = db.eventsDao().getById(id)!!.uid
        return calendar to db.eventsDao().getByUid(uid).single { it.originalEventId == null }.id
    }

    /**
     * The schedule after the whole series moved by [days] to [hour]:00 in [zone]: the renamed
     * occurrence moves with it, the retimed one keeps 14:00 New York, the cancelled one stays
     * out, and the sixth (last) week stays.
     */
    private fun allEventsMoved(days: Long, hour: Int, zone: String = NEW_YORK): List<Pair<Long, String>> =
        (1..6).filter { it != 5 }.map { n ->
            when (n) {
                4 -> at(day(4), 14, 0) to TITLE
                3 -> at(day(3).plusDays(days), hour, 0, zone) to RENAMED
                else -> at(day(n).plusDays(days), hour, 0, zone) to TITLE
            }
        }.sortedBy { it.first }

    /**
     * The schedule after a split from occurrence [from] moved the rest by [days] to [hour]:00:
     * earlier ones as they were, the old series' changed occurrences after the split gone with
     * it (their slots now the new series'), the cancelled one still out. Zoho drops EXDATE from
     * a resource it creates, and the new series is a create, so there it shows again.
     */
    private fun futureMoved(from: Int, days: Long, hour: Int): List<Pair<Long, String>> =
        (1..6).filter { it != 5 || (config.name == "Zoho" && it >= from) }.map { n ->
            if (n < from) {
                occurrence(n) to (if (n == 3) RENAMED else TITLE)
            } else {
                at(day(n).plusDays(days), hour, 0) to TITLE
            }
        }.sortedBy { it.first }

    /**
     * Checks the server's copy of every series this case made, as a new client pulling the
     * calendar sees it: each occurrence's start and title, a changed occurrence shown at its
     * own time with its own title only when its RECURRENCE-ID matches an occurrence. Also
     * checks the series' SEQUENCE was raised where the server keeps one.
     */
    private suspend fun assertSchedule(id: Long, expected: List<Pair<Long, String>>) {
        val got = settledSchedule(expected)
        if (got != expected) {
            val bodies = createdUids.flatMap { db.eventsDao().getByUid(it) }.filter { it.originalEventId == null }
                .mapNotNull { it.caldavUrl }.distinct()
                .map { url -> client!!.fetchEvent(url).getOrNull()?.icalData?.let { FixtureRedactor.redact(it) } ?: "missing $url" }
                .joinToString("\n----\n")
            assertEquals("${config.name}: schedule after the move; server copies:\n$bodies", expected.describe(), got.describe())
        }
        val body = serverBody(id)
        val sequence = sequenceOf(body)
        if (sequence != null) assertTrue("${config.name}: SEQUENCE raised: ${FixtureRedactor.redact(body)}", sequence > 0)
    }

    private fun List<Pair<Long, String>>.describe(): String =
        joinToString("\n") { (ts, title) -> "${Instant.ofEpochMilli(ts).atZone(ZoneId.of(NEW_YORK))} $title" }

    private suspend fun settledSchedule(expected: List<Pair<Long, String>>): List<Pair<Long, String>> {
        val deadline = System.currentTimeMillis() + 60_000
        var got = freshSchedule()
        while (got != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            got = freshSchedule()
        }
        return got
    }

    /** Every occurrence of the series this case made, from a fresh pull, with its shown title. */
    private suspend fun freshSchedule(): List<Pair<Long, String>> {
        val uids = createdUids.toSet()
        val context: Context = ApplicationProvider.getApplicationContext()
        val fresh = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        try {
            val calendar = localCalendarFor(calendarUrl!!, fresh)
            pullStrategyFor(fresh).pull(calendar, forceFullSync = true, client = client!!)
            val masters = uids.flatMap { u -> fresh.eventsDao().getByUid(u).filter { it.originalEventId == null } }
            return masters.flatMap { m ->
                fresh.occurrencesDao().getForEvent(m.id).filter { !it.isCancelled }.map { o ->
                    o.startTs to (o.exceptionEventId?.let { fresh.eventsDao().getById(it)?.title } ?: m.title)
                }
            }.sortedBy { it.first }
        } finally {
            fresh.close()
        }
    }

    // ---- helpers ----

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
    }

    private fun day(n: Int): LocalDate = firstMonday.plusWeeks(n - 1L)
    private fun at(date: LocalDate, hour: Int, minute: Int, zone: String = NEW_YORK): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), ZoneId.of(zone)).toInstant().toEpochMilli()
    private fun occurrence(n: Int): Long = at(day(n), 10, 0)

    /** Creates the series through KashCal, pushes it, and pulls the server's copy back. */
    private suspend fun seedSeries(rule: String, name: String, start: Long = occurrence(1)): Pair<Calendar, Long> {
        assumeReady()
        val url = calendarUrl ?: discoverCalendar().also { calendarUrl = it }
        assumeTrue("No calendar found on ${config.name}", url != null)
        val calendar = localCalendarFor(url!!)
        val uid = "$UID_PREFIX${config.name.lowercase()}-$name-${UUID.randomUUID()}"
        createdUids += uid
        coordinator.createEvent(
            Event(uid = uid, calendarId = calendar.id, title = "Series move", startTs = start, endTs = start + HOUR_MS,
                timezone = NEW_YORK, rrule = rule, dtstamp = 1, createdAt = 1, updatedAt = 1),
            calendarId = calendar.id,
        )
        pushClean(calendar, expectCreated = true)
        pull.pull(calendar, forceFullSync = true, client = client!!)
        val master = db.eventsDao().getByUid(uid).single { it.originalEventId == null }
        return calendar to master.id
    }

    private suspend fun discoverCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) c.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return c.listCalendars(home).getOrNull()?.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }

    private suspend fun localCalendarFor(url: String, into: KashCalDatabase = db): Calendar {
        into.calendarsDao().getAllOnce().firstOrNull { it.caldavUrl == url }?.let { return it }
        val accountId = into.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "series-move@example.test"))
        val id = into.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = url, displayName = "Series move", color = 0))
        return into.calendarsDao().getById(id)!!
    }

    /** Drops the occurrence on the timeline and confirms [scope], waiting for the ViewModel. */
    private suspend fun drag(id: Long, occurrenceTs: Long, date: LocalDate, hour: Int, minute: Int, scope: EditScope) {
        val before = masterUids()
        val e = db.eventsDao().getById(id)!!
        val occurrence = db.occurrencesDao().getForEvent(id).single { it.startTs == occurrenceTs }
        viewModel.rescheduleEvent(DisplayEvent.Room(e, occurrence, null), date, hour * 60 + minute)
        waitFor("the drop's check") { viewModel.uiState.value.pendingDragReschedule?.blockedScopes != null }
        viewModel.confirmReschedule(scope)
        waitFor("the Undo snackbar") { viewModel.uiState.value.pendingSnackbarMessage != null }
        // Ends the Undo window so the move saves, then waits for the write itself: nothing
        // here collects the grid, so the drawn copy is never released by a refresh.
        viewModel.endDragUndoWindow()
        waitFor("the save") { viewModel.dragSaveSettled }
        // A split creates the future series under a new UID: track it for the checks and the
        // cleanup.
        createdUids += masterUids() - before
    }

    /** Saves the form with a scope and tracks a split's new series for the checks and cleanup. */
    private suspend fun saveForm(form: EventFormState, scope: EditScope) {
        val before = masterUids()
        assertTrue("${config.name}: form save", viewModel.saveEvent(form, scope).isSuccess)
        createdUids += masterUids() - before
    }

    private suspend fun masterUids(): Set<String> =
        db.calendarsDao().getAllOnce().flatMap { db.eventsDao().getAllMasterEventsForCalendar(it.id) }.map { it.uid }.toSet()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("${config.name}: timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    /** Builds the form the way the event form loads a Room event opened on [occurrenceTs]. */
    private suspend fun openForm(id: Long, occurrenceTs: Long): EventFormState {
        val e = db.eventsDao().getById(id)!!
        return EventFormState().withDateFields(e.toFormDateFields(occurrenceTs)).copy(
            title = e.title,
            selectedCalendarId = e.calendarId,
            isAllDay = e.isAllDay,
            timezone = e.timezone?.takeIf { TimezoneUtils.resolveZoneOrNull(it) != null },
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

    private suspend fun pushClean(calendar: Calendar, expectCreated: Boolean = false) {
        val result = push.pushForCalendar(calendar, client!!)
        assertTrue("${config.name}: push must succeed, got $result", result is PushResult.Success)
        val success = result as PushResult.Success
        assertTrue("${config.name}: push errors: ${success.pushErrors}", success.pushErrors.isEmpty())
        if (expectCreated) assertTrue("${config.name}: expected a create, got $success", success.eventsCreated >= 1)
    }

    /** Describes each series this case created, as the server holds it, for a failure message. */
    private suspend fun seriesOnServer(): String =
        createdUids.flatMap { db.eventsDao().getByUid(it) }.filter { it.originalEventId == null }.map { e ->
            val body = e.caldavUrl?.let { url -> client!!.fetchEvent(url).getOrNull()?.icalData?.replace(Regex("""\r?\n[ \t]"""), "") }
            val lines = body?.substringAfter("BEGIN:VEVENT")?.lines()?.filter { it.startsWith("UID") || it.startsWith("DTSTART") || it.startsWith("RRULE") }
            "${e.syncStatus} ${e.caldavUrl?.substringAfterLast('/')} -> ${lines?.let { FixtureRedactor.redact(it.joinToString(", ")) } ?: "not on server"}"
        }.joinToString(" | ")

    /**
     * Re-reads until the result equals [expected] or a minute passes: some servers (Zoho) list
     * a just-created resource a few seconds late.
     */
    private suspend fun settled(expected: List<Long>, read: suspend () -> List<Long>): List<Long> {
        val deadline = System.currentTimeMillis() + 60_000
        var got = read()
        while (got != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            got = read()
        }
        return got
    }

    /** Returns the occurrences the stored VEVENT's DTSTART and RRULE generate over 400 days. */
    private fun expandBody(body: String): List<Long> {
        val event = body.substringAfter("BEGIN:VEVENT").lines()
        val dtstart = event.first { it.startsWith("DTSTART") }
        val zone = ZoneId.of(dtstart.substringAfter("TZID=").substringBefore(':'))
        val start = LocalDateTime.parse(dtstart.substringAfterLast(':').trim(), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            .atZone(zone).toInstant().toEpochMilli()
        return IcalDavRRuleEngine.expandToTimestamps(
            rruleOf(body), start, start - 1_000, start + 400L * 86_400_000, zone.id, false, null, null,
        )
    }

    private suspend fun serverBody(id: Long): String {
        val url = db.eventsDao().getById(id)!!.caldavUrl!!
        return client!!.fetchEvent(url).getOrNull()!!.icalData.replace(Regex("""\r?\n[ \t]"""), "")
    }

    /** Returns the event's SEQUENCE, or null when the stored copy has none. */
    private fun sequenceOf(body: String): Int? =
        body.substringAfter("BEGIN:VEVENT").lines().firstOrNull { it.startsWith("SEQUENCE") }
            ?.substringAfter(':')?.trim()?.toIntOrNull()

    /** Returns the event's own RRULE; a VTIMEZONE's rules come first and don't count. */
    private fun rruleOf(body: String): String =
        body.substringAfter("BEGIN:VEVENT").lines().firstOrNull { it.startsWith("RRULE") }?.substringAfter(':')?.trim().orEmpty()

    /** Returns the occurrence starts of [id]'s series as a new client pulling the calendar sees. */
    private suspend fun freshOccurrences(id: Long): List<Long> {
        val uid = db.eventsDao().getById(id)!!.uid
        return freshPull { fresh -> fresh.eventsDao().getByUid(uid).filter { it.originalEventId == null } }
    }

    /**
     * Returns the occurrence starts of every series this case created (a split makes two), from
     * a fresh pull.
     */
    private suspend fun freshOccurrencesForPrefix(): List<Long> {
        val uids = createdUids.toSet()
        return freshPull { fresh -> uids.flatMap { u -> fresh.eventsDao().getByUid(u).filter { it.originalEventId == null } } }
    }

    private suspend fun freshPull(masters: suspend (KashCalDatabase) -> List<Event>): List<Long> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fresh = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        try {
            val calendar = localCalendarFor(calendarUrl!!, fresh)
            pullStrategyFor(fresh).pull(calendar, forceFullSync = true, client = client!!)
            return masters(fresh).flatMap { m -> fresh.occurrencesDao().getForEvent(m.id).filter { !it.isCancelled } }
                .map { it.startTs }.sorted()
        } finally {
            fresh.close()
        }
    }

    private fun pullStrategyFor(database: KashCalDatabase): PullStrategy {
        val dataStore = mockk<KashCalDataStore>(relaxed = true)
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        return PullStrategy(
            database = database,
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()),
            defaultQuirks = config.quirksFactory(creds?.serverUrl ?: config.defaultServerUrl ?: ""),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            icsSource = EventIcsStore(database),
        )
    }

    private fun accountRepository(database: KashCalDatabase): AccountRepository = AccountRepositoryImpl(
        accountsDao = database.accountsDao(),
        addressBookDao = database.addressBookDao(),
        calendarsDao = database.calendarsDao(),
        eventsDao = database.eventsDao(),
        pendingOperationsDao = database.pendingOperationsDao(),
        credentialManager = mockk(relaxed = true),
        reminderScheduler = mockk(relaxed = true),
        workManager = mockk(relaxed = true),
        contactSystemAccountRegistrar = mockk(relaxed = true),
        contactsProviderRepository = mockk(relaxed = true),
    )
}
