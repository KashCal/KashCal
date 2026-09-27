package org.onekash.kashcal.domain.model

import androidx.compose.runtime.Immutable

/**
 * Pairs a search hit with the time the result shows.
 *
 * For a Room event [displayTs] is the start of its next occurrence, so a recurring hit
 * shows when it next happens, falling back to the event's start. For a device event it is the
 * Instances row's start.
 */
@Immutable
data class SearchResult(
    val displayEvent: DisplayEvent,
    val displayTs: Long
)
