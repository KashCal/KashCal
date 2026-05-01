package org.onekash.icaldav.model

import org.onekash.icaldav.util.DurationUtils
import java.time.Duration

/**
 * Alarm/reminder from VALARM component per RFC 5545 Section 3.6.6.
 * Extended with RFC 9074 properties for enhanced alarm handling.
 *
 * Note: Combined duration triggers like PT1H30M must be
 * parsed correctly (not just hours or just minutes).
 *
 * @see <a href="https://tools.ietf.org/html/rfc5545#section-3.6.6">RFC 5545 Section 3.6.6</a>
 * @see <a href="https://tools.ietf.org/html/rfc9074">RFC 9074 - VALARM Extensions</a>
 */
data class ICalAlarm(
    /** Alarm action: DISPLAY, AUDIO, EMAIL */
    val action: AlarmAction,

    /**
     * Trigger time relative to event start (negative = before).
     * Examples: -PT15M (15 min before), -PT1H30M (1h30m before)
     */
    val trigger: Duration?,

    /**
     * Absolute trigger time (alternative to relative trigger).
     */
    val triggerAbsolute: ICalDateTime?,

    /** Whether trigger is relative to END (default is START) */
    val triggerRelatedToEnd: Boolean = false,

    /** Description to display (for DISPLAY action) */
    val description: String?,

    /** Summary for email subject (for EMAIL action) */
    val summary: String?,

    /** Number of times to repeat the alarm */
    val repeatCount: Int = 0,

    /** Duration between alarm repetitions */
    val repeatDuration: Duration? = null,

    // RFC 9074 Extensions

    /** Unique identifier for this alarm (RFC 9074) */
    val uid: String? = null,

    /** When the alarm was acknowledged/dismissed (RFC 9074) */
    val acknowledged: ICalDateTime? = null,

    /** Related event UID for snooze alarms (RFC 9074) */
    val relatedTo: String? = null,

    /** Whether this is a default alarm that should be applied to new events (RFC 9074) */
    val defaultAlarm: Boolean = false,

    /** Proximity trigger for location-based alarms (RFC 9074) */
    val proximity: AlarmProximity? = null
) {
    /**
     * Get trigger offset in minutes (negative for before event).
     * Returns null for absolute triggers.
     */
    fun triggerMinutes(): Int? {
        return trigger?.toMinutes()?.toInt()
    }

    companion object {
        /**
         * Parse duration string per RFC 5545 Section 3.3.6.
         *
         * Delegates to [DurationUtils.parse] for consistent handling across the library.
         *
         * @param value Duration string (e.g., "-PT15M", "PT1H30M", "-P1DT2H")
         * @return Parsed Duration
         * @throws IllegalArgumentException if parsing fails
         */
        fun parseDuration(value: String): Duration {
            return DurationUtils.parse(value)
                ?: throw IllegalArgumentException("Invalid duration: $value")
        }

        /**
         * Format duration as iCalendar string.
         *
         * Delegates to [DurationUtils.format] for consistent handling across the library.
         */
        fun formatDuration(duration: Duration): String {
            return DurationUtils.format(duration)
        }
    }
}

/**
 * Alarm action types per RFC 5545.
 */
enum class AlarmAction {
    AUDIO,      // Play a sound
    DISPLAY,    // Display a notification
    EMAIL;      // Send an email

    companion object {
        fun fromString(value: String?): AlarmAction {
            return when (value?.uppercase()) {
                "AUDIO" -> AUDIO
                "EMAIL" -> EMAIL
                else -> DISPLAY
            }
        }
    }
}

/**
 * Proximity trigger types per RFC 9074.
 * Used for location-based alarm triggers.
 *
 * @see <a href="https://tools.ietf.org/html/rfc9074#section-8.1">RFC 9074 Section 8.1</a>
 */
enum class AlarmProximity {
    /** Trigger when arriving at the event location */
    ARRIVE,

    /** Trigger when departing from the event location */
    DEPART;

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): AlarmProximity? {
            if (value.isNullOrBlank()) return null
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) }
        }
    }
}