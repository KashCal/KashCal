package org.onekash.kashcal.ui.components.pickers

/**
 * Largest interval the user can enter in [CustomRecurrenceBuilder], for every unit; the builder
 * states how a synced larger interval is kept.
 */
const val RECURRENCE_INTERVAL_MAX = 999

/** Most digits a user can type into the picker's number fields: the digits of the cap. */
val RECURRENCE_MAX_TYPED_DIGITS = RECURRENCE_INTERVAL_MAX.toString().length

/**
 * Returns whether a typed edit from [original] to [edited] may stand.
 *
 * Unchanged text always passes, since a cursor or selection move runs the same check.
 * Otherwise the edit must be all decimal digits, in any script, and at most
 * [RECURRENCE_MAX_TYPED_DIGITS] long unless it is shorter than [original], so a synced "1200"
 * can only be shortened.
 */
fun acceptsNumberEdit(original: CharSequence, edited: CharSequence): Boolean {
    if (edited.contentEquals(original)) return true
    if (!edited.all { it.isDigit() }) return false
    return edited.length <= RECURRENCE_MAX_TYPED_DIGITS || edited.length < original.length
}

/**
 * Returns the value of [text] when it is one or more decimal digits (any script) worth at least
 * 1, else null. A sign, any other character or an Int overflow gives null.
 */
fun positiveNumberOrNull(text: CharSequence): Int? {
    if (text.isEmpty() || !text.all { it.isDigit() }) return null
    return text.toString().toIntOrNull()?.takeIf { it >= 1 }
}

/** Returns [text] without its leading zero digits, keeping at least one digit and the script. */
fun stripLeadingZeros(text: String): String =
    text.dropWhile { Character.digit(it, 10) == 0 }.ifEmpty { text.takeLast(1) }

/** Returns the text the picker writes into a number field for [value]. */
fun numberText(value: Int): String = value.toString()
