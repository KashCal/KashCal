package org.onekash.kashcal.ui.components

import androidx.compose.ui.text.style.TextDecoration

/**
 * Alpha for an event card given its past/declined state.
 *
 * Floors at 0.5f when either flag is set: multiplying two 0.5fs would dim
 * declined-and-past events to 0.25f, which is too faint to read.
 */
fun declinedCardAlpha(isPast: Boolean, isDeclined: Boolean): Float =
    if (isPast || isDeclined) 0.5f else 1.0f

/**
 * Title decoration for an event given its declined state.
 *
 * Returns LineThrough when declined, null otherwise.
 */
fun declinedTitleDecoration(isDeclined: Boolean): TextDecoration? =
    if (isDeclined) TextDecoration.LineThrough else null
