package org.onekash.kashcal.data.db.entity

/**
 * Status of a scheduled reminder alarm.
 *
 * Tracks the lifecycle of a reminder from scheduling to dismissal.
 */
enum class ReminderStatus {
    /**
     * Alarm scheduled with AlarmManager, waiting to fire.
     */
    PENDING,

    /**
     * Alarm fired, notification shown to user.
     */
    FIRED,

    /**
     * User snoozed the reminder, alarm rescheduled.
     */
    SNOOZED,

    /**
     * User dismissed the reminder, no further action needed.
     */
    DISMISSED
}
