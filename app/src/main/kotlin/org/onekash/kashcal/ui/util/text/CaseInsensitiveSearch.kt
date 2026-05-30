package org.onekash.kashcal.ui.util.text

/**
 * Case-insensitive substring check using [String.regionMatches] with
 * `ignoreCase = true`. Locale-independent in the sense that it folds the
 * same characters regardless of `Locale.getDefault()` — which is the
 * desired behavior for UI labels (Turkish 'i'/'I'/'İ' are not
 * regionally folded apart against an English label set).
 *
 * Empty/whitespace [other] returns true (matches anything) so callers
 * can skip a separate empty-query branch when the use site already
 * means "show everything when nothing typed."
 */
fun String.containsCaseInsensitive(other: String): Boolean {
    if (other.isEmpty()) return true
    val q = other.length
    if (q > length) return false
    var i = 0
    val last = length - q
    while (i <= last) {
        if (regionMatches(i, other, 0, q, ignoreCase = true)) return true
        i++
    }
    return false
}
