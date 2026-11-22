package org.onekash.kashcal.domain.initializer

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.EventIcsSource
import org.onekash.kashcal.data.db.EventIcsStore
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.ics.IcsFetcher
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.widget.WidgetUpdateManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.TimeZone

/**
 * The one-time repair of series stored with no zone though their DTSTART is a UTC time, which
 * the mapper now stores as "UTC" ([ICalEventMapper.storedZoneOf]). Such a row expanded in the
 * device zone and moved an hour at each clock change. The repair reads the row's stored copy,
 * gives the row the zone "UTC", and regenerates its occurrences and reminders, offline: nothing
 * is queued and no other column changes. It also drops every feed's cached validators once,
 * since feed rows keep no stored copy and are fixed by their next download.
 *
 * Rows are seeded with no zone, as the mapper stored them until it kept "UTC", in
 * America/New_York (US clocks change on 14 Mar 2027).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UtcSeriesZoneRepairTest {

    private lateinit var db: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private lateinit var scheduler: ReminderScheduler
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private val widgets = mockk<WidgetUpdateManager>(relaxed = true)
    private var calendarId = 0L
    private val savedZone: TimeZone = TimeZone.getDefault()
    private val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context)
            .addCallback(KashCalDatabase.testCallback())
            .allowMainThreadQueries().build()
        generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        scheduler = ReminderScheduler(
            context = context,
            scheduledRemindersDao = db.scheduledRemindersDao(),
            eventReader = EventReader(db),
            channels = mockk(relaxed = true),
            attendeesDao = db.attendeesDao(),
            accountsDao = db.accountsDao(),
            calendarsDao = db.calendarsDao(),
            occurrencesDao = db.occurrencesDao(),
        )
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "repair_prefs_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        runTest {
            val accountId = db.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "user@example.test"))
            calendarId = db.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "https://caldav.example.test/cal/", displayName = "Cal", color = 0x123456)
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
        TimeZone.setDefault(savedZone)
    }

    private fun repair(
        eventsDao: org.onekash.kashcal.data.db.dao.EventsDao = db.eventsDao(),
        icsSource: EventIcsSource = icsStore,
    ) = UtcSeriesZoneRepair(
        database = db,
        eventsDao = eventsDao,
        icsSubscriptionsDao = db.icsSubscriptionsDao(),
        occurrenceGenerator = generator,
        reminderScheduler = scheduler,
        eventReader = EventReader(db),
        widgetUpdateManager = widgets,
        dataStore = dataStore,
        icsSource = icsSource,
    )

    private val icsStore by lazy { EventIcsStore(db) }

    // ---- fixtures ----

    private fun ics(uid: String, vararg vevents: List<String>): String = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN") +
            vevents.flatMap { listOf("BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261025T000000Z") + it + "END:VEVENT" } +
            listOf("END:VCALENDAR", "")
        ).joinToString("\r\n")

    private fun weekly(start: String = "20270308T150000Z", end: String = "20270308T160000Z", vararg extra: String) =
        listOf("DTSTART:$start", "DTEND:$end", "RRULE:FREQ=WEEKLY;COUNT=4", "SUMMARY:Weekly") + extra

    /**
     * Stores [body]'s first VEVENT as the earlier mapper stored it (no zone) with [copy] as its
     * stored ICS, and expands it.
     */
    private suspend fun seedAsBeforeFix(
        uid: String,
        body: String,
        copy: String? = body,
        edit: (Event) -> Event = { it },
    ): Event {
        val parsed = ICalParser().parseAllEvents(body).getOrNull()!!.first()
        val mapped = ICalEventMapper.toEntity(parsed, body, calendarId, "https://caldav.example.test/cal/$uid.ics", "\"e1\"").event
        val row = edit(mapped.copy(timezone = if (parsed.dtStart.isUtc) null else mapped.timezone, updatedAt = 1L, createdAt = 1L))
        val id = db.eventsDao().insert(row)
        icsStore.write(id, copy)
        val stored = db.eventsDao().getById(id)!!
        generator.regenerateOccurrences(stored)
        return stored
    }

    private suspend fun shown(id: Long): List<String> =
        db.occurrencesDao().getForEvent(id).filter { !it.isCancelled }.sortedBy { it.startTs }
            .map { utc.format(Instant.ofEpochMilli(it.startTs)) + if (it.exceptionEventId != null) "(changed)" else "" }

    private val weeklyAt1500 = listOf("20270308T150000Z", "20270315T150000Z", "20270322T150000Z", "20270329T150000Z")

    // ---- repaired ----

    @Test
    fun `a synced series stored with no zone gets the UTC zone and its 15-00Z occurrences`() = runTest {
        val before = seedAsBeforeFix("synced", ics("synced", weekly()))
        assertEquals("the earlier expansion followed the device zone", "20270315T140000Z", shown(before.id)[1])

        val outcome = repair().run()

        val after = db.eventsDao().getById(before.id)!!
        assertEquals(1, outcome.repaired)
        assertEquals(before.copy(timezone = "UTC"), after)
        assertEquals(weeklyAt1500, shown(before.id))
        assertTrue(db.pendingOperationsDao().getForEvent(before.id).isEmpty())
        coVerify(exactly = 1) { widgets.updateAllWidgets() }
    }

    @Test
    fun `an imported file's series is repaired the same way`() = runTest {
        val before = seedAsBeforeFix("imported", ics("imported", weekly())) {
            it.copy(caldavUrl = null, etag = null, importSource = Event.IMPORT_SOURCE_ICS_FILE)
        }

        repair().run()

        assertEquals(before.copy(timezone = "UTC"), db.eventsDao().getById(before.id))
        assertEquals(weeklyAt1500, shown(before.id))
    }

    @Test
    fun `reminders are re-armed on the repaired occurrences`() = runTest {
        // Starts tomorrow, so its reminders fall inside the scheduling window whatever the date.
        val first = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS).plus(15, ChronoUnit.HOURS)
        val body = ics("reminded", listOf(
            "DTSTART:${utc.format(first)}", "DTEND:${utc.format(first.plus(1, ChronoUnit.HOURS))}",
            "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:Daily",
            "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT15M", "END:VALARM",
        ))
        val before = seedAsBeforeFix("reminded", body)
        // A reminder armed at the zone-shifted time an hour off, as after a clock change.
        val stale = first.toEpochMilli() - 3_600_000
        db.scheduledRemindersDao().insert(ScheduledReminder(
            eventId = before.id, occurrenceTime = stale, triggerTime = stale - 900_000,
            reminderOffset = "-PT15M", eventTitle = "Daily", calendarColor = 0,
        ))

        repair().run()

        val armed = db.scheduledRemindersDao().getPendingForEvent(before.id)
        assertFalse("the stale reminder is gone", armed.any { it.occurrenceTime == stale })
        val occurrenceStarts = db.occurrencesDao().getForEvent(before.id).map { it.startTs }.toSet()
        assertTrue("reminders armed: $armed", armed.isNotEmpty())
        assertTrue("every reminder sits on a repaired occurrence", armed.all { it.occurrenceTime in occurrenceStarts })
        assertTrue(armed.all { it.triggerTime == it.occurrenceTime - 900_000 })
    }

    @Test
    fun `an occurrence deleted and one changed in an earlier build show what servers that expand the series show`() = runTest {
        // What an earlier build stored after deleting 15 Mar and renaming 22 Mar on a New York
        // phone: both at 14:00Z, the device-zone expansion after the clock change.
        val exdate = Instant.parse("2027-03-15T14:00:00Z").toEpochMilli()
        val changedAt = Instant.parse("2027-03-22T14:00:00Z").toEpochMilli()
        val body = ics("shifted", weekly(extra = arrayOf("EXDATE:20270315T140000Z")),
            listOf("RECURRENCE-ID:20270322T140000Z", "DTSTART:20270322T140000Z", "DTEND:20270322T150000Z", "SUMMARY:Changed"))
        val master = seedAsBeforeFix("shifted", body)
        val exceptionId = db.eventsDao().insert(Event(
            uid = master.uid, calendarId = calendarId, title = "Changed", startTs = changedAt, endTs = changedAt + 3_600_000,
            originalEventId = master.id, originalInstanceTime = changedAt,
            caldavUrl = master.caldavUrl, dtstamp = 1L, createdAt = 1L, updatedAt = 1L,
        ))
        val exception = db.eventsDao().getById(exceptionId)!!
        generator.linkException(master.id, changedAt, exception)
        assertEquals(listOf("20270308T150000Z", "20270322T140000Z(changed)", "20270329T140000Z"), shown(master.id))

        repair().run()

        // RFC 5545: the 14:00Z EXDATE and RECURRENCE-ID name no occurrence of the 15:00Z series.
        assertEquals(
            listOf("20270308T150000Z", "20270315T150000Z", "20270322T140000Z(changed)", "20270322T150000Z", "20270329T150000Z"),
            shown(master.id),
        )
        assertEquals("$exdate", db.eventsDao().getById(master.id)!!.exdate)
        assertEquals(exception, db.eventsDao().getById(exceptionId))
        assertTrue(db.pendingOperationsDao().getForEvent(master.id).isEmpty())
    }

    // ---- left alone ----

    @Test
    fun `rows whose stored copy has no UTC series start are left alone`() = runTest {
        val rows = listOf(
            // No zone, but a floating start in the copy.
            seedAsBeforeFix("floating", ics("floating", weekly(start = "20270308T150000", end = "20270308T160000"))) { it.copy(timezone = null) },
            // No stored copy.
            seedAsBeforeFix("nocopy", ics("nocopy", weekly()), copy = null),
            // A copy that doesn't parse.
            seedAsBeforeFix("garbage", ics("garbage", weekly()), copy = "not a calendar"),
            // A TZID start in the copy.
            seedAsBeforeFix("tzid", ics("tzid", weekly(start = "20270308T150000", end = "20270308T160000")).replace("DTSTART:", "DTSTART;TZID=Europe/Berlin:")) { it.copy(timezone = null) },
            // All-day.
            seedAsBeforeFix("allday", ics("allday", listOf("DTSTART;VALUE=DATE:20270308", "DTEND;VALUE=DATE:20270309", "RRULE:FREQ=WEEKLY;COUNT=4"))),
            // A one-off in UTC.
            seedAsBeforeFix("once", ics("once", listOf("DTSTART:20270308T150000Z", "DTEND:20270308T160000Z", "SUMMARY:Once"))),
            // Waiting to be deleted on the server.
            seedAsBeforeFix("deleting", ics("deleting", weekly())) { it.copy(syncStatus = SyncStatus.PENDING_DELETE) },
            // Already holding a zone.
            seedAsBeforeFix("zoned", ics("zoned", weekly())) { it.copy(timezone = "Asia/Tokyo") },
        )
        val occurrencesBefore = rows.associate { it.id to shown(it.id) }

        val outcome = repair().run()

        assertEquals(0, outcome.repaired)
        for (row in rows) {
            assertEquals(row.uid, row, db.eventsDao().getById(row.id))
            assertEquals(row.uid, occurrencesBefore[row.id], shown(row.id))
        }
        coVerify(exactly = 0) { widgets.updateAllWidgets() }
    }

    // ---- once ----

    @Test
    fun `runs once and a second pass changes nothing`() = runTest {
        val first = seedAsBeforeFix("first", ics("first", weekly()))
        repair().run()
        assertTrue(dataStore.getUtcSeriesZoneRepaired())

        // A row stored the old way after the pass is not touched by a later launch.
        val late = seedAsBeforeFix("late", ics("late", weekly()))
        val outcome = repair().run()
        assertEquals(0, outcome.repaired)
        assertNull(db.eventsDao().getById(late.id)!!.timezone)

        // With the flags cleared the pass is safe to repeat: the repaired row isn't touched again.
        val repairedRow = db.eventsDao().getById(first.id)!!
        dataStore.setUtcSeriesZoneRepaired(false)
        repair().run()
        assertEquals(repairedRow, db.eventsDao().getById(first.id))
    }

    @Test
    fun `a row that fails is retried on the next launch and the feed clear still runs once`() = runTest {
        // A legacy duplicate master (same UID and calendar) makes the master-uniqueness trigger
        // abort the row's update.
        val failing = seedAsBeforeFix("dup", ics("dup", weekly()))
        TestDatabases.raw(db).execSQL("DROP TRIGGER IF EXISTS trigger_master_event_unique_insert")
        db.eventsDao().insert(failing.copy(id = 0, caldavUrl = "https://caldav.example.test/cal/dup-2.ics", timezone = "Asia/Tokyo"))
        val feedId = db.icsSubscriptionsDao().insert(feed(etag = "\"v1\"", lastModified = "Sun, 25 Oct 2026 00:00:00 GMT"))

        val outcome = repair().run()

        assertEquals(1, outcome.failed)
        assertNull("rolled back", db.eventsDao().getById(failing.id)!!.timezone)
        assertFalse("retried next launch", dataStore.getUtcSeriesZoneRepaired())
        assertNull(db.icsSubscriptionsDao().getById(feedId)!!.etag)

        // Validators a refresh stores afterwards are kept by the retry.
        db.icsSubscriptionsDao().updateSyncSuccess(feedId, 2L, "\"v2\"", null)
        repair().run()
        assertEquals("\"v2\"", db.icsSubscriptionsDao().getById(feedId)!!.etag)
    }

    // ---- feeds ----

    private suspend fun feedCalendar(): Long {
        val accountId = db.accountsDao().insert(Account(provider = AccountProvider.ICS, email = IcsSubscription.ACCOUNT_EMAIL))
        return db.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = "ics://feed", displayName = "Feed", color = 0))
    }

    private var feedCalendarId = 0L

    private suspend fun feed(etag: String?, lastModified: String?): IcsSubscription {
        if (feedCalendarId == 0L) feedCalendarId = feedCalendar()
        return IcsSubscription(
            url = "https://feeds.example.test/a.ics", name = "Feed", color = 0, calendarId = feedCalendarId,
            etag = etag, lastModified = lastModified,
        )
    }

    /** Answers 304 to a conditional fetch, else the body; records each fetch's validators. */
    private class RecordingFetcher(val body: String) : IcsFetcher {
        val sent = mutableListOf<Pair<String?, String?>>()
        var onFetch: suspend () -> Unit = {}
        override suspend fun fetch(subscription: IcsSubscription): IcsFetcher.FetchResult {
            sent += subscription.etag to subscription.lastModified
            onFetch()
            return if (subscription.etag != null || subscription.lastModified != null) {
                IcsFetcher.FetchResult.NotModified
            } else {
                IcsFetcher.FetchResult.Success(body, "\"v2\"", null)
            }
        }
    }

    private fun feedRepository(fetcher: IcsFetcher) = IcsSubscriptionRepository(
        database = db,
        icsSubscriptionsDao = db.icsSubscriptionsDao(),
        accountRepository = mockk(relaxed = true),
        calendarsDao = db.calendarsDao(),
        eventsDao = db.eventsDao(),
        occurrenceGenerator = generator,
        icsFetcher = fetcher,
        reminderScheduler = scheduler,
        eventReader = EventReader(db),
        context = ApplicationProvider.getApplicationContext(),
    )

    @Test
    fun `feeds are re-read in full on their next refresh, and the repair starts none`() = runTest {
        val fetcher = RecordingFetcher(ics("feed-series", weekly()))
        val feedRepository = feedRepository(fetcher)
        val feedId = db.icsSubscriptionsDao().insert(feed(etag = "\"v1\"", lastModified = "Sun, 25 Oct 2026 00:00:00 GMT"))
        // The feed was downloaded with no zone kept for its UTC series.
        feedRepository.refreshSubscription(feedId)
        db.icsSubscriptionsDao().updateSyncSuccess(feedId, 1L, "\"v1\"", "Sun, 25 Oct 2026 00:00:00 GMT")
        fetcher.sent.clear()

        repair().run()

        assertTrue("the repair fetched nothing", fetcher.sent.isEmpty())
        val cleared = db.icsSubscriptionsDao().getById(feedId)!!
        assertNull(cleared.etag)
        assertNull(cleared.lastModified)

        feedRepository.refreshSubscription(feedId)

        assertEquals(listOf<Pair<String?, String?>>(null to null), fetcher.sent)
        val series = db.eventsDao().getByCalendarIdAndCaldavUrlPrefix(feedCalendarId, IcsSubscription.eventSourcePrefix(feedId)).single()
        assertEquals("UTC", series.timezone)
        assertEquals(weeklyAt1500, shown(series.id))
    }

    @Test
    fun `a refresh that ends after the clear stores no validators`() = runTest {
        val fetcher = RecordingFetcher(ics("feed-series", weekly()))
        val feedId = db.icsSubscriptionsDao().insert(feed(etag = null, lastModified = null))
        // Downloaded once: the feed now has events and the validator "v2".
        feedRepository(fetcher).refreshSubscription(feedId)
        assertEquals("\"v2\"", db.icsSubscriptionsDao().getById(feedId)!!.etag)
        // A refresh is in flight with that validator when the repair clears it.
        fetcher.onFetch = { repair().run() }

        feedRepository(fetcher).refreshSubscription(feedId)

        assertNull("the 304 doesn't write back the validators it was sent with", db.icsSubscriptionsDao().getById(feedId)!!.etag)
        fetcher.onFetch = {}
        feedRepository(fetcher).refreshSubscription(feedId)
        assertEquals(null to null, fetcher.sent.last())
    }

    // ---- robustness ----

    @Test
    fun `a row a write changes during the pass is counted as failed and retried`() = runTest {
        val row = seedAsBeforeFix("raced", ics("raced", weekly()))
        // A push stores the server's new copy between the read and the update.
        val racing = object : EventIcsSource by icsStore {
            var reads = 0
            override suspend fun read(event: Event): String? = icsStore.read(event).also {
                if (++reads == 1) icsStore.write(row.id, it + "\r\n")
            }
        }

        val outcome = repair(icsSource = racing).run()

        assertEquals(1, outcome.failed)
        assertNull(db.eventsDao().getById(row.id)!!.timezone)
        assertFalse(dataStore.getUtcSeriesZoneRepaired())
    }

    @Test
    fun `a regeneration that fails rolls the zone back and keeps the old occurrences`() = runTest {
        val row = seedAsBeforeFix("blocked", ics("blocked", weekly()))
        val before = shown(row.id)
        TestDatabases.raw(db).execSQL(
            "CREATE TRIGGER block_occurrences BEFORE INSERT ON occurrences WHEN NEW.event_id = ${row.id} " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END"
        )

        val outcome = repair().run()

        assertEquals(1, outcome.failed)
        assertNull(db.eventsDao().getById(row.id)!!.timezone)
        assertEquals(before, shown(row.id))
        assertFalse(dataStore.getUtcSeriesZoneRepaired())
    }

    @Test
    fun `a changed occurrence the server keyed at 15-00Z ends linked to the repaired occurrence`() = runTest {
        // The server's RECURRENCE-ID names 22 Mar 15:00Z; the occurrence was moved to 17:00Z.
        val original = Instant.parse("2027-03-22T15:00:00Z").toEpochMilli()
        val moved = Instant.parse("2027-03-22T17:00:00Z").toEpochMilli()
        val body = ics("relink", weekly(),
            listOf("RECURRENCE-ID:20270322T150000Z", "DTSTART:20270322T170000Z", "DTEND:20270322T180000Z", "SUMMARY:Moved"))
        val master = seedAsBeforeFix("relink", body)
        val exceptionId = db.eventsDao().insert(Event(
            uid = master.uid, calendarId = calendarId, title = "Moved", startTs = moved, endTs = moved + 3_600_000,
            originalEventId = master.id, originalInstanceTime = original,
            caldavUrl = master.caldavUrl, dtstamp = 1L, createdAt = 1L, updatedAt = 1L,
        ))
        // The earlier expansion had 22 Mar at 14:00Z, so the pull's link found no occurrence and
        // added one of its own.
        generator.linkException(master.id, original, db.eventsDao().getById(exceptionId)!!)
        val exceptionBefore = db.eventsDao().getById(exceptionId)

        repair().run()

        assertEquals(
            listOf("20270308T150000Z", "20270315T150000Z", "20270322T170000Z(changed)", "20270329T150000Z"),
            shown(master.id),
        )
        assertEquals(exceptionBefore, db.eventsDao().getById(exceptionId))
    }

    @Test
    fun `a changed occurrence row is left alone`() = runTest {
        val master = seedAsBeforeFix("exception-row", ics("exception-row", weekly()))
        val at = Instant.parse("2027-03-15T15:00:00Z").toEpochMilli()
        val exceptionId = db.eventsDao().insert(Event(
            uid = master.uid, calendarId = calendarId, title = "Changed", startTs = at, endTs = at + 3_600_000,
            originalEventId = master.id, originalInstanceTime = at,
            rrule = null, dtstamp = 1L, createdAt = 1L, updatedAt = 1L,
        ))
        val before = db.eventsDao().getById(exceptionId)

        repair().run()

        assertEquals(before, db.eventsDao().getById(exceptionId))
    }
}
