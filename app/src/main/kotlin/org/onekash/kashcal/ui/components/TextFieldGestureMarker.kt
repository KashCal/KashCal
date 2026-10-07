package org.onekash.kashcal.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Carries the event form's [textFieldGestureMarker] to text fields drawn by shared components.
 *
 * The form provides it so the tag-name field and the repeat count, which live in other
 * composables, mark their gestures like the form's own fields do; everywhere else it is a
 * no-op [Modifier]. A field applies it only while editable.
 */
internal val LocalTextFieldGestureMarker = staticCompositionLocalOf<Modifier> { Modifier }

/**
 * Sets [startedInTextField] when a gesture's first press lands on the modified field.
 *
 * Feeds [shouldDismissKeyboardOnScroll]. Only observes, so the field's own taps, typing and
 * selection are unaffected. The host clears the flag on each new first press; it sees the
 * press before this does, because the [PointerEventPass.Initial] pass runs from ancestors to
 * descendants.
 */
internal fun textFieldGestureMarker(startedInTextField: MutableState<Boolean>): Modifier =
    Modifier.pointerInput(startedInTextField) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            startedInTextField.value = true
        }
    }
