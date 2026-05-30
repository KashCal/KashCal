package org.onekash.kashcal.ui.screens.settings

import org.onekash.kashcal.ui.util.text.containsCaseInsensitive

/**
 * A searchable representation of a single settings row. Carries enough
 * for the filter to decide visibility and for the UI to map back to its
 * row composable via [id].
 *
 * @property id Stable identifier; tied to the row by the screen, not by
 *   any database key.
 * @property label The row's primary label (the same string that renders
 *   in [SettingsRow]'s `label` parameter).
 * @property subtitle The row's currently-rendered subtitle, or null if
 *   the row has none. Subtitles are dynamic (e.g., "30 days" -> "90 days"),
 *   so callers must reconstruct this list when underlying state changes
 *   so the filter re-evaluates.
 */
data class SearchableRow(
    val id: String,
    val label: String,
    val subtitle: String?
)

/**
 * Returns the subset of [rows] whose label or subtitle contains [query]
 * as a substring (case-insensitive). Empty or whitespace-only [query]
 * returns [rows] unchanged.
 */
fun filterSettings(rows: List<SearchableRow>, query: String): List<SearchableRow> {
    if (query.isBlank()) return rows
    return rows.filter { row ->
        row.label.containsCaseInsensitive(query) ||
            (row.subtitle?.containsCaseInsensitive(query) == true)
    }
}
