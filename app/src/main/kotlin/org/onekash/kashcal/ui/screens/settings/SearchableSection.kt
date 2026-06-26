package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.util.text.containsCaseInsensitive

/**
 * Renders a settings section that participates in inline search.
 *
 * The [content] block is a Composable lambda that registers rows on its
 * receiver via [SearchableSectionScope.row]. After the block runs, the
 * section decides — based on [query] — which rows to render and whether
 * to emit the [header] + card at all.
 *
 * Empty-query fast path: when [query] is blank, every registered row is
 * rendered. When the section emits anything, [onEmitted]
 * fires once during composition so the parent can track whether *any*
 * section produced UI (drives the empty-state fallback).
 *
 * Dividers are emitted by the section between consecutive visible rows;
 * callers MUST NOT pass `showDivider` to row composables — leaving the
 * default `false` gives the section sole control. This avoids the
 * orphan-divider artifact that would otherwise appear when search filters
 * out the source-last row but keeps an earlier row that had its
 * `showDivider` defaulted to true.
 */
@Composable
fun SearchableSection(
    query: String,
    modifier: Modifier = Modifier,
    header: String? = null,
    onEmitted: () -> Unit = {},
    content: @Composable SearchableSectionScope.() -> Unit
) {
    val scope = SearchableSectionScope()
    scope.content()

    val visibleRows = if (query.isBlank()) {
        scope.rows
    } else {
        scope.rows.filter { it.matches(query) }
    }
    if (visibleRows.isEmpty()) return

    onEmitted()

    Column(modifier = modifier) {
        if (header != null) SectionHeader(header)
        SettingsCard {
            visibleRows.forEachIndexed { index, row ->
                key(row.id) { row.render() }
                if (index < visibleRows.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

/**
 * Receiver for [SearchableSection]'s content block. The block registers
 * rows by calling [row] with their matchable text and render lambda.
 */
class SearchableSectionScope internal constructor() {
    internal val rows = mutableListOf<RegisteredRow>()

    /**
     * Register a row in source order. The render lambda runs later in
     * the section's Compose scope, only if the row passes the filter,
     * and inside a [key] block keyed on [id] (defaulting to [label]) so
     * remember-state inside the row stays bound to the row's identity
     * even when filtering changes its position.
     */
    @Composable
    fun row(
        label: String,
        subtitle: String? = null,
        id: String = label,
        render: @Composable () -> Unit
    ) {
        rows += RegisteredRow(id, label, subtitle, render)
    }
}

internal data class RegisteredRow(
    val id: String,
    val label: String,
    val subtitle: String?,
    val render: @Composable () -> Unit
) {
    fun matches(query: String): Boolean =
        label.containsCaseInsensitive(query) ||
            (subtitle?.containsCaseInsensitive(query) == true)
}

/**
 * Tracks whether any [SearchableSection] in the parent composable
 * emitted UI during the current composition. The parent calls
 * [reset] at the top of each pass and reads [anyEmitted] after the
 * sections to decide whether to show the empty-state composable.
 *
 * Designed to be created via `remember { SearchEmissionTracker() }`
 * so it survives recompositions cheaply, and reset imperatively at
 * the top of the composition (the reset is idempotent on a fresh
 * recomposition pass since the only reads happen after every section
 * has had a chance to write).
 */
class SearchEmissionTracker {
    var anyEmitted: Boolean = false
        private set

    fun reset() {
        anyEmitted = false
    }

    fun onEmitted() {
        anyEmitted = true
    }
}

/**
 * Empty-state composable shown when the user's query matches no rows.
 * Announces the headline as a single TalkBack line.
 */
@Composable
fun SearchEmptyState(
    query: String,
    modifier: Modifier = Modifier
) {
    val message = stringResource(R.string.settings_search_no_results, query)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
