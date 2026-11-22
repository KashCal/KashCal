package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
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
import org.onekash.kashcal.data.db.EventIcsStore
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
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
import org.onekash.kashcal.ui.components.toStartEndTs
import org.onekash.kashcal.ui.components.toFormDateFields
import org.onekash.kashcal.ui.components.withDateFields
import org.onekash.kashcal.ui.components.withTimedStart
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
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.TimeZone
import java.util.UUID

/**
 * Live round trips, on every configured CalDAV server, for the edits users make most often:
 *
 *   KashCal creates the event -> push -> pull (the server's copy) -> act through
 *   HomeViewModel (a form save with the scope the sheet offers, a drag, a delete from the
 *   scope sheet or the form) -> push -> read the server's body -> pull into a fresh
 *   database -> check what a new client would show.
 *
 * Covers a one-off event edited in the form (title, date, time, location, notes) and
 * dragged (same day, another day); a series renamed from a later occurrence for this and
 * future; one occurrence renamed, moved to another time, moved to another day, dragged and
 * deleted; a changed occurrence edited again, dragged and deleted.
 *
 * Each server body is checked unfolded (RFC 5545 section 3.1): one resource per series
 * whose VEVENTs share the UID (RFC 4791 section 4.1), a RECURRENCE-ID per changed
 * occurrence naming its original start with DTSTART's value type (RFC 5545 section
 * 3.8.4.4), an EXDATE per deleted one (section 3.8.5.1), and a SEQUENCE raised by a new
 * time where the server keeps SEQUENCE (RFC 5546 section 2.1.4).
 *
 * The weekly series start on a Monday at 10:00 America/New_York at least two weeks out
 * and run five weeks; the time zone work is covered by [MultiServerRecurringSeriesMoveTest]
 * and [MultiServerSeriesTimesRoundTripTest]. Each case creates its own event (unique UID),
 * deletes every resource it created afterwards, skips a server that has no credentials or
 * doesn't answer, and redacts addresses from failure messages.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerEverydayEditsTest*'
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerEverydayEditsTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private const val NEW_YORK = "America/New_York"
        private val UID_PREFIX = "everyday-${System.currentTimeMillis()}-"
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val TITLE = "Everyday edit"
        private const val RENAMED = "Renamed occurrence"
        private const val RENAMED_AGAIN = "Renamed again"
    }

    /** What a client shows for one occurrence. */
    private data class Shown(val start: Long, val end: Long, val title: String) {
        override fun toString(): String =
            "${Instant.ofEpochMilli(start).atZone(ZoneId.of(NEW_YORK))}..${(end - start) / 60_000}min $title"
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

    /** A Monday at least two weeks out, with no US clock change in the five weeks after it. */
    private val firstMonday: LocalDate = run {
        val zone = ZoneId.of(NEW_YORK).rules
        var monday = LocalDate.now().plusDays(14).with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        while (zone.getOffset(monday.atStartOfDay().toInstant(ZoneOffset.UTC)) !=
            zone.getOffset(monday.plusWeeks(5).atStartOfDay().toInstant(ZoneOffset.UTC))
        ) {
            monday = monday.plusWeeks(1)
        }
        monday
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
                // raw '@' URL with 404, which the client counts as success; a split's new
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

    // ---- a one-off event ----

    @Test
    fun `a one-off event edited in the form keeps every edited field on the server`() = runBlocking<Unit> {
        val (calendar, id) = seed(rule = null, name = "one-off-form")
        val sequenceBefore = sequenceOf(vevents(serverBody(id)).single())
        val opened = openForm(id, null)
        val newDay = day(1).plusDays(2)

        saveForm(
            opened.withTimedStart(midnight(newDay), 14, 30, defaultDurationMinutes = 60).copy(
                title = "Edited one-off", location = "Room 4, Main St", description = "Bring the slides; agenda, notes",
            ),
            scope = null,
        )
        pushClean(calendar)

        val body = serverBody(id)
        val event = vevents(body).single()
        val redacted = FixtureRedactor.redact(body)
        assertEquals("${config.name}: SUMMARY: $redacted", "Edited one-off", unescape(value(event, "SUMMARY")))
        assertEquals("${config.name}: LOCATION: $redacted", "Room 4, Main St", unescape(value(event, "LOCATION")))
        assertEquals("${config.name}: DESCRIPTION: $redacted", "Bring the slides; agenda, notes", unescape(value(event, "DESCRIPTION")))
        assertEquals("${config.name}: DTSTART: $redacted", at(newDay, 14, 30), instantOf(line(event, "DTSTART")!!))
        assertEquals("${config.name}: no RRULE: $redacted", null, line(event, "RRULE"))
        assertSequenceRaised(event, sequenceBefore, redacted)

        val fresh = freshMasters().single()
        assertEquals("${config.name}: fresh pull", listOf("Edited one-off", "Room 4, Main St", "Bring the slides; agenda, notes"),
            listOf(fresh.title, fresh.location, fresh.description))
        assertSchedule(listOf(Shown(at(newDay, 14, 30), at(newDay, 15, 30), "Edited one-off")))
    }

    @Test
    fun `a one-off event dragged to a later time on the same day moves on the server`() = runBlocking<Unit> {
        val (calendar, id) = seed(rule = null, name = "one-off-drag-time")

        drag(id, occurrence(1), day(1), 15, 15, scope = null)
        pushClean(calendar)

        assertOneOffAt(id, at(day(1), 15, 15))
    }

    @Test
    fun `a one-off event dragged to another day moves on the server`() = runBlocking<Unit> {
        val (calendar, id) = seed(rule = null, name = "one-off-drag-day")

        drag(id, occurrence(1), day(1).plusDays(3), 9, 0, scope = null)
        pushClean(calendar)

        assertOneOffAt(id, at(day(1).plusDays(3), 9, 0))
    }

    // ---- a whole series ----

    @Test
    fun `renaming this and future from a later occurrence splits the series on the server`() = runBlocking<Unit> {
        val (calendar, id) = seed("FREQ=WEEKLY;COUNT=5", "rename-future")

        saveForm(openForm(id, occurrence(3)).copy(title = "Renamed from here"), EditScope.THIS_AND_FUTURE)
        pushClean(calendar)

        assertEquals("${config.name}: two series after the split", 2, createdUids.size)
        assertSchedule((1..5).map { n -> shown(n, if (n < 3) TITLE else "Renamed from here") })
    }

    // ---- one occurrence ----

    @Test
    fun `renaming one occurrence adds an override to the series resource`() = runBlocking<Unit> {
        val (calendar, id) = seed("FREQ=WEEKLY;COUNT=5", "this-rename")

        saveForm(openForm(id, occurrence(2)).copy(title = RENAMED), EditScope.THIS_EVENT)
        pushClean(calendar)

        assertOverrides(id, mapOf(occurrence(2) to occurrence(2)), exdates = emptySet())
        assertSchedule((1..5).map { n -> shown(n, if (n == 2) RENAMED else TITLE) })
    }

    @Test
    fun `moving one occurrence to another time keeps its original start as the recurrence id`() = runBlocking<Unit> {
        val (calendar, id) = seed("FREQ=WEEKLY;COUNT=5", "this-time")
        val opened = openForm(id, occurrence(3))

        saveForm(opened.withTimedStart(opened.dateMillis, 16, 0, defaultDurationMinutes = 60), EditScope.THIS_EVENT)
        pushClean(calendar)

        assertOverrides(id, mapOf(occurrence(3) to at(day(3), 16, 0)), exdates = emptySet(), sequenceAbove = 0)
        assertSchedule((1..5).map { n -> if (n == 3) Shown(at(day(3), 16, 0), at(day(3), 17, 0), TITLE) else shown(n) })
    }

    @Test
    fun `moving one occurrence to another day keeps its original start as the recurrence id`() = runBlocking<Unit> {
        val (calendar, id) = seed("FREQ=WEEKLY;COUNT=5", "this-day")
        val opened = openForm(id, occurrence(3))

        saveForm(opened.withTimedStart(opened.dateMillis + 2 * DAY_MS, 10, 0, defaultDurationMinutes = 60), EditScope.THIS_EVENT)
        pushClean(calendar)

        val moved = at(day(3).plusDays(2), 10, 0)
        assertOverrides(id, mapOf(occurrence(3) to moved), exdates = emptySet(), sequenceAbove = 0)
        assertSchedule((1..5).map { n -> if (n == 3) Shown(moved, moved + HOUR_MS, TITLE) else shown(n) })
    }

    @Test
    fun `dragging one occurrence to another day and time moves only it on the server`() = runBlocking<Unit> {
        val (calendar, id) = seed("FREQ=WEEKLY;COUNT=5", "this-drag")

        drag(id, occurrence(4), day(4).plusDays(1), 13, 30, EditScope.THIS_EVENT)
        pushClean(calendar)

        val moved = at(day(4).plusDays(1), 13, 30)
        assertOverrides(id, mapOf(occurrence(4) to moved), exdates = emptySet(), sequenceAbove = 0)
        assertSchedule((1..5).map { n -> if (n == 4) Shown(moved, moved + HOUR_MS, TITLE) else shown(n) })
    }

    @Test
    fun `deleting one occurrence from the scope sheet adds an EXDATE on the server`() = runBlocking<Unit> {
        val (calendar, id) = seed("FREQ=WEEKLY;COUNT=5", "this-delete")

        deleteFromSheet(id, occurrence(4), EditScope.THIS_EVENT)
        pushClean(calendar)

        assertOverrides(id, emptyMap(), exdates = setOf(occurrence(4)))
        assertSchedule((1..5).filter { it != 4 }.map { shown(it) })
    }

    // ---- a changed occurrence ----

    @Test
    fun `editing a changed occurrence again updates its override and adds no other`() = runBlocking<Unit> {
        val (calendar, id) = seedWithRenamedOccurrence("exception-reedit")
        val exception = exceptionFor(id, occurrence(2))
        val sequenceBefore = overrideSequence(id, occurrence(2)) ?: 0
        val opened = openForm(exception.id, exception.originalInstanceTime)
        assertEquals("${config.name}: the form opens at the changed occurrence's own start", occurrence(2), opened.toStartEndTs().first)

        saveForm(opened.withTimedStart(opened.dateMillis, 11, 30, defaultDurationMinutes = 60).copy(title = RENAMED_AGAIN), EditScope.THIS_EVENT)
        pushClean(calendar)

        val moved = at(day(2), 11, 30)
        assertOverrides(id, mapOf(occurrence(2) to moved), exdates = emptySet(), sequenceAbove = sequenceBefore)
        assertSchedule((1..5).map { n -> if (n == 2) Shown(moved, moved + HOUR_MS, RENAMED_AGAIN) else shown(n) })
    }

    @Test
    fun `dragging a changed occurrence to another day keeps its title and its recurrence id`() = runBlocking<Unit> {
        val (calendar, id) = seedWithRenamedOccurrence("exception-drag")
        // The rename raised the override's SEQUENCE already; the move must raise it again.
        val sequenceBefore = overrideSequence(id, occurrence(2)) ?: 0

        drag(id, occurrence(2), day(2).plusDays(1), 12, 0, scope = null)
        pushClean(calendar)

        val moved = at(day(2).plusDays(1), 12, 0)
        assertOverrides(id, mapOf(occurrence(2) to moved), exdates = emptySet(), sequenceAbove = sequenceBefore)
        assertSchedule((1..5).map { n -> if (n == 2) Shown(moved, moved + HOUR_MS, RENAMED) else shown(n) })
    }

    @Test
    fun `deleting a changed occurrence from its form removes the override and cancels the occurrence`() = runBlocking<Unit> {
        val (calendar, id) = seedWithRenamedOccurrence("exception-delete")
        val exception = exceptionFor(id, occurrence(2))

        assertTrue(
            "${config.name}: form delete",
            viewModel.handleRoomEventFormDelete(exception.id, exception.originalInstanceTime).isSuccess,
        )
        pushClean(calendar)

        assertOverrides(id, emptyMap(), exdates = setOf(occurrence(2)))
        assertSchedule((1..5).filter { it != 2 }.map { shown(it) })
    }

    @Test
    fun `deleting a changed occurrence from the quick view removes the override and cancels the occurrence`() = runBlocking<Unit> {
        val (calendar, id) = seedWithRenamedOccurrence("exception-delete-quick")
        val exception = exceptionFor(id, occurrence(2))

        viewModel.deleteSingleOccurrence(id, exception.originalInstanceTime!!)
        waitFor("the delete") { runBlocking { db.pendingOperationsDao().getForEvent(id).isNotEmpty() } }
        pushClean(calendar)

        assertOverrides(id, emptyMap(), exdates = setOf(occurrence(2)))
        assertSchedule((1..5).filter { it != 2 }.map { shown(it) })
    }

    // ---- checks ----

    private suspend fun assertOneOffAt(id: Long, start: Long) {
        val body = serverBody(id)
        val event = vevents(body).single()
        val redacted = FixtureRedactor.redact(body)
        assertEquals("${config.name}: DTSTART: $redacted", start, instantOf(line(event, "DTSTART")!!))
        assertEquals("${config.name}: one-hour length kept: $redacted", start + HOUR_MS, endOf(event))
        assertSequenceRaised(event, 0, redacted)
        assertSchedule(listOf(Shown(start, start + HOUR_MS, TITLE)))
    }

    /**
     * Checks the series resource holds the master and one override per entry of [overrides]
     * (original start to shown start), all under the master's UID, each RECURRENCE-ID a
     * DATE-TIME like DTSTART, and EXDATEs at exactly [exdates]. With [sequenceAbove], each
     * override's SEQUENCE must be above it where the server keeps SEQUENCE.
     */
    private suspend fun assertOverrides(id: Long, overrides: Map<Long, Long>, exdates: Set<Long>, sequenceAbove: Int? = null) {
        val body = serverBody(id)
        val redacted = FixtureRedactor.redact(body)
        val events = vevents(body)
        val uid = db.eventsDao().getById(id)!!.uid
        assertEquals("${config.name}: every VEVENT has the series UID: $redacted", events.map { uid }, events.map { value(it, "UID") })
        val master = events.single { line(it, "RECURRENCE-ID") == null }
        assertTrue("${config.name}: master keeps its RRULE: $redacted", line(master, "RRULE") != null)
        val masterStart = line(master, "DTSTART")!!
        val got = events.filter { line(it, "RECURRENCE-ID") != null }.associate { e ->
            val rid = line(e, "RECURRENCE-ID")!!
            assertEquals("${config.name}: RECURRENCE-ID value type matches DTSTART: $rid vs $masterStart", isDate(masterStart), isDate(rid))
            if (sequenceAbove != null) {
                sequenceOf(e)?.let { assertTrue("${config.name}: override SEQUENCE above $sequenceAbove: $redacted", it > sequenceAbove) }
            }
            instantOf(rid) to instantOf(line(e, "DTSTART")!!)
        }
        assertEquals("${config.name}: overrides (original -> start): $redacted", overrides.describe(), got.describe())
        val gotExdates = master.filter { it.startsWith("EXDATE") }.flatMap { l ->
            val params = l.substringBefore(':')
            l.substringAfter(':').split(',').map { instantOf("$params:$it") }
        }.toSet()
        assertEquals("${config.name}: EXDATEs: $redacted", exdates.map { describe(it) }.sorted(), gotExdates.map { describe(it) }.sorted())
    }

    private fun Map<Long, Long>.describe(): String = entries.sortedBy { it.key }.joinToString { "${describe(it.key)} -> ${describe(it.value)}" }
    private fun describe(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZoneId.of(NEW_YORK)).toString()

    private fun assertSequenceRaised(event: List<String>, before: Int?, redacted: String) {
        // Zoho drops SEQUENCE from what it stores; where a server keeps it, a new time raises it.
        val after = sequenceOf(event) ?: return
        assertTrue("${config.name}: SEQUENCE raised from ${before ?: 0} to $after: $redacted", after > (before ?: 0))
    }

    /**
     * Checks every occurrence of the series this case made, as a new client pulling the
     * calendar sees it, waiting up to a minute for servers that list a change late.
     */
    private suspend fun assertSchedule(expected: List<Shown>) {
        val sorted = expected.sortedBy { it.start }
        val deadline = System.currentTimeMillis() + 60_000
        var got = freshSchedule()
        while (got != sorted && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            got = freshSchedule()
        }
        if (got != sorted) {
            val bodies = createdUids.flatMap { db.eventsDao().getByUid(it) }.filter { it.originalEventId == null }
                .mapNotNull { it.caldavUrl }.distinct()
                .map { url -> client!!.fetchEvent(url).getOrNull()?.icalData?.let { FixtureRedactor.redact(unfold(it)) } ?: FixtureRedactor.redact("missing $url") }
                .joinToString("\n----\n")
            assertEquals("${config.name}: what a fresh pull shows; server copies:\n$bodies", sorted.joinToString("\n"), got.joinToString("\n"))
        }
    }

    private suspend fun freshSchedule(): List<Shown> = withFreshPull { fresh ->
        createdUids.toSet().flatMap { u -> fresh.eventsDao().getByUid(u).filter { it.originalEventId == null } }.flatMap { m ->
            fresh.occurrencesDao().getForEvent(m.id).filter { !it.isCancelled }.map { o ->
                Shown(o.startTs, o.endTs, o.exceptionEventId?.let { fresh.eventsDao().getById(it)?.title } ?: m.title)
            }
        }.sortedBy { it.start }
    }

    private suspend fun freshMasters(): List<Event> = withFreshPull { fresh ->
        createdUids.toSet().flatMap { u -> fresh.eventsDao().getByUid(u).filter { it.originalEventId == null } }
    }

    private suspend fun <T> withFreshPull(read: suspend (KashCalDatabase) -> T): T {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fresh = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        try {
            val calendar = localCalendarFor(calendarUrl!!, fresh)
            pullStrategyFor(fresh).pull(calendar, forceFullSync = true, client = client!!)
            return read(fresh)
        } finally {
            fresh.close()
        }
    }

    // ---- reading a body ----

    private fun unfold(body: String): String = body.replace(Regex("""\r?\n[ \t]"""), "")

    /** Returns each VEVENT's lines; VTIMEZONE lines are left out. */
    private fun vevents(body: String): List<List<String>> =
        body.split("BEGIN:VEVENT").drop(1).map { it.substringBefore("END:VEVENT").lines().map { l -> l.trimEnd('\r') }.filter { l -> l.isNotEmpty() } }

    private fun line(event: List<String>, name: String): String? =
        event.firstOrNull { it.startsWith("$name:") || it.startsWith("$name;") }

    private fun value(event: List<String>, name: String): String? = line(event, name)?.substringAfter(':')

    private fun unescape(text: String?): String? =
        text?.replace("\\n", "\n")?.replace("\\N", "\n")?.replace("\\,", ",")?.replace("\\;", ";")?.replace("\\\\", "\\")

    /** The SEQUENCE of [id]'s override for the original start [key] on the server, if any. */
    private suspend fun overrideSequence(id: Long, key: Long): Int? =
        vevents(serverBody(id)).firstOrNull { e -> line(e, "RECURRENCE-ID")?.let { instantOf(it) } == key }?.let { sequenceOf(it) }

    private fun sequenceOf(event: List<String>): Int? = value(event, "SEQUENCE")?.trim()?.toIntOrNull()

    private fun isDate(line: String): Boolean = line.contains("VALUE=DATE") && !line.contains("VALUE=DATE-TIME")

    /** Parses a DTSTART-like line (TZID, UTC or floating in New York) to epoch millis. */
    private fun instantOf(line: String): Long {
        val params = line.substringBefore(':')
        val text = line.substringAfter(':').trim()
        if (isDate(params) || text.length == 8) {
            return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val local = LocalDateTime.parse(text.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        val zone = when {
            text.endsWith("Z") -> ZoneOffset.UTC
            params.contains("TZID=") -> ZoneId.of(params.substringAfter("TZID=").substringBefore(';').trim('"'))
            else -> ZoneId.of(NEW_YORK)
        }
        return local.atZone(zone).toInstant().toEpochMilli()
    }

    private fun endOf(event: List<String>): Long? = line(event, "DTEND")?.let { instantOf(it) }

    // ---- acting ----

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
    }

    private fun day(n: Int): LocalDate = firstMonday.plusWeeks(n - 1L)
    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), ZoneId.of(NEW_YORK)).toInstant().toEpochMilli()
    private fun occurrence(n: Int): Long = at(day(n), 10, 0)
    private fun shown(n: Int, title: String = TITLE): Shown = Shown(occurrence(n), occurrence(n) + HOUR_MS, title)
    private fun midnight(date: LocalDate): Long = date.atStartOfDay(ZoneId.of(NEW_YORK)).toInstant().toEpochMilli()

    /** Creates the event through KashCal, pushes it, and pulls the server's copy back. */
    private suspend fun seed(rule: String?, name: String): Pair<Calendar, Long> {
        assumeReady()
        val url = calendarUrl ?: discoverCalendar().also { calendarUrl = it }
        assumeTrue("No calendar found on ${config.name}", url != null)
        val calendar = localCalendarFor(url!!)
        val uid = "$UID_PREFIX${config.name.lowercase()}-$name-${UUID.randomUUID()}"
        createdUids += uid
        val start = occurrence(1)
        coordinator.createEvent(
            Event(uid = uid, calendarId = calendar.id, title = TITLE, startTs = start, endTs = start + HOUR_MS,
                timezone = NEW_YORK, rrule = rule, dtstamp = 1, createdAt = 1, updatedAt = 1),
            calendarId = calendar.id,
        )
        pushClean(calendar, expectCreated = true)
        pull.pull(calendar, forceFullSync = true, client = client!!)
        return calendar to masterId(uid)
    }

    /** Seeds a five-week series, renames occurrence 2 through the form, pushes and pulls back. */
    private suspend fun seedWithRenamedOccurrence(name: String): Pair<Calendar, Long> {
        val (calendar, id) = seed("FREQ=WEEKLY;COUNT=5", name)
        saveForm(openForm(id, occurrence(2)).copy(title = RENAMED), EditScope.THIS_EVENT)
        pushClean(calendar)
        pull.pull(calendar, forceFullSync = true, client = client!!)
        val masterId = masterId(db.eventsDao().getById(id)?.uid ?: createdUids.last())
        assertOverrides(masterId, mapOf(occurrence(2) to occurrence(2)), exdates = emptySet())
        return calendar to masterId
    }

    private suspend fun masterId(uid: String): Long = db.eventsDao().getByUid(uid).single { it.originalEventId == null }.id

    private suspend fun exceptionFor(masterId: Long, originalStart: Long): Event =
        db.eventsDao().getByUid(db.eventsDao().getById(masterId)!!.uid)
            .single { it.originalEventId == masterId && it.originalInstanceTime == originalStart }

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
        val accountId = into.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "everyday@example.test"))
        val id = into.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = url, displayName = "Everyday", color = 0))
        return into.calendarsDao().getById(id)!!
    }

    /**
     * Drops the occurrence that starts at [occurrenceTs] on the timeline, the way the grid
     * shows it (a changed occurrence as its own row), confirms [scope] when the drop stages
     * the scope sheet, ends the Undo window and waits for the save.
     */
    private suspend fun drag(id: Long, occurrenceTs: Long, date: LocalDate, hour: Int, minute: Int, scope: EditScope?) {
        val before = masterUids()
        val master = db.eventsDao().getById(id)!!
        val occurrence = db.occurrencesDao().getForEvent(id).single { it.startTs == occurrenceTs }
        val shownEvent = occurrence.exceptionEventId?.let { db.eventsDao().getById(it) } ?: master
        viewModel.rescheduleEvent(DisplayEvent.Room(shownEvent, occurrence, null), date, hour * 60 + minute)
        if (scope != null) {
            waitFor("the drop's check") { viewModel.uiState.value.pendingDragReschedule?.blockedScopes != null }
            viewModel.confirmReschedule(scope)
        }
        waitFor("the Undo snackbar") { viewModel.uiState.value.pendingSnackbarMessage != null }
        viewModel.endDragUndoWindow()
        waitFor("the save") { viewModel.dragSaveSettled }
        createdUids += masterUids() - before
    }

    /** Opens the delete scope sheet on the occurrence and picks [scope], as the quick view does. */
    private suspend fun deleteFromSheet(id: Long, occurrenceTs: Long, scope: EditScope) {
        val master = db.eventsDao().getById(id)!!
        viewModel.requestDeleteRoom(master, occurrenceTs, master.startTs, isDetachedException = false, isAllDay = false)
        viewModel.confirmDelete(scope)
        waitFor("the delete") {
            runBlocking { db.pendingOperationsDao().getForEvent(id).isNotEmpty() }
        }
    }

    /** Saves the form with a scope and tracks a split's new series for the checks and cleanup. */
    private suspend fun saveForm(form: EventFormState, scope: EditScope?) {
        val before = masterUids()
        val result = viewModel.saveEvent(form, scope)
        assertTrue("${config.name}: form save: ${result.exceptionOrNull()}", result.isSuccess)
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

    /**
     * Builds the form the way the event form loads a Room event: [occurrenceTs] is null for a
     * one-off, the tapped occurrence for a series, the original start for a changed occurrence.
     */
    private suspend fun openForm(id: Long, occurrenceTs: Long?): EventFormState {
        val e = db.eventsDao().getById(id)!!
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
            editingZone = if (e.isAllDay) null else e.timezone ?: TimezoneUtils.getDeviceTimezone(),
            transp = e.transp,
            eventColor = e.color,
            categories = e.categories.orEmpty(),
        )
    }

    private suspend fun pushClean(calendar: Calendar, expectCreated: Boolean = false) {
        val result = push.pushForCalendar(calendar, client!!)
        assertTrue("${config.name}: push must succeed, got ${FixtureRedactor.redact(result.toString())}", result is PushResult.Success)
        val success = result as PushResult.Success
        assertTrue("${config.name}: push errors: ${FixtureRedactor.redact(success.pushErrors.toString())}", success.pushErrors.isEmpty())
        if (expectCreated) assertTrue("${config.name}: expected a create, got $success", success.eventsCreated >= 1)
        assertEquals("${config.name}: nothing left to push", 0, db.pendingOperationsDao().getAll().size)
    }

    private suspend fun serverBody(id: Long): String {
        val url = db.eventsDao().getById(id)!!.caldavUrl!!
        return unfold(client!!.fetchEvent(url).getOrNull()!!.icalData)
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
