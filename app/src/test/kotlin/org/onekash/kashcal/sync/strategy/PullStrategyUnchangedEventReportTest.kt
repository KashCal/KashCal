package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.notification.InviteNotifier
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Tests that a pull reports an event only when its content changed, over a real in-memory Room
 * database. CalDAV gives one etag per resource, so editing one VEVENT of a series changes the
 * etag the pull sees for every VEVENT in it; the unchanged ones must not show as "updated".
 *
 * The comparison runs between the stored row and the freshly parsed event, so the stored side
 * has to come back through Room: comparing two parsed events (RoundTripPreservationTest) can't
 * see a difference Room's converters introduce. Each test pulls twice with a mocked client on
 * the full-listing path (the server reports no sync-token support), with a new ctag and new
 * etags on the second pull.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PullStrategyUnchangedEventReportTest {

    private lateinit var database: KashCalDatabase
    private lateinit var pullStrategy: PullStrategy
    private val client: CalDavClient = mockk()
    private val dataStore: KashCalDataStore = TestDataStoreFactory.createDefault()
    private val inviteNotifier: InviteNotifier = mockk(relaxed = true)
    private val accountRepository: AccountRepository = mockk(relaxed = true)
    private val reminderScheduler: ReminderScheduler = mockk(relaxed = true)

    private val account = Account(
        id = 1L,
        provider = AccountProvider.ICLOUD,
        email = "self@example.test",
        calendarUserAddresses = listOf("mailto:self@example.test")
    )
    private val calendarId = 9L
    private val calendarUrl = "https://caldav.example.test/cal9/"
    private var pullCount = 0

    private val chicago: ZoneId = ZoneId.of("America/Chicago")
    private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val chicagoFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(chicago)
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    // Three weeks out at 15:00 UTC, inside the pull's occurrence expansion window.
    private val dayMs = 86_400_000L
    private val weekMs = 7 * dayMs
    private val seriesStart = ((System.currentTimeMillis() / dayMs) + 21) * dayMs + 15 * 3_600_000L

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.accountsDao().insert(account)
        database.calendarsDao().insert(
            Calendar(
                id = calendarId,
                accountId = account.id,
                caldavUrl = calendarUrl,
                displayName = "Work",
                color = 0xFF0000,
                ctag = null,
                syncToken = null
            )
        )
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
            inviteNotifier = inviteNotifier,
            accountRepository = accountRepository,
            reminderScheduler = reminderScheduler
        )
        coEvery { accountRepository.getAccountById(account.id) } returns account
    }

    @After
    fun tearDown() = database.close()

    // ---- fixtures ----

    private fun utc(ms: Long) = utcFormat.format(Instant.ofEpochMilli(ms))
    private fun local(ms: Long) = chicagoFormat.format(Instant.ofEpochMilli(ms))

    private fun vcal(vararg vevents: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n" +
            vevents.joinToString("") + "END:VCALENDAR\r\n"

    /** Builds a VEVENT from property lines; DTSTAMP is set from [stamp]. */
    private fun vevent(uid: String, stamp: String, vararg lines: String) =
        "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:$stamp\r\n" +
            lines.joinToString("") { "$it\r\n" } + "END:VEVENT\r\n"

    private fun plainEvent(stamp: String, title: String = "Plain event", extra: List<String> = emptyList()) =
        vcal(vevent("plain-uid", stamp,
            "DTSTART:${utc(seriesStart)}", "DTEND:${utc(seriesStart + 3_600_000L)}",
            "SUMMARY:$title", *extra.toTypedArray()))

    private fun seriesVevent(uid: String, stamp: String, title: String = "Weekly sync") =
        vevent(uid, stamp,
            "DTSTART:${utc(seriesStart)}", "DTEND:${utc(seriesStart + 3_600_000L)}",
            "RRULE:FREQ=WEEKLY;COUNT=6", "SEQUENCE:0", "SUMMARY:$title")

    /** An override the way iOS writes one: RECURRENCE-ID in the user's zone, series in UTC. */
    private fun movedOccurrence(uid: String, stamp: String, slot: Long, newStart: Long) =
        vevent(uid, stamp,
            "RECURRENCE-ID;TZID=America/Chicago:${local(slot)}",
            "DTSTART;TZID=America/Chicago:${local(newStart)}",
            "DTEND;TZID=America/Chicago:${local(newStart + 3_600_000L)}",
            "SEQUENCE:0", "SUMMARY:Weekly sync")

    /**
     * Serves [resources] (href to body) for the next pull, with etags and a ctag unique to it,
     * then runs the pull against the calendar row as Room holds it now.
     */
    private suspend fun pullServing(vararg resources: Pair<String, String>): PullResult.Success {
        pullCount++
        // Absolute-path hrefs, as servers list them, so the deletion check matches stored URLs.
        val events = resources.map { (name, body) ->
            val href = "/cal9/$name"
            CalDavEvent(href, "https://caldav.example.test$href", "etag-$name-$pullCount", body)
        }
        coEvery { client.getCtag(calendarUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "ctag-$pullCount", displayName = null, color = null, isReadOnly = null)
        )
        // No sync-token support keeps both pulls on the full-listing path.
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.error(501, "Not supported")
        coEvery { client.fetchAllEtags(calendarUrl) } returns
            CalDavResult.success(events.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(events.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns CalDavResult.success(events)
        val calendar = database.calendarsDao().getById(calendarId)!!
        val result = pullStrategy.pull(calendar, client = client)
        assertTrue("pull $pullCount must succeed, got $result", result is PullResult.Success)
        return result as PullResult.Success
    }

    private suspend fun seriesRowId(uid: String): Long =
        database.eventsDao().getByUid(uid).single { it.originalEventId == null }.id

    // ---- unchanged content is not reported ----

    @Test
    fun `an unchanged event re-served with a new etag is not reported`() = runTest {
        pullServing("plain.ics" to plainEvent("20260101T000000Z"))

        val second = pullServing("plain.ics" to plainEvent("20260102T000000Z"))

        assertEquals("unchanged event must raise no change: ${second.changes}", emptyList<Any>(), second.changes)
        assertEquals(0, second.eventsUpdated)
    }

    @Test
    fun `an unchanged event with reminders, categories, extra properties and common fields set is not reported`() = runTest {
        val start = seriesStart
        fun rich(stamp: String) = vcal(vevent("rich-uid", stamp,
            "DTSTART;TZID=America/Chicago:${local(start)}",
            "DTEND;TZID=America/Chicago:${local(start + 3_600_000L)}",
            "RRULE:FREQ=WEEKLY;COUNT=4",
            "EXDATE;TZID=America/Chicago:${local(start + 2 * weekMs)}",
            "SUMMARY:Planning",
            "DESCRIPTION:Agenda in the doc",
            "LOCATION:Room 4",
            "URL:https://example.test/meeting",
            "GEO:41.88;-87.63",
            "PRIORITY:5",
            "STATUS:CONFIRMED",
            "TRANSP:OPAQUE",
            "CLASS:PUBLIC",
            "COLOR:red",
            "CATEGORIES:Work,Planning",
            "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC",
            "SEQUENCE:2",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "DESCRIPTION:Reminder", "END:VALARM"))
        pullServing("rich.ics" to rich("20260101T000000Z"))

        val second = pullServing("rich.ics" to rich("20260102T000000Z"))

        assertEquals("unchanged rich event must raise no change: ${second.changes}", emptyList<Any>(), second.changes)
    }

    @Test
    fun `an unchanged all-day event re-served with a new etag is not reported`() = runTest {
        val day = dateFormat.format(Instant.ofEpochMilli(seriesStart))
        val next = dateFormat.format(Instant.ofEpochMilli(seriesStart + dayMs))
        fun allDay(stamp: String) = vcal(vevent("allday-uid", stamp,
            "DTSTART;VALUE=DATE:$day", "DTEND;VALUE=DATE:$next", "SUMMARY:Holiday"))
        pullServing("allday.ics" to allDay("20260101T000000Z"))

        val second = pullServing("allday.ics" to allDay("20260102T000000Z"))

        assertEquals("unchanged all-day event must raise no change: ${second.changes}", emptyList<Any>(), second.changes)
    }

    @Test
    fun `an unchanged series and its moved occurrence re-served with new stamps are not reported`() = runTest {
        val slot = seriesStart + weekMs
        fun body(stamp: String) = vcal(
            seriesVevent("series-uid", stamp),
            movedOccurrence("series-uid", stamp, slot, slot + 3_600_000L),
        )
        pullServing("series.ics" to body("20260101T000000Z"))

        val second = pullServing("series.ics" to body("20260102T000000Z"))

        assertEquals("neither the series nor its occurrence changed: ${second.changes}", emptyList<Any>(), second.changes)
        assertEquals(0, second.eventsUpdated)
    }

    @Test
    fun `moving one occurrence reports that occurrence and not the series`() = runTest {
        val slot = seriesStart + weekMs
        pullServing("series.ics" to vcal(seriesVevent("series-uid", "20260101T000000Z")))
        val seriesId = seriesRowId("series-uid")

        val second = pullServing("series.ics" to vcal(
            seriesVevent("series-uid", "20260102T000000Z"),
            movedOccurrence("series-uid", "20260102T000000Z", slot, slot + 3_600_000L),
        ))

        val occurrence = database.eventsDao().getByUid("series-uid").single { it.originalEventId != null }
        assertEquals("only the moved occurrence is reported: ${second.changes}", 1, second.changes.size)
        assertEquals(occurrence.id, second.changes.single().eventId)
        assertTrue("the series row must not be reported", second.changes.none { it.eventId == seriesId })
        // A series reminder armed for the old slot is suppressed when it comes due:
        // ReminderScheduler.hasLiveOccurrenceForReminder looks the slot up with this query.
        assertNull(
            "no live series occurrence remains at the old slot",
            database.occurrencesDao().getOccurrenceNearTime(seriesId, slot)
        )
    }

    // ---- real changes are still reported ----

    @Test
    fun `a title change is reported`() = runTest {
        pullServing("plain.ics" to plainEvent("20260101T000000Z"))

        val second = pullServing("plain.ics" to plainEvent("20260102T000000Z", title = "Renamed"))

        assertEquals(ChangeType.MODIFIED, second.changes.single().type)
    }

    @Test
    fun `an alarm added to an event that had none is reported`() = runTest {
        pullServing("plain.ics" to plainEvent("20260101T000000Z"))

        val second = pullServing("plain.ics" to plainEvent("20260102T000000Z",
            extra = listOf("BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT10M", "DESCRIPTION:Reminder", "END:VALARM")))

        assertEquals(ChangeType.MODIFIED, second.changes.single().type)
    }

    @Test
    fun `categories removed from an event are reported`() = runTest {
        pullServing("plain.ics" to plainEvent("20260101T000000Z", extra = listOf("CATEGORIES:Work")))

        val second = pullServing("plain.ics" to plainEvent("20260102T000000Z"))

        assertEquals(ChangeType.MODIFIED, second.changes.single().type)
    }

    @Test
    fun `an extra property removed from an event is reported`() = runTest {
        pullServing("plain.ics" to plainEvent("20260101T000000Z",
            extra = listOf("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC")))
        val stored = database.eventsDao().getByUid("plain-uid").single()
        assertNotNull("fixture must store the extra property", stored.extraProperties?.takeIf { it.isNotEmpty() })

        val second = pullServing("plain.ics" to plainEvent("20260102T000000Z"))

        assertEquals(ChangeType.MODIFIED, second.changes.single().type)
    }
}
