package org.onekash.kashcal.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.util.Log

/**
 * Sets a wake-up alarm without ever letting the platform's refusal escape.
 *
 * AlarmManager caps each app at a fixed number of pending alarms (500 by default)
 * and throws IllegalStateException for every further set call, including one that
 * would only replace an alarm the app already holds. Set calls run at app startup,
 * in broadcast receivers and on background observers, where an escaping exception
 * crashes the app. Device-calendar reminders and widget updates set their alarms here;
 * `ReminderScheduler.scheduleAlarm` catches the same refusals itself.
 */
object AlarmArming {

    /**
     * Sets an exact alarm when exact alarms are allowed, otherwise an inexact one.
     * A SecurityException (exact-alarm permission missing or revoked) gets one
     * inexact attempt when [inexactFallback] is true. A refusal at the alarm limit
     * gets no retry: the limit counts inexact alarms too.
     *
     * @param label names the alarm in the log; must not carry personal data
     * @return true if an exact or inexact alarm was set, false if it was refused
     */
    fun setAllowWhileIdle(
        alarmManager: AlarmManager,
        triggerTime: Long,
        pendingIntent: PendingIntent,
        tag: String,
        label: String,
        inexactFallback: Boolean = true
    ): Boolean {
        return try {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                Log.d(tag, "$label: exact alarm set for $triggerTime")
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                Log.d(tag, "$label: inexact alarm set for $triggerTime (may drift 5-15 min)")
            }
            true
        } catch (e: SecurityException) {
            if (!inexactFallback) {
                Log.e(tag, "$label: cannot set alarm", e)
                return false
            }
            Log.w(tag, "$label: alarm denied, trying inexact", e)
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                true
            } catch (e2: SecurityException) {
                Log.e(tag, "$label: cannot set any alarm", e2)
                false
            } catch (e2: IllegalStateException) {
                logAlarmLimitRefusal(tag, label, triggerTime, e2)
                false
            }
        } catch (e: IllegalStateException) {
            logAlarmLimitRefusal(tag, label, triggerTime, e)
            false
        }
    }

    /**
     * One line, no stack trace: once the app is at the alarm limit every later
     * request is refused, so a single refresh can hit this many times.
     */
    private fun logAlarmLimitRefusal(tag: String, label: String, triggerTime: Long, e: IllegalStateException) {
        Log.e(tag, "$label at $triggerTime not armed: ${e.message}")
    }
}
