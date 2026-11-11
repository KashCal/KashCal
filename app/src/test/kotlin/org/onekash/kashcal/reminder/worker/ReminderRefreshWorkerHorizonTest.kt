package org.onekash.kashcal.reminder.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The daily reminder refresh arms the reminder of a repeating series whose stored rows ended
 * before today, without the app being opened: the run expands the series first. Real
 * [org.onekash.kashcal.reminder.scheduler.ReminderScheduler] and [OccurrenceGenerator] over an
 * in-memory database and Robolectric's AlarmManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderRefreshWorkerHorizonTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var generator: OccurrenceGenerator

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val week = 7 * day

    @Before
    fun setUp(): Unit = runBlocking {
        fixture = ReminderAlarmFixture(context)
        fixture.seedCalendar()
        generator = OccurrenceGenerator(
            fixture.database, fixture.database.occurrencesDao(), fixture.database.eventsDao(),
            TestDataStoreFactory.createWithSyncLookback(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS),
        )
        Unit
    }

    @After
    fun tearDown() = fixture.close()

    private fun worker(): ReminderRefreshWorker {
        val params: WorkerParameters = mockk(relaxed = true) { every { runAttemptCount } returns 0 }
        val dataStore: KashCalDataStore = mockk { coEvery { getReminderMigrationVersion() } returns 1 }
        // The real coordinator entry over the real generator; the pass touches none of the other
        // collaborators, and the alarm assertion shows it ran (a failure is logged, not thrown).
        val coordinator = EventCoordinator(
            eventWriter = mockk(), eventReader = fixture.eventReader, occurrenceGenerator = generator,
            localCalendarInitializer = mockk(), icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(), contactAnniversaryRepository = mockk(),
            accountRepository = mockk(), syncScheduler = mockk(), reminderScheduler = fixture.scheduler,
            widgetUpdateManager = mockk(), inviteNotifier = mockk(), icsRefreshScheduleReconciler = mockk(),
            dataStore = mockk(),
        )
        return ReminderRefreshWorker(context, params, fixture.scheduler, mockk { coJustRun { scheduleNextReminder() } }, dataStore, coordinator)
    }

    @Test
    fun `the daily refresh arms a stale series' reminder due this week`(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        // Weekly since 3 years ago; its stored rows ended a month ago.
        val dtstart = (now - 3 * 365 * day) / 1000 * 1000
        val seed = fixture.insertEvent("Standup", dtstart, reminders = listOf("-PT15M"))
        val series = seed.copy(rrule = "FREQ=WEEKLY", timezone = "UTC")
        fixture.database.eventsDao().update(series)
        generator.generateOccurrences(series, dtstart - 1000, now - 30 * day)
        val next = generateSequence(dtstart) { it + week }.first { it > now + hour }

        worker().doWork()

        val armedAt = fixture.reminderAlarms().map { it.second }
        assertTrue("an alarm 15 minutes before $next; armed: $armedAt", armedAt.any { kotlin.math.abs(it - (next - 15 * minute)) < minute })
    }

    @Test
    fun `control - the refresh arms the reminder when the stored rows cover this week`(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        val dtstart = (now - 3 * 365 * day) / 1000 * 1000
        val seed = fixture.insertEvent("Standup", dtstart, reminders = listOf("-PT15M"))
        val series = seed.copy(rrule = "FREQ=WEEKLY", timezone = "UTC")
        fixture.database.eventsDao().update(series)
        generator.generateOccurrences(series, dtstart - 1000, now + 30 * day)
        val next = generateSequence(dtstart) { it + week }.first { it > now + hour }

        worker().doWork()

        val armedAt = fixture.reminderAlarms().map { it.second }
        assertTrue("armed: $armedAt", armedAt.any { kotlin.math.abs(it - (next - 15 * minute)) < minute })
    }
}
