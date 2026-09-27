package org.onekash.kashcal.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import org.onekash.kashcal.R

/**
 * Returns an event card's alpha for its de-emphasized state (past, declined or
 * cancelled).
 *
 * Any flag gives 0.5f and the flags don't compound: multiplying several 0.5fs
 * would dim a past, declined and cancelled event well below legibility.
 */
fun declinedCardAlpha(isPast: Boolean, isDeclined: Boolean, isCancelled: Boolean = false): Float =
    if (isPast || isDeclined || isCancelled) 0.5f else 1.0f

/**
 * Returns LineThrough for the title of an event the user declined or one that is
 * cancelled (STATUS:CANCELLED), else null.
 */
fun declinedTitleDecoration(isDeclined: Boolean, isCancelled: Boolean = false): TextDecoration? =
    if (isDeclined || isCancelled) TextDecoration.LineThrough else null

/**
 * Returns the string resource for an event's de-emphasized state, or null for a
 * normal event. Only the most significant state is returned (cancelled, then
 * declined, then past) to keep the screen-reader announcement short.
 *
 * Not composable, so the precedence is unit-testable.
 */
@StringRes
fun eventStateRes(
    isPast: Boolean,
    isDeclined: Boolean,
    isCancelled: Boolean = false,
): Int? = when {
    isCancelled -> R.string.cd_event_state_cancelled
    isDeclined -> R.string.cd_event_state_declined
    isPast -> R.string.cd_event_state_past
    else -> null
}

/**
 * Returns the screen-reader state label for an event whose state is otherwise shown
 * only by dimming and strikethrough, which TalkBack doesn't announce. Meant as the
 * `stateDescription` of the merged event-card node, so the card announces e.g.
 * "Team Meeting, 10 AM, cancelled". Null for a normal event.
 */
@Composable
fun eventStateDescription(
    isPast: Boolean,
    isDeclined: Boolean,
    isCancelled: Boolean = false,
): String? = eventStateRes(isPast, isDeclined, isCancelled)?.let { stringResource(it) }
