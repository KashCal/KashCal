package org.onekash.kashcal.ui.screens.settings

import org.onekash.kashcal.ui.util.text.containsCaseInsensitive

/**
 * Describes one settings row for [filterSettings]: enough to decide visibility and to map back
 * to the row composable by [id].
 *
 * @property id a stable identifier the screen assigns, not a database key.
 * @property label the row's primary label, the string passed as [SettingsRow]'s `label`.
 * @property subtitle the row's currently rendered subtitle, or null. Subtitles change with state
 *   ("30 days" to "90 days"), so callers must rebuild the list when that state changes or the
 *   filter matches stale text.
 */
data class SearchableRow(
    val id: String,
    val label: String,
    val subtitle: String?
)

/**
 * Returns the [rows] whose label or subtitle contains [query], ignoring case. A blank [query]
 * returns [rows] unchanged.
 */
fun filterSettings(rows: List<SearchableRow>, query: String): List<SearchableRow> {
    if (query.isBlank()) return rows
    return rows.filter { row ->
        row.label.containsCaseInsensitive(query) ||
            (row.subtitle?.containsCaseInsensitive(query) == true)
    }
}
