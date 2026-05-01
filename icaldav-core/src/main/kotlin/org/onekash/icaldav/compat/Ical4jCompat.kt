package org.onekash.icaldav.compat

import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VFreeBusy
import net.fortuna.ical4j.model.component.VToDo
import net.fortuna.ical4j.model.component.VJournal
import net.fortuna.ical4j.model.WeekDay
import java.time.DayOfWeek

/**
 * Compatibility layer for ical4j 4.x API.
 *
 * Provides Kotlin-friendly extensions:
 * - getPropertyOrNull() - unwraps Optional<T> to T?
 * - getParameterOrNull() - unwraps Optional<T> to T?
 * - WeekDay conversion helpers
 *
 * In 4.x, ical4j uses:
 * - Optional<T> for getProperty()/getParameter()
 * - java.time.* API natively (no Date/DateTime needed)
 * - WeekDay.getWeekDay(java.time.DayOfWeek)
 */

// ============ Property Access Extensions ============

/**
 * Get property by name, returning null if not found.
 * Unwraps ical4j 4.x Optional<T> to nullable T?.
 */
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

/**
 * Get all properties from a VEvent.
 * Returns a list that can be iterated over.
 */
fun VEvent.getAllProperties(): List<Property> {
    return getProperties<Property>().toList()
}

/**
 * Get all properties from a VToDo.
 * Returns a list that can be iterated over.
 */
fun VToDo.getAllProperties(): List<Property> {
    return getProperties<Property>().toList()
}

/**
 * Get all properties from a VJournal.
 * Returns a list that can be iterated over.
 */
fun VJournal.getAllProperties(): List<Property> {
    return getProperties<Property>().toList()
}

/**
 * Get parameter by name, returning null if not found.
 * Unwraps ical4j 4.x Optional<T> to nullable T?.
 */
inline fun <reified T : Parameter> Property.getParameterOrNull(name: String): T? {
    return getParameter<T>(name).orElse(null)
}

// ============ WeekDay Conversion ============

/**
 * Convert java.time.DayOfWeek to ical4j WeekDay.
 * ical4j 4.x accepts java.time.DayOfWeek directly.
 */
fun DayOfWeek.toIcal4jWeekDay(): WeekDay {
    return WeekDay.getWeekDay(this)
}

/**
 * Get WeekDay with optional offset (e.g., 2nd Monday = ordinal 2).
 * ical4j 4.x: WeekDay constructor accepts DayOfWeek and ordinal.
 */
fun DayOfWeek.toIcal4jWeekDay(ordinal: Int?): WeekDay {
    return if (ordinal != null) {
        // ical4j 4.x: WeekDay constructor takes (WeekDay, Int), not (DayOfWeek, Int)
        WeekDay(WeekDay.getWeekDay(this), ordinal)
    } else {
        WeekDay.getWeekDay(this)
    }
}
