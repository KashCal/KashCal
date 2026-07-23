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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.util.text.containsCaseInsensitive
import org.onekash.kashcal.ui.util.text.highlighted

/** Test tag on the between-groups leading divider, so its presence/count is assertable. */
internal const val SEARCHABLE_SECTION_LEADING_DIVIDER_TAG = "searchable_section_leading_divider"

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
 * Layout matches the account hub: a flat column of rows with a
 * primary-colored header and no card background. A divider separates
 * groups only — drawn *before* this section when an earlier section
 * already emitted (tracked via [tracker]), so no rule hangs above the
 * first group or below the last. The section reads [tracker] before
 * recording its own emission, so the divider decision and the
 * empty-state accounting stay in one place. Callers MUST NOT pass
 * `showDivider` to row composables — leaving the default `false` keeps
 * rows flush, which is what the between-groups-only rule requires.
 */
@Composable
fun SearchableSection(
    query: String,
    modifier: Modifier = Modifier,
    header: String? = null,
    tracker: SearchEmissionTracker? = null,
    content: @Composable SearchableSectionScope.() -> Unit
) {
    val scope = SearchableSectionScope()
    scope.content()

    // A query that matches the section header surfaces the whole group, so a
    // user searching "appearance" finds every setting under that header even
    // when no individual row label contains the term.
    val headerMatches = !query.isBlank() &&
        header?.containsCaseInsensitive(query) == true
    val visibleRows = if (query.isBlank() || headerMatches) {
        scope.rows
    } else {
        scope.rows.filter { it.matches(query) }
    }
    if (visibleRows.isEmpty()) return

    // Read emission state (did an earlier section render?) BEFORE recording
    // this section's own emission, so the leading divider is drawn only
    // between groups — never above the first or below the last.
    val showLeadingDivider = tracker?.anyEmitted == true
    tracker?.onEmitted()

    Column(modifier = modifier) {
        if (showLeadingDivider) {
            HorizontalDivider(
                modifier = Modifier
                    .testTag(SEARCHABLE_SECTION_LEADING_DIVIDER_TAG)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
        // Highlight the header only when the query actually matched it, so a
        // header-driven hit shows the user why the section surfaced.
        if (header != null) {
            FlatSectionHeader(header, if (headerMatches) query else "")
        }
        visibleRows.forEach { row ->
            key(row.id) { row.render() }
        }
    }
}

/**
 * Section header in the account-hub style: a primary-colored [titleMedium]
 * with no card wrapper. Kept local to the flat settings list so the shared
 * [SectionHeader] (used by other screens with a different look) is untouched.
 * When [highlightQuery] is non-blank, the matching substring is highlighted
 * (used when the search query matched the header text itself).
 */
@Composable
private fun FlatSectionHeader(text: String, highlightQuery: String = "") {
    Text(
        text = if (highlightQuery.isBlank()) {
            AnnotatedString(text)
        } else {
            highlighted(text, highlightQuery, settingsSearchHighlightStyle())
        },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() }
    )
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
