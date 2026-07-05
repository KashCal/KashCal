package org.onekash.kashcal.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import org.onekash.kashcal.R

/**
 * Alpha for an event card given its de-emphasized state (past, declined, or
 * cancelled).
 *
 * Floors at 0.5f when any flag is set: multiplying several 0.5fs would dim a
 * past-and-declined-and-cancelled event well below legibility.
 */
fun declinedCardAlpha(isPast: Boolean, isDeclined: Boolean, isCancelled: Boolean = false): Float =
    if (isPast || isDeclined || isCancelled) 0.5f else 1.0f

/**
 * Title decoration for an event given its declined/cancelled state.
 *
 * Returns LineThrough when the event is declined by the user or the whole
 * event has been cancelled (STATUS:CANCELLED), null otherwise.
 */
fun declinedTitleDecoration(isDeclined: Boolean, isCancelled: Boolean = false): TextDecoration? =
    if (isDeclined || isCancelled) TextDecoration.LineThrough else null

/**
 * String resource for an event's de-emphasized state, or null when the event is
 * in its normal state. Returns the single most significant state — cancelled
 * outranks declined outranks past — to keep the screen-reader announcement short.
 *
 * Kept as a pure (non-composable) function so the precedence is unit-testable.
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
 * Screen-reader state label for an event whose de-emphasized state is otherwise
 * conveyed only by dimming + strikethrough (which is silent to TalkBack). Meant
 * as a `stateDescription` on the merged event-card node, so the card announces
 * e.g. "Team Meeting, 10 AM, cancelled". Null when the event is normal.
 */
@Composable
fun eventStateDescription(
    isPast: Boolean,
    isDeclined: Boolean,
    isCancelled: Boolean = false,
): String? = eventStateRes(isPast, isDeclined, isCancelled)?.let { stringResource(it) }
