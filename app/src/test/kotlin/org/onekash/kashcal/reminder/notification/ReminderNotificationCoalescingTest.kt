package org.onekash.kashcal.reminder.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.receiver.ReminderAlarmReceiver
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * What the user sees when one occurrence has several reminders.
 *
 * An event with a 1-hour and a 15-minute reminder used to leave two
 * notifications side by side for the same meeting, so the user dismissed it
 * twice (#362). The later reminder now clears the earlier one's notification as
 * it posts, while still alerting normally.
 *
 * These drive the real notification manager rather than a mock, deliberately.
 * A notification's id is derived from its reminder row, and the post and cancel
 * paths compute it independently, so a mock-only test would happily pass while
 * the app cancelled an id it never posted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderNotificationCoalescingTest {

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var context: Context
    private lateinit var channels: ReminderNotificationChannels
    private lateinit var dataStore: KashCalDataStore
    private lateinit var manager: ReminderNotificationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var receiver: ReminderAlarmReceiver
    private lateinit var originalTimeZone: TimeZone
    private lateinit var originalLocale: Locale
    private lateinit var testDataStoreFile: File

    @Before
    fun setup() {
        originalTimeZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Locale.setDefault(Locale.US)

        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        context = RuntimeEnvironment.getApplication()
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channels = ReminderNotificationChannels(context)
        channels.createChannels()

        // A dedicated real scope (not TestScope) for DataStore: runTest catches
        // coroutines leaked by other classes in the same JVM fork, so a real
        // scope + runBlocking sidesteps that entirely.
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            testDataStoreFile
        }
        dataStore = KashCalDataStore(context, testPrefsDataStore)
        manager = ReminderNotificationManager(context, channels, dataStore)
        receiver = ReminderAlarmReceiver()
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
        unmockkAll()
        testDataStoreFile.delete()
    }

    private fun activeById(id: Int) =
        notificationManager.activeNotifications.firstOrNull { it.id == id }

    @Test
    fun `later reminder replaces the earlier one's notification for the same occurrence`() =
        runBlocking {
            // The 1-hour reminder already fired and its notification is showing.
            manager.showNotification(hourBefore)
            val earlierId = channels.getNotificationId(EARLIER_ID)
            assertNotNull("Earlier reminder should be showing to start with", activeById(earlierId))

            // Now the 15-minute reminder for the same occurrence fires.
            val scheduler = schedulerFor(quarterHourBefore, siblings = listOf(EARLIER_ID))
            receiver.handleAlarm(scheduler, manager, LATER_ID)

            val laterId = channels.getNotificationId(LATER_ID)
            assertNull(
                "Earlier reminder's notification should be gone, not stacked",
                activeById(earlierId)
            )
            assertNotNull("Later reminder must be showing", activeById(laterId))
            assertEquals(
                "Exactly one notification for the occurrence",
                1,
                notificationManager.activeNotifications.count { it.id == earlierId || it.id == laterId }
            )
        }

    @Test
    fun `replacing the notification does not silence the later reminder`() = runBlocking {
        // The whole point of #362 is that the second reminder still rings; it is
        // only the stale notification that goes away. Alert-once would suppress
        // the alert, so it must not be set.
        val scheduler = schedulerFor(quarterHourBefore, siblings = listOf(EARLIER_ID))
        receiver.handleAlarm(scheduler, manager, LATER_ID)

        val posted = activeById(channels.getNotificationId(LATER_ID))
        assertNotNull(posted)
        assertEquals(
            "Reminders must alert, so use the high-importance channel",
            ReminderNotificationChannels.CHANNEL_REMINDERS,
            posted!!.notification.channelId
        )
        assertTrue(
            "Reminder notifications must not be alert-once, or the later reminder goes silent",
            posted.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0
        )
    }

    @Test
    fun `a reminder on a different occurrence keeps its own notification`() = runBlocking {
        // Two occurrences of a recurring event are genuinely different meetings, so
        // collapsing them would hide one. Tomorrow's standup is already showing.
        val survivingId = manager.showNotification(tomorrowsHourBefore)

        // The lookup is scoped to one occurrence, so tomorrow's reminder is not in
        // today's sibling list (proven against real Room in ScheduledRemindersDaoTest).
        val scheduler = schedulerFor(quarterHourBefore, siblings = listOf(EARLIER_ID))
        receiver.handleAlarm(scheduler, manager, LATER_ID)

        assertNotNull(
            "The other occurrence's notification must survive",
            activeById(survivingId)
        )
        assertNotNull(activeById(channels.getNotificationId(LATER_ID)))
    }

    /**
     * A scheduler that reports [siblings] for [reminder] and accepts the
     * post-fire bookkeeping. Strict on purpose: an unexpected call fails.
     */
    private fun schedulerFor(reminder: ScheduledReminder, siblings: List<Long>): ReminderScheduler {
        val scheduler = mockk<ReminderScheduler>()
        coEvery { scheduler.getReminder(reminder.id) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns siblings
        coJustRun { scheduler.markAsFired(reminder.id) }
        return scheduler
    }

    private val hourBefore = ScheduledReminder(
        id = EARLIER_ID,
        eventId = EVENT_ID,
        occurrenceTime = OCCURRENCE_TIME,
        triggerTime = OCCURRENCE_TIME - 3_600_000,
        reminderOffset = "-PT1H",
        status = ReminderStatus.FIRED,
        eventTitle = "Team Standup",
        calendarColor = 0xFF2196F3.toInt()
    )

    private val quarterHourBefore = ScheduledReminder(
        id = LATER_ID,
        eventId = EVENT_ID,
        occurrenceTime = OCCURRENCE_TIME,
        triggerTime = OCCURRENCE_TIME - 900_000,
        reminderOffset = "-PT15M",
        status = ReminderStatus.PENDING,
        eventTitle = "Team Standup",
        calendarColor = 0xFF2196F3.toInt()
    )

    /** The same recurring event, next day: a different meeting. */
    private val tomorrowsHourBefore = hourBefore.copy(
        id = OTHER_OCCURRENCE_ID,
        occurrenceTime = OCCURRENCE_TIME + 86_400_000,
        triggerTime = OCCURRENCE_TIME + 86_400_000 - 3_600_000
    )

    private companion object {
        const val EVENT_ID = 100L
        const val EARLIER_ID = 7L
        const val LATER_ID = 8L
        const val OTHER_OCCURRENCE_ID = 9L
        const val OCCURRENCE_TIME = 1_800_000_000_000L
    }
}
