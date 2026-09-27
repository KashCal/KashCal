package org.onekash.kashcal.domain.share

/**
 * Hour labels for the share card's day stripe, at the 0, 6, 12, 18 and 24 hour marks: compact
 * "12a"/"6p" forms in 12-hour format, zero-padded hours in 24-hour format.
 */
object StripeLabels {
    fun labelsFor(is24Hour: Boolean): List<String> =
        if (is24Hour) listOf("00", "06", "12", "18", "24")
        else listOf("12a", "6a", "12p", "6p", "12a")
}
