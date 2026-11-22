package org.onekash.kashcal.testutil

import java.util.SimpleTimeZone
import java.util.TimeZone

/**
 * A device zone as Android reads it after the platform's cutoff year: java.util's zone data
 * stops applying daylight saving time there and keeps the offset of the zone's last listed
 * transition, while java.time keeps the zone's ongoing rule. The Android 17 emulator does this
 * from 2100 (Berlin and New York on standard time all summer, Sydney on summer time all
 * winter); Android 13 does it from an earlier year.
 *
 * On the JVM, [withSplitDeviceTimeZone] reproduces it for any date: java.util sees
 * [javaUtilOffsetMs] all year, and `ZoneId.systemDefault()` resolves [id] to its real rules.
 * [TOKYO] and [UTC] are controls with no daylight saving time since 1952, so the two engines
 * agree there on today's dates.
 *
 * @property javaUtilOffsetMs the fixed offset java.util applies
 */
enum class SplitZone(val id: String, val javaUtilOffsetMs: Int) {
    BERLIN("Europe/Berlin", 1 * HOUR_MS),
    NEW_YORK("America/New_York", -5 * HOUR_MS),
    SYDNEY("Australia/Sydney", 11 * HOUR_MS),
    TOKYO("Asia/Tokyo", 9 * HOUR_MS),
    UTC("UTC", 0),
}

private const val HOUR_MS = 3_600_000

/**
 * Runs [block] with the default zone set to [zone] split as [SplitZone] describes, restoring
 * the previous default afterwards. [withDeviceTimeZone] is the unsplit counterpart.
 */
fun <T> withSplitDeviceTimeZone(zone: SplitZone, block: () -> T): T {
    val original = TimeZone.getDefault()
    TimeZone.setDefault(SimpleTimeZone(zone.javaUtilOffsetMs, zone.id))
    try {
        return block()
    } finally {
        TimeZone.setDefault(original)
    }
}
