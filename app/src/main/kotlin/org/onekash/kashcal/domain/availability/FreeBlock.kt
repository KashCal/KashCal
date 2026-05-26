package org.onekash.kashcal.domain.availability

import java.time.LocalDate
import java.time.LocalTime

/**
 * A contiguous block of free time inside the user's working-hours window.
 *
 * Pure value type with no Android dependencies; safe to share between
 * domain logic and UI state.
 */
data class FreeBlock(
    val day: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val durationMinutes: Long
)
