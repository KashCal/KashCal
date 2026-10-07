package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Tests what each [SyncChange] from a real pull says changed, over a real in-memory Room
 * database: the change categories, the previous start, cancelled instances, the row key and the
 * first-pull and forced flags the Recent changes log reads. The ICS bodies follow what a real
 * iPhone wrote to iCloud: an override with RECURRENCE-ID in the user's zone against a series
 * DTSTART in UTC, a default VALARM and TRANSP added on first touch, a UTC EXDATE for a deleted
 * occurrence, and a series rename copied into the override.
 *
 * The first pull of each test is the calendar's first, so its changes carry isFirstPull; the
 * assertions read the second pull.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PullStrategyChangedFieldsTest {

    private lateinit var database: KashCalDatabase
    private lateinit var pullStrategy: PullStrategy
    private val client: CalDavClient = mockk()
    // Strict: the pull reads only the calendar's account, stubbed in setup.
    private val accountRepository: AccountRepository = mockk()

    private val account = Account(id = 1L, provider = AccountProvider.ICLOUD, email = "self@example.test")
    private val calendarId = 9L
    private val calendarUrl = "https://caldav.example.test/cal9/"
    private var pullCount = 0

    private val chicago: ZoneId = ZoneId.of("America/Chicago")
    private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val chicagoFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(chicago)

    private val hour = 3_600_000L
    private val dayMs = 24 * hour
    private val weekMs = 7 * dayMs
    // Three weeks out at 15:00 UTC, inside the pull's occurrence expansion window.
    private val seriesStart = ((System.currentTimeMillis() / dayMs) + 21) * dayMs + 15 * hour
    private val slot = seriesStart + weekMs

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.accountsDao().insert(account)
        database.calendarsDao().insert(
            Calendar(id = calendarId, accountId = account.id, caldavUrl = calendarUrl, displayName = "Work", color = 0)
        )
        val dataStore = TestDataStoreFactory.createDefault()
        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = OccurrenceGenerator(
                database, database.occurrencesDao(), database.eventsDao(), dataStore
            ),
            defaultQuirks = ICloudQuirks(),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = accountRepository,
            reminderScheduler = mockk(relaxed = true)
        )
        coEvery { accountRepository.getAccountById(account.id) } returns account
    }

    @After
    fun tearDown() = database.close()

    // ---- fixtures ----

    private fun utc(ms: Long) = utcFormat.format(Instant.ofEpochMilli(ms))
    private fun local(ms: Long) = chicagoFormat.format(Instant.ofEpochMilli(ms))
    private fun stamp() = "2026010${pullCount + 1}T000000Z"

    private fun vcal(vararg vevents: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n" +
            vevents.joinToString("") + "END:VCALENDAR\r\n"

    private fun vevent(uid: String, vararg lines: String) =
        "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:${stamp()}\r\n" +
            lines.joinToString("") { "$it\r\n" } + "END:VEVENT\r\n"

    private fun oneOff(
        start: Long = seriesStart,
        end: Long = seriesStart + hour,
        title: String = "Dentist",
        extra: List<String> = emptyList(),
    ) = vcal(vevent("one-uid",
        "DTSTART:${utc(start)}", "DTEND:${utc(end)}", "SUMMARY:$title", *extra.toTypedArray()))

    private fun seriesVevent(
        title: String = "Weekly sync",
        start: Long = seriesStart,
        extra: List<String> = emptyList(),
    ) = vevent("series-uid",
        "DTSTART:${utc(start)}", "DTEND:${utc(start + hour)}",
        "RRULE:FREQ=WEEKLY;COUNT=6", "SUMMARY:$title", *extra.toTypedArray())

    /** An override the way iOS writes one: RECURRENCE-ID in the user's zone, series in UTC. */
    private fun override(newStart: Long, title: String = "Weekly sync", location: String? = null) =
        vevent("series-uid", *listOfNotNull(
            "RECURRENCE-ID;TZID=America/Chicago:${local(slot)}",
            "DTSTART;TZID=America/Chicago:${local(newStart)}",
            "DTEND;TZID=America/Chicago:${local(newStart + hour)}",
            "SUMMARY:$title",
            location?.let { "LOCATION:$it" },
        ).toTypedArray())

    private suspend fun pullServing(
        vararg resources: Pair<String, String>,
        forceFullSync: Boolean = false,
    ): PullResult.Success {
        pullCount++
        val events = resources.map { (name, body) ->
            val href = "/cal9/$name"
            CalDavEvent(href, "https://caldav.example.test$href", "etag-$name-$pullCount", body)
        }
        coEvery { client.getCtag(calendarUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "ctag-$pullCount", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.error(501, "Not supported")
        coEvery { client.fetchAllEtags(calendarUrl) } returns
            CalDavResult.success(events.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(events.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns CalDavResult.success(events)
        val calendar = database.calendarsDao().getById(calendarId)!!
        val result = pullStrategy.pull(calendar, forceFullSync = forceFullSync, client = client)
        assertTrue("pull $pullCount must succeed, got $result", result is PullResult.Success)
        return result as PullResult.Success
    }

    private fun List<SyncChange>.single(instanceTs: Long): SyncChange =
        filter { it.instanceTs == instanceTs }.let {
            assertEquals("one change at instance $instanceTs: $this", 1, it.size)
            it.first()
        }

    // ---- one-off events ----

    @Test
    fun `a moved one-off carries its old start and the row key`() = runTest {
        pullServing("one.ics" to oneOff())

        val change = pullServing("one.ics" to oneOff(start = seriesStart + hour, end = seriesStart + 2 * hour))
            .changes.single()

        assertEquals(ChangeType.MODIFIED, change.type)
        assertEquals(setOf(ChangedField.TIME), change.changedFields)
        assertEquals(seriesStart, change.previousStartTs)
        assertEquals(false, change.previousIsAllDay)
        assertEquals(calendarId, change.calendarId)
        assertEquals("one-uid", change.eventUid)
        assertEquals(0L, change.instanceTs)
        assertEquals(seriesStart + 2 * hour, change.eventEndTs)
        assertFalse(change.isFirstPull)
        assertFalse(change.isForcedPull)
    }

    @Test
    fun `an end-only change is TIME with no previous start`() = runTest {
        pullServing("one.ics" to oneOff())

        val change = pullServing("one.ics" to oneOff(end = seriesStart + 2 * hour)).changes.single()

        assertEquals(setOf(ChangedField.TIME), change.changedFields)
        assertNull(change.previousStartTs)
    }

    @Test
    fun `a location change is LOCATION`() = runTest {
        pullServing("one.ics" to oneOff())

        val change = pullServing("one.ics" to oneOff(extra = listOf("LOCATION:Suite 4"))).changes.single()

        assertEquals(setOf(ChangedField.LOCATION), change.changedFields)
    }

    @Test
    fun `an added default alarm and TRANSP are still reported but name no field`() = runTest {
        pullServing("one.ics" to oneOff())

        val change = pullServing("one.ics" to oneOff(extra = listOf(
            "TRANSP:TRANSPARENT",
            "BEGIN:VALARM", "X-APPLE-DEFAULT-ALARM:TRUE", "ACTION:DISPLAY", "TRIGGER:-PT15M",
            "DESCRIPTION:Reminder", "END:VALARM"
        ))).changes.single()

        assertEquals("reminders still re-arm on an alarm change", ChangeType.MODIFIED, change.type)
        assertEquals(emptySet<ChangedField>(), change.changedFields)
        assertNull(change.previousStartTs)
    }

    @Test
    fun `an organizer schedule-status re-stamp names no field`() = runTest {
        val organizer = "ORGANIZER;CN=Boss:mailto:boss@example.test"
        pullServing("one.ics" to oneOff(extra = listOf(organizer)))

        val changes = pullServing("one.ics" to oneOff(extra = listOf(
            "ORGANIZER;CN=Boss;SCHEDULE-STATUS=1.2:mailto:boss@example.test"
        ))).changes

        assertTrue("no change, or a change naming no field: $changes",
            changes.all { it.changedFields.isEmpty() })
    }

    // ---- recurring events ----

    @Test
    fun `a first-time moved occurrence carries its slot as the previous start`() = runTest {
        pullServing("series.ics" to vcal(seriesVevent()))

        val changes = pullServing("series.ics" to vcal(seriesVevent(), override(slot + hour))).changes
        val occurrence = changes.single(slot)

        assertEquals("the pull still reports a first-time override as NEW", ChangeType.NEW, occurrence.type)
        assertEquals(setOf(ChangedField.TIME), occurrence.changedFields)
        assertEquals(slot, occurrence.previousStartTs)
        assertEquals(false, occurrence.previousIsAllDay)
        assertEquals("series-uid", occurrence.eventUid)
        assertFalse(occurrence.seriesIsPlaceholder)
        assertTrue("the unchanged series isn't reported: $changes", changes.none { it.instanceTs == 0L })
    }

    @Test
    fun `a first-time override with a new location but the same slot has no previous start`() = runTest {
        pullServing("series.ics" to vcal(seriesVevent()))

        val occurrence = pullServing("series.ics" to vcal(seriesVevent(), override(slot, location = "Lab")))
            .changes.single(slot)

        assertEquals(setOf(ChangedField.LOCATION), occurrence.changedFields)
        assertNull(occurrence.previousStartTs)
    }

    @Test
    fun `a first-time override that differs only by a locally kept series color names no field`() = runTest {
        pullServing("series.ics" to vcal(seriesVevent()))
        // A color set in KashCal on a server that sends no COLOR stays on the series row only.
        val series = database.eventsDao().getByUid("series-uid").single { it.originalEventId == null }
        database.eventsDao().upsert(series.copy(color = 0xFF00FF))

        val occurrence = pullServing("series.ics" to vcal(seriesVevent(), override(slot))).changes.single(slot)

        assertEquals(emptySet<ChangedField>(), occurrence.changedFields)
        assertNull(occurrence.previousStartTs)
    }

    @Test
    fun `an override edited again compares with its own stored row`() = runTest {
        pullServing("series.ics" to vcal(seriesVevent(), override(slot + hour)))

        val occurrence = pullServing("series.ics" to vcal(seriesVevent(), override(slot + hour, location = "Lab")))
            .changes.single(slot)

        assertEquals(ChangeType.MODIFIED, occurrence.type)
        assertEquals(setOf(ChangedField.LOCATION), occurrence.changedFields)
        assertNull(occurrence.previousStartTs)
    }

    @Test
    fun `a new EXDATE lists the cancelled instance and names no field`() = runTest {
        pullServing("series.ics" to vcal(seriesVevent()))

        val series = pullServing("series.ics" to vcal(seriesVevent(extra = listOf("EXDATE:${utc(slot)}"))))
            .changes.single(0)

        assertEquals(ChangeType.MODIFIED, series.type)
        assertEquals(listOf(slot), series.cancelledInstances)
        assertEquals(emptySet<ChangedField>(), series.changedFields)
        assertEquals(seriesStart + hour, series.eventEndTs)
    }

    @Test
    fun `a removed EXDATE is a RECURRENCE change`() = runTest {
        pullServing("series.ics" to vcal(seriesVevent(extra = listOf("EXDATE:${utc(slot)}"))))

        val series = pullServing("series.ics" to vcal(seriesVevent())).changes.single(0)

        assertEquals(setOf(ChangedField.RECURRENCE), series.changedFields)
        assertEquals(emptyList<Long>(), series.cancelledInstances)
    }

    @Test
    fun `a series moved an hour with its EXDATE rewritten cancels nothing`() = runTest {
        pullServing("series.ics" to vcal(seriesVevent(extra = listOf("EXDATE:${utc(slot)}"))))

        val series = pullServing("series.ics" to vcal(seriesVevent(
            start = seriesStart + hour, extra = listOf("EXDATE:${utc(slot + hour)}")
        ))).changes.single(0)

        assertEquals(setOf(ChangedField.TIME), series.changedFields)
        assertEquals(seriesStart, series.previousStartTs)
        assertEquals(emptyList<Long>(), series.cancelledInstances)
    }

    @Test
    fun `a series moved to the next weekday with its EXDATE rewritten cancels nothing`() = runTest {
        pullServing("series.ics" to vcal(seriesVevent(extra = listOf("EXDATE:${utc(slot)}"))))

        val series = pullServing("series.ics" to vcal(seriesVevent(
            start = seriesStart + dayMs, extra = listOf("EXDATE:${utc(slot + dayMs)}")
        ))).changes.single(0)

        assertEquals(setOf(ChangedField.TIME), series.changedFields)
        assertEquals(emptyList<Long>(), series.cancelledInstances)
    }

    @Test
    fun `a series rename copied into its override reports both with TITLE`() = runTest {
        pullServing("series.ics" to vcal(seriesVevent(), override(slot + hour)))

        val changes = pullServing("series.ics" to vcal(
            seriesVevent(title = "Weekly sync v2"), override(slot + hour, title = "Weekly sync v2")
        )).changes

        assertEquals(setOf(ChangedField.TITLE), changes.single(0).changedFields)
        assertEquals(setOf(ChangedField.TITLE), changes.single(slot).changedFields)
    }

    @Test
    fun `an override against a placeholder series is flagged, and the real series replacing it too`() = runTest {
        // The override arrives alone first (orphan), so the pull makes a placeholder series.
        val orphan = pullServing("series.ics" to vcal(override(slot + hour))).changes.single(slot)
        assertTrue(orphan.seriesIsPlaceholder)
        assertNull(orphan.previousStartTs)
        assertEquals(emptySet<ChangedField>(), orphan.changedFields)

        val changes = pullServing("series.ics" to vcal(seriesVevent(), override(slot + hour))).changes
        val series = changes.single(0)

        assertEquals("the pull's own type and counters don't change", ChangeType.MODIFIED, series.type)
        assertTrue(series.replacedPlaceholder)
        assertEquals(emptySet<ChangedField>(), series.changedFields)
        assertNull(series.previousStartTs)
    }

    // ---- flags ----

    @Test
    fun `the first pull is flagged and marks the calendar, the next is not`() = runTest {
        assertFalse(database.calendarsDao().getById(calendarId)!!.initialPullDone)

        val first = pullServing("one.ics" to oneOff())
        assertTrue(first.changes.isNotEmpty())
        assertTrue(first.changes.all { it.isFirstPull })
        assertTrue(database.calendarsDao().getById(calendarId)!!.initialPullDone)

        val second = pullServing("one.ics" to oneOff(title = "Dentist 2"))
        assertTrue(second.changes.isNotEmpty())
        assertTrue(second.changes.none { it.isFirstPull })
    }

    @Test
    fun `a failed first pull leaves the calendar unmarked`() = runTest {
        coEvery { client.getCtag(calendarUrl) } returns CalDavResult.error(500, "boom")
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.error(500, "boom")
        coEvery { client.fetchAllEtags(calendarUrl) } returns CalDavResult.error(500, "boom")
        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns CalDavResult.error(500, "boom")

        val result = pullStrategy.pull(database.calendarsDao().getById(calendarId)!!, client = client)

        assertTrue(result is PullResult.Error)
        assertFalse(database.calendarsDao().getById(calendarId)!!.initialPullDone)
    }

    @Test
    fun `a forced pull flags its changes`() = runTest {
        pullServing("one.ics" to oneOff())

        val forced = pullServing("one.ics" to oneOff(title = "Dentist 2"), forceFullSync = true)

        assertTrue(forced.changes.isNotEmpty())
        assertTrue(forced.changes.all { it.isForcedPull })
    }

    @Test
    fun `a deletion carries the row key and end`() = runTest {
        pullServing("one.ics" to oneOff(), "series.ics" to vcal(seriesVevent()))

        val deleted = pullServing("series.ics" to vcal(seriesVevent())).changes.single()

        assertEquals(ChangeType.DELETED, deleted.type)
        assertEquals("one-uid", deleted.eventUid)
        assertEquals(0L, deleted.instanceTs)
        assertEquals(calendarId, deleted.calendarId)
        assertEquals(seriesStart + hour, deleted.eventEndTs)
    }
}
