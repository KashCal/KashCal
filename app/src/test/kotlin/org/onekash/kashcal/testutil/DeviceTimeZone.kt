package org.onekash.kashcal.testutil

import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Runs [block] with the JVM default timezone set to [zoneId] (the "phone's"
 * zone), restoring the previous default afterwards.
 */
fun <T> withDeviceTimeZone(zoneId: String, block: () -> T): T {
    val original = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
    try {
        return block()
    } finally {
        TimeZone.setDefault(original)
    }
}

/** Midnight of [date] in the current default ("phone") timezone, as the event form stores dates. */
fun phoneMidnight(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** The date [millis] falls on in the current default ("phone") timezone. */
fun phoneLocalDate(millis: Long): LocalDate =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
