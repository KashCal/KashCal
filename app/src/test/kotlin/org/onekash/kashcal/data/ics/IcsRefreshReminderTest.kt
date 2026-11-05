package org.onekash.kashcal.data.ics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.HOUR
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.MINUTE
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.util.DateTimeUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests what refreshing a subscribed feed does to a reminder that is due, fired or snoozed
 * (#425), through the real [OkHttpIcsFetcher], [IcsSubscriptionRepository] and reminder scheduler
 * over Room, with a MockWebServer serving the same feed twice.
 *
 * The feed didn't change, so each reminder must come through the refresh as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsRefreshReminderTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var db: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: IcsSubscriptionRepository
    private lateinit var savedZone: TimeZone
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        // All-day occurrences start at UTC midnight; at UTC-12 today's 9 AM is still ahead until
        // 21:00 UTC.
        val context: Context = ApplicationProvider.getApplicationContext()
        // Before the zone change: Robolectric's AlarmManager shadow puts back, after every test,
        // the default zone it first saw.
        fixture = ReminderAlarmFixture(context)
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
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
        TimeZone.setDefault(savedZone)
    }

    private val todayUtc = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** A meeting in 10 minutes with a 15-minute VALARM, and today's all-day event at 9 AM. */
    private fun feed(): String {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
        val start = Instant.ofEpochMilli(now + 10 * MINUTE).truncatedTo(ChronoUnit.SECONDS)
        val today = Instant.ofEpochMilli(todayUtc)
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Feed//EN\r\n" +
            "BEGIN:VEVENT\r\nUID:meeting@example.test\r\nDTSTAMP:20261028T000000Z\r\n" +
            "DTSTART:${fmt.format(start)}\r\nDTEND:${fmt.format(start.plus(1, ChronoUnit.HOURS))}\r\n" +
            "SUMMARY:Meeting\r\n" +
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Soon\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n" +
            "END:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:holiday@example.test\r\nDTSTAMP:20261028T000000Z\r\n" +
            "DTSTART;VALUE=DATE:${day.format(today)}\r\nDTEND;VALUE=DATE:${day.format(today.plus(1, ChronoUnit.DAYS))}\r\n" +
            "SUMMARY:Holiday\r\n" +
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Today\r\nTRIGGER:PT9H\r\nEND:VALARM\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
    }

    private fun serveFeed() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(feed()))
    }

    /** Subscribes to [feed] and returns the subscription with the stored events by title. */
    private suspend fun subscribe(): Pair<IcsSubscription, Map<String, Event>> {
        serveFeed()
        val added = repository.addSubscription(server.url("/feed.ics").toString(), "Feed", -1)
        assertTrue("subscribing failed: $added", added is IcsSubscriptionRepository.SubscriptionResult.Success)
        val sub = (added as IcsSubscriptionRepository.SubscriptionResult.Success).subscription
        val events = db.eventsDao()
            .getByCalendarIdAndCaldavUrlPrefix(sub.calendarId, IcsSubscription.eventSourcePrefix(sub.id))
            .associateBy { it.title }
        assertEquals(setOf("Meeting", "Holiday"), events.keys)
        return sub to events
    }

    private suspend fun refreshUnchanged(sub: IcsSubscription) {
        serveFeed()
        val result = repository.refreshSubscription(sub.id)
        assertTrue("refresh failed: $result", result is IcsSubscriptionRepository.SyncResult.Success)
    }

    @Test
    fun `Snooze on a showing notification works after an unchanged feed refresh`() = runBlocking {
        val (sub, events) = subscribe()
        val meeting = events.getValue("Meeting")
        val rowId = fixture.insertReminderRow(
            meeting, meeting.startTs, "-PT15M", meeting.startTs - 15 * MINUTE, ReminderStatus.FIRED
        )

        refreshUnchanged(sub)
        fixture.scheduler.snoozeReminder(rowId, snoozeDurationMinutes = 15)

        assertNotNull("row behind the notification", fixture.scheduler.getReminder(rowId))
    }

    @Test
    fun `a snoozed reminder still alerts after an unchanged feed refresh`() = runBlocking {
        val (sub, events) = subscribe()
        val meeting = events.getValue("Meeting")
        val rowId = fixture.insertReminderRow(
            meeting, meeting.startTs, "-PT15M", meeting.startTs - 15 * MINUTE, ReminderStatus.FIRED
        )
        fixture.scheduler.snoozeReminder(rowId, snoozeDurationMinutes = 15)
        val snoozedAt = fixture.armedReminders().getValue(rowId)

        refreshUnchanged(sub)

        assertEquals(snoozedAt, fixture.armedReminders()[rowId])
    }

    @Test
    fun `today's all-day reminder still due survives an unchanged feed refresh`() = runBlocking {
        val trigger = DateTimeUtils.allDayReminderTriggerTime(todayUtc, 9 * HOUR)
        assumeTrue("9 AM still ahead at UTC-12", trigger > now + MINUTE)
        val (sub, events) = subscribe()
        val holiday = events.getValue("Holiday")
        // The row the subscription made.
        val rowId = checkNotNull(
            fixture.database.scheduledRemindersDao().findExisting(holiday.id, todayUtc, "PT9H")
        ).id

        refreshUnchanged(sub)

        assertEquals(mapOf(rowId to trigger), fixture.armedReminders())
    }

    @Test
    fun `subscribing to a feed arms today's all-day 9 AM reminder`() = runBlocking {
        val trigger = DateTimeUtils.allDayReminderTriggerTime(todayUtc, 9 * HOUR)
        assumeTrue("9 AM still ahead at UTC-12", trigger > now + MINUTE)

        subscribe()

        assertEquals(listOf(trigger), fixture.armedReminders().values.toList())
    }
}
