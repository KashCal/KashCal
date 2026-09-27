package org.onekash.kashcal.ui.util.text

/**
 * Returns whether this string contains [other], ignoring case.
 *
 * Compares per character with [String.regionMatches] and `ignoreCase = true`, which folds the
 * same characters whatever `Locale.getDefault()` is. That suits UI labels: a Turkish default
 * locale doesn't fold 'i', 'I' and 'İ' apart against an English label set.
 *
 * An empty [other] returns true (matches anything), so a use site that shows everything when
 * nothing is typed needs no separate empty-query branch. A whitespace-only [other] is searched
 * for like any other text.
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
