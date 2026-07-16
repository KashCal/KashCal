package org.onekash.kashcal.domain.quickadd

import java.time.LocalDate
import java.time.LocalTime

enum class ParseConfidence {
    HIGH,
    MEDIUM,
    LOW
}

data class QuickAddResult(
    val title: String,
    val startDate: LocalDate,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val endDate: LocalDate? = null,
    val timezone: String? = null,
    val location: String? = null,
    val rrule: String? = null,
    val categories: List<String> = emptyList(),
    val emoji: String? = null,
    val isAllDay: Boolean = startTime == null,
    val confidence: ParseConfidence = ParseConfidence.LOW
)
