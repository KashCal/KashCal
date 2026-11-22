package org.onekash.kashcal.data.ics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Subscribes to and refreshes feeds with no events left to show, through the real
 * [OkHttpIcsFetcher] and [IcsSubscriptionRepository] over Room, with a MockWebServer as the feed.
 *
 * A calendar with no events, with only a VTIMEZONE (RFC 5545 §3.6 accepts any component), or
 * whose events are all CANCELLED (as a Nextcloud export of a calendar of cancelled events is) is
 * the feed's current state, so the refresh removes the stored events and their alarms. A blank
 * body, one that isn't a calendar, or one whose events can't be read fails the refresh and keeps
 * them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsEmptyFeedRefreshTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var db: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: IcsSubscriptionRepository

    private val header = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Feed//EN\r\n"

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = ReminderAlarmFixture(context)
        db = fixture.database
        val icsAccountId = runBlocking {
            db.accountsDao().insert(
                Account(provider = AccountProvider.ICS, email = IcsSubscription.ACCOUNT_EMAIL)
            )
        }
        val accountRepository = mockk<AccountRepository>()
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.ICS, IcsSubscription.ACCOUNT_EMAIL)
        } coAnswers { db.accountsDao().getById(icsAccountId) }
        repository = IcsSubscriptionRepository(
            database = db,
            icsSubscriptionsDao = db.icsSubscriptionsDao(),
            accountRepository = accountRepository,
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            occurrenceGenerator = OccurrenceGenerator(
                db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault()
            ),
            icsFetcher = OkHttpIcsFetcher(),
            reminderScheduler = fixture.scheduler,
            eventReader = fixture.eventReader,
            context = context,
        )
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        fixture.close()
    }

    /** Two events two and three days ahead; the first has a 15-minute VALARM. */
    private fun twoEventFeed(): String {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
        fun event(uid: String, at: Instant, alarm: Boolean) = buildString {
            append("BEGIN:VEVENT\r\nUID:$uid@example.test\r\nDTSTAMP:20261028T000000Z\r\n")
            append("DTSTART:${fmt.format(at)}\r\nDTEND:${fmt.format(at.plus(1, ChronoUnit.HOURS))}\r\n")
            append("SUMMARY:Feed $uid\r\n")
            if (alarm) append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Soon\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n")
            append("END:VEVENT\r\n")
        }
        return header + event("a", start, alarm = true) +
            event("b", start.plus(1, ChronoUnit.DAYS), alarm = false) + "END:VCALENDAR\r\n"
    }

    private val emptyCalendar get() = header + "END:VCALENDAR\r\n"

    private val timezoneOnlyCalendar get() = header +
        "BEGIN:VTIMEZONE\r\nTZID:Europe/Rome\r\n" +
        "BEGIN:STANDARD\r\nDTSTART:19701025T030000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0100\r\nEND:STANDARD\r\n" +
        "BEGIN:DAYLIGHT\r\nDTSTART:19700329T020000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\nEND:DAYLIGHT\r\n" +
        "END:VTIMEZONE\r\nEND:VCALENDAR\r\n"

    private suspend fun storedEvents(sub: IcsSubscription): List<Event> =
        db.eventsDao().getByCalendarIdAndCaldavUrlPrefix(sub.calendarId, IcsSubscription.eventSourcePrefix(sub.id))

    /** Subscribes to the two-event feed, served with validators, and checks what it stored. */
    private suspend fun subscribeToTwoEvents(): Pair<IcsSubscription, List<Event>> {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(twoEventFeed())
                .setHeader("ETag", "\"v1\"")
                .setHeader("Last-Modified", "Mon, 26 Oct 2026 10:00:00 GMT")
        )
        val added = repository.addSubscription(server.url("/feed.ics").toString(), "Feed", 0xFF3366CC.toInt())
        assertTrue("subscribing failed: $added", added is IcsSubscriptionRepository.SubscriptionResult.Success)
        val sub = (added as IcsSubscriptionRepository.SubscriptionResult.Success).subscription
        server.takeRequest()
        val events = storedEvents(sub)
        assertEquals("events stored by the first download", 2, events.size)
        val alarmed = events.single { it.title == "Feed a" }
        assertEquals(
            "the VALARM event has a pending reminder",
            1, db.scheduledRemindersDao().getPendingForEvent(alarmed.id).size
        )
        assertEquals("the reminder's alarm is set", 1, fixture.armedReminders().size)
        assertTrue("occurrences generated", db.occurrencesDao().getForEvents(events.map { it.id }).isNotEmpty())
        return sub to events
    }

    private fun assertConditionalRefresh() {
        val refresh = server.takeRequest()
        assertEquals("\"v1\"", refresh.getHeader("If-None-Match"))
        assertEquals("Mon, 26 Oct 2026 10:00:00 GMT", refresh.getHeader("If-Modified-Since"))
    }

    private fun assertFeedCleared(emptiedBody: String) = runBlocking {
        val (sub, seeded) = subscribeToTwoEvents()
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptiedBody).setHeader("ETag", "\"v2\""))

        val result = repository.refreshSubscription(sub.id)

        assertConditionalRefresh()
        assertEquals(
            IcsSubscriptionRepository.SyncResult.Success(
                IcsSubscriptionRepository.SyncCount(added = 0, updated = 0, deleted = 2)
            ),
            result
        )
        assertTrue("no feed events left", storedEvents(sub).isEmpty())
        assertTrue("no occurrences left", db.occurrencesDao().getForEvents(seeded.map { it.id }).isEmpty())
        assertTrue("no alarm left for the removed reminder", fixture.armedReminders().isEmpty())
        assertNull("no error on the row", db.icsSubscriptionsDao().getById(sub.id)!!.lastError)
    }

    private fun assertFeedKept(body: String, expectedError: String) = runBlocking {
        val (sub, seeded) = subscribeToTwoEvents()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = repository.refreshSubscription(sub.id)

        assertConditionalRefresh()
        assertEquals(IcsSubscriptionRepository.SyncResult.Error(expectedError), result)
        assertEquals("events kept", seeded.map { it.id }.toSet(), storedEvents(sub).map { it.id }.toSet())
        assertEquals("the reminder's alarm is kept", 1, fixture.armedReminders().size)
        assertEquals(expectedError, db.icsSubscriptionsDao().getById(sub.id)!!.lastError)
    }

    @Test
    fun `a feed emptied on the server clears its events and alarms on refresh`() =
        assertFeedCleared(emptyCalendar)

    @Test
    fun `a feed left with only a VTIMEZONE clears its events and alarms on refresh`() =
        assertFeedCleared(timezoneOnlyCalendar)

    @Test
    fun `a blank response fails the refresh and keeps the events`() =
        assertFeedKept("", "Empty response from server")

    @Test
    fun `a whitespace-only response fails the refresh and keeps the events`() =
        assertFeedKept(" \r\n\r\n ", "Empty response from server")

    @Test
    fun `an HTML page fails the refresh and keeps the events`() =
        assertFeedKept("<!DOCTYPE html><html><body>Sign in</body></html>", "Invalid ICS format")

    @Test
    fun `a JSON body fails the refresh and keeps the events`() =
        assertFeedKept("""{"events": []}""", "Invalid ICS format")

    @Test
    fun `a download cut off before END-VCALENDAR fails the refresh and keeps the events`() =
        assertFeedKept(header + "BEGIN:VEVENT\r\nUID:x@example.test\r\nDTSTART:20261110T100000Z\r\n", "Invalid ICS format")

    /** The two-event feed with each event marked STATUS:CANCELLED. */
    private fun cancelledFeed() = twoEventFeed().replace("SUMMARY:", "STATUS:CANCELLED\r\nSUMMARY:")

    @Test
    fun `a feed whose events were all cancelled clears its events and alarms on refresh`() =
        assertFeedCleared(cancelledFeed())

    @Test
    fun `subscribing to a feed of cancelled events succeeds with nothing stored`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(cancelledFeed()))

        val added = repository.addSubscription(server.url("/feed.ics").toString(), "Feed", 0xFF3366CC.toInt())

        assertTrue("subscribing failed: $added", added is IcsSubscriptionRepository.SubscriptionResult.Success)
        val sub = (added as IcsSubscriptionRepository.SubscriptionResult.Success).subscription
        assertEquals("the feed was downloaded", 1, server.requestCount)
        assertTrue("no feed events", storedEvents(sub).isEmpty())
        val row = db.icsSubscriptionsDao().getById(sub.id)!!
        assertNull("no error on the row", row.lastError)
        assertTrue("the refresh was recorded", row.lastSync > 0)
    }

    @Test
    fun `a feed whose live events can't be read fails even with a cancelled one`() =
        // Only the CANCELLED VEVENT parses: not an all-cancelled feed, so the events stay.
        assertFeedKept(
            header + "BEGIN:VEVENT\r\nUID:bad@example.test\r\nEND:VEVENT\r\n" +
                "BEGIN:VEVENT\r\nUID:gone@example.test\r\nDTSTAMP:20261028T000000Z\r\n" +
                "DTSTART:20261110T100000Z\r\nDTEND:20261110T110000Z\r\nSTATUS:CANCELLED\r\nEND:VEVENT\r\n" +
                "END:VCALENDAR\r\n",
            IcsFeedErrors.NO_EVENTS_PARSED
        )

    @Test
    fun `a feed whose events can't be read fails the refresh and keeps the events`() =
        // A VEVENT without DTSTART: the parser reads no event from a body that has VEVENTs.
        assertFeedKept(
            header + "BEGIN:VEVENT\r\nUID:bad@example.test\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
            IcsFeedErrors.NO_EVENTS_PARSED
        )
}
