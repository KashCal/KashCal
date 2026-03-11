package org.onekash.kashcal.domain.model

import androidx.compose.runtime.Immutable

/**
 * Wrapper for search results that pairs a [DisplayEvent] with its display timestamp.
 *
 * For Room recurring events, [displayTs] is the next occurrence's startTs
 * (so the search result shows when the event next occurs, not when it was created).
 * For Device events, [displayTs] is the instance's startTs.
 * For Room non-recurring events, [displayTs] is the event's startTs.
 */
@Immutable
data class SearchResult(
    val displayEvent: DisplayEvent,
    val displayTs: Long
)
