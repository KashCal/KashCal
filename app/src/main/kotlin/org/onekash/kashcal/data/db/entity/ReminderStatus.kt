package org.onekash.kashcal.data.db.entity

/** Lifecycle state of a [ScheduledReminder], from scheduling to dismissal. */
enum class ReminderStatus {
    /** Alarm set with AlarmManager, waiting to fire. */
    PENDING,

    /** Alarm fired and the notification is shown. */
    FIRED,

    /** User snoozed the reminder; the alarm is set again. */
    SNOOZED,

    /** User dismissed the reminder; nothing more to do. */
    DISMISSED
}
