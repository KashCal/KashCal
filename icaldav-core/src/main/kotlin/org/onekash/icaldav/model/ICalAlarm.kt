package org.onekash.icaldav.model

import org.onekash.icaldav.util.DurationUtils
import java.time.Duration

/**
 * Holds one VALARM component (RFC 5545 §3.6.6) with the RFC 9074 extension properties.
 *
 * A combined trigger like PT1H30M must parse as both parts, not only the hours or the minutes.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5545#section-3.6.6">RFC 5545 Section 3.6.6</a>
 * @see <a href="https://tools.ietf.org/html/rfc9074">RFC 9074 - VALARM Extensions</a>
 */
data class ICalAlarm(
    /** ACTION; see [AlarmAction.fromString] for unknown values. */
    val action: AlarmAction,

    /**
     * Relative trigger, negative before the event: -PT15M, -PT1H30M. Relative to the start
     * unless [triggerRelatedToEnd]. Null when the trigger is [triggerAbsolute]; the parser gives
     * a VALARM without TRIGGER 15 minutes before.
     */
    val trigger: Duration?,

    /** Absolute trigger time, set when [trigger] is null. */
    val triggerAbsolute: ICalDateTime?,

    /** Whether [trigger] is relative to the END (RELATED=END); the default is the start. */
    val triggerRelatedToEnd: Boolean = false,

    /** Text to display, for DISPLAY. */
    val description: String?,

    /** Email subject, for EMAIL. */
    val summary: String?,

    /** REPEAT: how many more times the alarm fires after the first. */
    val repeatCount: Int = 0,

    /** DURATION between repeats. */
    val repeatDuration: Duration? = null,

    // RFC 9074 Extensions

    /** The alarm's own UID (RFC 9074 §4). */
    val uid: String? = null,

    /** ACKNOWLEDGED: when the alarm was last acknowledged (RFC 9074 §6.1). */
    val acknowledged: ICalDateTime? = null,

    /**
     * RELATED-TO value. On a snooze alarm it is the UID of the alarm it snoozes (RFC 9074 §7).
     */
    val relatedTo: String? = null,

    /**
     * DEFAULT-ALARM:TRUE, marking an alarm to apply to new events. RFC 9074 doesn't define this
     * property.
     */
    val defaultAlarm: Boolean = false,

    /** PROXIMITY trigger for location-based alarms (RFC 9074 §8.1). */
    val proximity: AlarmProximity? = null
) {
    /**
     * Returns the [trigger] offset in whole minutes (negative before the event), or null for an
     * absolute trigger.
     */
    fun triggerMinutes(): Int? {
        return trigger?.toMinutes()?.toInt()
    }

    companion object {
        /**
         * Parses an RFC 5545 §3.3.6 duration such as "-PT15M", "PT1H30M" or "-P1DT2H" with
         * [DurationUtils.parse].
         *
         * @throws IllegalArgumentException if parsing fails
         */
        fun parseDuration(value: String): Duration {
            return DurationUtils.parse(value)
                ?: throw IllegalArgumentException("Invalid duration: $value")
        }

        /** Formats [duration] as an iCalendar duration with [DurationUtils.format]. */
        fun formatDuration(duration: Duration): String {
            return DurationUtils.format(duration)
        }
    }
}

/** VALARM ACTION values: the three from RFC 5545 plus NONE. */
enum class AlarmAction {
    AUDIO,      // Play a sound
    DISPLAY,    // Display a notification
    EMAIL,      // Send an email
    NONE;       // "No action" sentinel, not a user-facing alarm; RFC 9074 doesn't define it

    companion object {
        /** Maps [value] ignoring case; null or any unknown value maps to DISPLAY. */
        fun fromString(value: String?): AlarmAction {
            return when (value?.uppercase()) {
                "AUDIO" -> AUDIO
                "EMAIL" -> EMAIL
                "NONE" -> NONE
                else -> DISPLAY
            }
        }
    }
}

/**
 * PROXIMITY values for location-based alarms (RFC 9074 §8.1). The RFC's CONNECT and DISCONNECT
 * aren't modelled; [fromString] returns null for them.
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