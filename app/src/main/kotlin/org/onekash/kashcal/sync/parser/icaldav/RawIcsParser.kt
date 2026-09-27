package org.onekash.kashcal.sync.parser.icaldav

import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.parser.ICalParser

/**
 * Reads data from `rawIcal` that the Event columns don't hold, parsing on demand.
 *
 * [Event.reminders][org.onekash.kashcal.data.db.entity.Event.reminders] keeps at most five alarms
 * and no column holds the rest; `ReminderScheduler` reads the full set from the stored `rawIcal`
 * here when `Event.alarmCount` is over 3. Every function reads the file's first VEVENT and returns
 * empty when parsing fails.
 */
object RawIcsParser {

    private val parser = ICalParser()

    /** Returns the first VEVENT's alarms except ACTION:NONE, or empty if [rawIcal] won't parse. */
    fun getAllAlarms(rawIcal: String?): List<ICalAlarm> {
        if (rawIcal.isNullOrBlank()) return emptyList()

        return try {
            val events = parser.parseAllEvents(rawIcal).getOrNull()
            // Exclude RFC 9074 ACTION:NONE sentinels, as ICalEventMapper does, so the
            // scheduler's all-alarms path can't turn a sentinel into a phantom reminder.
            // The trigger and count functions below inherit this.
            events?.firstOrNull()?.alarms.orEmpty().filter { it.action != AlarmAction.NONE }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Returns the relative alarm triggers as duration strings ("-PT15M", "-P1D"), END-relative
     * ones included.
     */
    fun getAllAlarmTriggers(rawIcal: String?): List<String> {
        return getAllAlarms(rawIcal).mapNotNull { alarm ->
            alarm.trigger?.let { ICalAlarm.formatDuration(it) }
        }
    }

    /** Returns the number of alarms [getAllAlarms] reads. Only tests call it. */
    fun getAlarmCount(rawIcal: String?): Int {
        return getAllAlarms(rawIcal).size
    }
}
