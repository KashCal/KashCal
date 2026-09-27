package org.onekash.icaldav.compat

import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.WeekDay
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VFreeBusy
import net.fortuna.ical4j.model.component.VJournal
import net.fortuna.ical4j.model.component.VToDo
import java.time.DayOfWeek

/**
 * Kotlin extensions over the ical4j 4.x API: `getPropertyOrNull` and `getParameterOrNull` unwrap
 * the `Optional` that `getProperty` and `getParameter` return, and the WeekDay helpers convert
 * from `java.time.DayOfWeek`.
 */

// ============ Property Access Extensions ============

/** Returns the first property named [name], or null if there is none. */
inline fun <reified T : Property> VEvent.getPropertyOrNull(name: String): T? {
    return getProperty<T>(name).orElse(null)
}

inline fun <reified T : Property> VAlarm.getPropertyOrNull(name: String): T? {
    return getProperty<T>(name).orElse(null)
}

inline fun <reified T : Property> VFreeBusy.getPropertyOrNull(name: String): T? {
    return getProperty<T>(name).orElse(null)
}

inline fun <reified T : Property> VToDo.getPropertyOrNull(name: String): T? {
    return getProperty<T>(name).orElse(null)
}

inline fun <reified T : Property> VJournal.getPropertyOrNull(name: String): T? {
    return getProperty<T>(name).orElse(null)
}

inline fun <reified T : Property> Calendar.getPropertyOrNull(name: String): T? {
    return getProperty<T>(name).orElse(null)
}

/** Returns every property of this VEvent as a list. */
fun VEvent.getAllProperties(): List<Property> {
    return getProperties<Property>().toList()
}

/** Returns every property of this VToDo as a list. */
fun VToDo.getAllProperties(): List<Property> {
    return getProperties<Property>().toList()
}

/** Returns every property of this VJournal as a list. */
fun VJournal.getAllProperties(): List<Property> {
    return getProperties<Property>().toList()
}

/** Returns the first parameter named [name], or null if there is none. */
inline fun <reified T : Parameter> Property.getParameterOrNull(name: String): T? {
    return getParameter<T>(name).orElse(null)
}

/**
 * Returns the first parameter whose name equals [name] ignoring case, or null if there is none.
 * RFC 5545 §3.1 makes parameter names case-insensitive, so `EMAIL=`, `email=` and `Email=` all
 * match. ical4j 4.3.0's `getParameter(name)` also compares names ignoring case; this returns an
 * untyped [Parameter].
 */
fun Property.getParameterIgnoreCase(name: String): Parameter? {
    return getParameterList().all.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

// ============ WeekDay Conversion ============

/** Converts this day to the ical4j [WeekDay] with no ordinal. */
fun DayOfWeek.toIcal4jWeekDay(): WeekDay {
    return WeekDay.getWeekDay(this)
}

/** Converts this day to an ical4j [WeekDay] with an optional [ordinal] (2nd Monday is 2). */
fun DayOfWeek.toIcal4jWeekDay(ordinal: Int?): WeekDay {
    return if (ordinal != null) {
        // The ical4j 4.x constructor takes (WeekDay, Int); there is no (DayOfWeek, Int).
        WeekDay(WeekDay.getWeekDay(this), ordinal)
    } else {
        WeekDay.getWeekDay(this)
    }
}
