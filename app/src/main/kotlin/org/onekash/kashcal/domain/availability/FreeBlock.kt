package org.onekash.kashcal.domain.availability

import java.time.LocalDate
import java.time.LocalTime

/**
 * A contiguous block of free time inside the user's working-hours window.
 *
 * An [end] of `LocalTime.MAX` means end of day (24:00); see [FreeBlockFinder].
 */
data class FreeBlock(
    val day: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val durationMinutes: Long
)
