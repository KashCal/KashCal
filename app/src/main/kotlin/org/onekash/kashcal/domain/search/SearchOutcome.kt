package org.onekash.kashcal.domain.search

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.DisplayEvent

/**
 * One search result: an event dated at one of its occurrences.
 *
 * [displayEvent] carries that occurrence and [displayTs] is its start. [isSeries] is true for
 * a repeating event or a changed occurrence of one. [isHidden] is true when its calendar is
 * hidden in the app. [reason] says where the search words matched when the title doesn't
 * hold them all ([SearchMatchExplainer]).
 */
@Immutable
data class SearchHit(
    val displayEvent: DisplayEvent,
    val displayTs: Long,
    val isSeries: Boolean,
    val isHidden: Boolean,
    val reason: MatchReason? = null,
)

/**
 * How long one search took, in milliseconds: the Room query with its occurrence read, the
 * device search, and both from the start of the search to this result. [coverageMs] is the
 * latest expansion of matched series that did work ([OccurrenceGenerator.ensureCoveredFor]),
 * null until one has. It runs beside the search, so the first result's [totalMs] doesn't
 * include it; a result that arrives after it does.
 */
@Immutable
data class SearchTiming(
    val roomMs: Long,
    val deviceMs: Long,
    val totalMs: Long,
    val coverageMs: Long? = null,
)

/**
 * The results of one search, split by [SearchSplitter].
 *
 * [upcoming] and [past] hold the matches in calendars shown in the app; [hiddenUpcoming] and
 * [hiddenPast] the matches in hidden ones, which the screen leaves out unless asked. With a
 * date range, [range] and [hiddenRange] hold the matches in it; without one they are null.
 * [lookbackDays] is the sync lookback the phone holds events for (Int.MAX_VALUE for all).
 */
@Immutable
data class SearchOutcome(
    val upcoming: ImmutableList<SearchHit> = persistentListOf(),
    val past: ImmutableList<SearchHit> = persistentListOf(),
    val hiddenUpcoming: ImmutableList<SearchHit> = persistentListOf(),
    val hiddenPast: ImmutableList<SearchHit> = persistentListOf(),
    val range: ImmutableList<SearchHit>? = null,
    val hiddenRange: ImmutableList<SearchHit>? = null,
    val timing: SearchTiming = SearchTiming(0, 0, 0),
    val lookbackDays: Int = Int.MAX_VALUE,
)

/**
 * One Room event the search matched, with the occurrence rows that date it (see
 * [org.onekash.kashcal.data.db.dao.EventSearchSql.nextAndLast]): [next] not yet ended, [last]
 * ended, [inRange] the first in the date range. [hasOccurrences] is false when no row at all
 * belongs to the event, cancelled or linked ones included.
 */
data class RoomSearchMatch(
    val event: Event,
    val next: Occurrence?,
    val last: Occurrence?,
    val inRange: Occurrence?,
    val hasOccurrences: Boolean,
)
