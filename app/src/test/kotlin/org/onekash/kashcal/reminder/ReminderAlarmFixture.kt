package org.onekash.kashcal.reminder

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import io.mockk.mockk
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels
import org.onekash.kashcal.reminder.receiver.ReminderAlarmReceiver
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * A real [ReminderScheduler] over an in-memory database and Robolectric's
 * AlarmManager, so a test can seed events, occurrences and reminder rows and
 * then read back exactly which reminder alarms are set and when.
 *
 * Call [close] in `@After`.
 */
class ReminderAlarmFixture(private val context: Context) {

    val database: KashCalDatabase = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    val eventReader = EventReader(database)

    val scheduler = ReminderScheduler(
        context = context,
        scheduledRemindersDao = database.scheduledRemindersDao(),
        eventReader = eventReader,
        // Only used to clear notifications on dismiss, a side effect
        channels = mockk<ReminderNotificationChannels>(relaxed = true),
        attendeesDao = database.attendeesDao(),
        accountsDao = database.accountsDao(),
        calendarsDao = database.calendarsDao(),
        occurrencesDao = database.occurrencesDao()
    )

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    var calendarId: Long = 0
        private set

    init {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    suspend fun seedCalendar() {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "self@example.test")
        )
        calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "local://reminder-window",
                displayName = "Reminders",
                color = -1
            )
        )
    }

    /** An event whose first occurrence starts at [startTs], plus its occurrence row. */
    suspend fun insertEvent(
        title: String,
        startTs: Long,
        reminders: List<String>,
        isAllDay: Boolean = false
    ): Event {
        val event = Event(
            uid = "$title@kashcal.test",
            calendarId = calendarId,
            title = title,
            startTs = startTs,
            endTs = startTs + HOUR,
            dtstamp = 0L,
            isAllDay = isAllDay,
            reminders = reminders,
            alarmCount = reminders.size
        )
        val saved = event.copy(id = database.eventsDao().insert(event))
        insertOccurrence(saved, startTs)
        return saved
    }

    suspend fun insertOccurrence(event: Event, startTs: Long) {
        database.occurrencesDao().insert(
            Occurrence(
                eventId = event.id,
                calendarId = event.calendarId,
                startTs = startTs,
                endTs = startTs + HOUR,
                startDay = 20260101,
                endDay = 20260101
            )
        )
    }

    suspend fun insertReminderRow(
        event: Event,
        occurrenceTime: Long,
        reminderOffset: String,
        triggerTime: Long,
        status: ReminderStatus = ReminderStatus.PENDING
    ): Long = database.scheduledRemindersDao().insert(
        ScheduledReminder(
            eventId = event.id,
            occurrenceTime = occurrenceTime,
            triggerTime = triggerTime,
            reminderOffset = reminderOffset,
            status = status,
            eventTitle = event.title,
            eventLocation = null,
            isAllDay = event.isAllDay,
            calendarColor = -1
        )
    )

    suspend fun reminderRow(id: Long): ScheduledReminder? = database.scheduledRemindersDao().getById(id)

    /** Reminder alarms currently set, as reminder id to trigger time. */
    fun armedReminders(): Map<Long, Long> = reminderAlarms()
        .associate { (id, trigger) -> id to trigger }

    /** Every reminder alarm currently set, duplicates included. */
    fun reminderAlarms(): List<Pair<Long, Long>> =
        shadowOf(alarmManager).scheduledAlarms.mapNotNull { alarm ->
            val intent = alarm.operation?.let { shadowOf(it).savedIntent } ?: return@mapNotNull null
            if (intent.component?.className != ReminderAlarmReceiver::class.java.name) return@mapNotNull null
            intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L) to alarm.triggerAtMs
        }

    /** Drops every alarm, as a reboot does. */
    fun clearAlarms() {
        val shadow = shadowOf(alarmManager)
        shadow.scheduledAlarms.mapNotNull { it.operation }.forEach { alarmManager.cancel(it) }
    }

    fun reminderRowCount(): Int =
        database.query("SELECT COUNT(*) FROM scheduled_reminders", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    fun close() {
        database.close()
    }

    companion object {
        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
