package org.onekash.kashcal.domain.share

/**
 * Hour labels for the day stripe on a share card. Five labels at the
 * 0/6/12/18/24 marks. 12h locales use AM/PM compact forms; 24h locales use
 * zero-padded hours.
 */
object StripeLabels {
    fun labelsFor(is24Hour: Boolean): List<String> =
        if (is24Hour) listOf("00", "06", "12", "18", "24")
        else listOf("12a", "6a", "12p", "6p", "12a")
}
