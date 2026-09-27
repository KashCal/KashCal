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

/** Test tag on the between-groups leading divider, so tests can assert its presence and count. */
internal const val SEARCHABLE_SECTION_LEADING_DIVIDER_TAG = "searchable_section_leading_divider"

/**
 * Renders a settings section that takes part in inline search.
 *
 * [content] registers rows on its receiver with [SearchableSectionScope.row]. After it runs, the
 * section uses [query] to pick which rows to render and whether to emit the [header] at all.
 * A blank [query] renders every row. When the section emits anything it calls
 * [SearchEmissionTracker.onEmitted] on [tracker] once during composition, so the parent knows
 * whether any section produced UI and can show the empty state otherwise.
 *
 * Layout matches the account hub: a flat column of rows under a primary-colored header, with no
 * card background. A divider separates groups only: it is drawn before this section when an
 * earlier section already emitted, so no rule hangs above the first group or below the last.
 * Rows must pass `showDivider = false` (the row composables default to true), or each row draws
 * its own rule and breaks the between-groups-only rule.
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

    // A query that matches the header surfaces the whole group, so searching "appearance" finds
    // every setting under that header even when no row label contains the term.
    val headerMatches = !query.isBlank() &&
        header?.containsCaseInsensitive(query) == true
    val visibleRows = if (query.isBlank() || headerMatches) {
        scope.rows
    } else {
        scope.rows.filter { it.matches(query) }
    }
    if (visibleRows.isEmpty()) return

    // Read whether an earlier section rendered before recording this one, so the leading
    // divider is drawn only between groups.
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
        // Highlight the header only when the query matched it, so a header hit shows why the
        // section surfaced.
        if (header != null) {
            FlatSectionHeader(header, if (headerMatches) query else "")
        }
        visibleRows.forEach { row ->
            key(row.id) { row.render() }
        }
    }
}

/**
 * Shows a section header in the account-hub style: primary-colored `titleMedium` text with no
 * card wrapper. Kept local to the flat settings list so [SectionHeader], which other screens use
 * with a different look, stays unchanged. A non-blank [highlightQuery] highlights the matching
 * substring.
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

/** Receives [SearchableSection]'s content block, which registers rows by calling [row]. */
class SearchableSectionScope internal constructor() {
    internal val rows = mutableListOf<RegisteredRow>()

    /**
     * Registers a row in source order; [label] and [subtitle] are what the query matches.
     *
     * [render] runs later in the section's scope, only if the row passes the filter, inside a
     * [key] block on [id] (default [label]) so remembered state stays bound to the row even when
     * filtering changes its position.
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
 * Tracks whether any [SearchableSection] in the parent composable emitted UI during the current
 * composition.
 *
 * Create it with `remember { SearchEmissionTracker() }`. The parent calls [reset] at the top of
 * each pass and reads [anyEmitted] after the sections to decide whether to show
 * [SearchEmptyState]. Resetting every pass is safe because the only reads happen after every
 * section has had a chance to write.
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
 * Shows the empty state when the query matches no rows. TalkBack reads the message as one
 * line.
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
