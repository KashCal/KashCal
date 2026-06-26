package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.SearchPillTextField
import org.onekash.kashcal.domain.catalog.HolidayCatalogEntry
import org.onekash.kashcal.domain.catalog.filterCatalog
import org.onekash.kashcal.domain.catalog.loadHolidayCatalog
import org.onekash.kashcal.domain.catalog.markAlreadyAdded

private const val LICENSE_URL = "https://creativecommons.org/licenses/by-sa/3.0/"

/**
 * Bottom sheet that lets the user subscribe to a country's public holiday
 * calendar in one tap. Entries come from the bundled catalog (pointers to
 * externally-hosted feeds); tapping one validates the feed via the shared
 * [fetchCalendarInfo] before [onPick] is invoked. Already-subscribed entries
 * are shown disabled. The caller supplies the subscription color.
 *
 * @param subscribedUrls URLs of feeds the user already subscribes to, used to
 *   mark catalog entries as already added.
 * @param onPick Invoked with (url, name) once a tapped feed validates.
 * @param onDismiss Invoked when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HolidayCatalogPicker(
    subscribedUrls: Set<String>,
    onPick: (url: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val entries = remember { loadHolidayCatalog(context) }

    var query by rememberSaveable { mutableStateOf("") }
    // URL of the entry currently being validated, or null when idle.
    var validatingUrl by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filtered = remember(entries, query, subscribedUrls) {
        markAlreadyAdded(filterCatalog(entries, query), subscribedUrls)
    }

    // Validate the tapped feed in an effect keyed to its URL: dismissing the
    // sheet or tapping another entry changes/clears the key, cancelling the
    // in-flight fetch so a late result can never fire onPick.
    LaunchedEffect(validatingUrl) {
        val url = validatingUrl ?: return@LaunchedEffect
        val entry = entries.firstOrNull { it.url == url } ?: return@LaunchedEffect
        when (val result = fetchCalendarInfo(url)) {
            is FetchCalendarState.Success -> {
                validatingUrl = null
                onPick(url, entry.name)
            }
            is FetchCalendarState.Error -> {
                validatingUrl = null
                errorMessage = result.message
            }
            else -> validatingUrl = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // Pin the sheet to the top of the screen so the list has a stable,
        // full-height area to scroll within instead of resizing to content.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(bottom = 24.dp),
        ) {
            AttributionFooter()

            SearchPillTextField(
                query = query,
                onQueryChange = {
                    query = it
                    errorMessage = null
                },
                placeholder = stringResource(R.string.holiday_catalog_search_label),
                leadingIcon = Icons.Default.Search,
                enabled = validatingUrl == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )

            errorMessage?.let { msg ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.holiday_catalog_validate_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (filtered.isEmpty()) {
                CatalogEmptyState()
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.entry.url }) { marked ->
                        CatalogRow(
                            entry = marked.entry,
                            alreadyAdded = marked.alreadyAdded,
                            validating = validatingUrl == marked.entry.url,
                            // Disable taps while any validation is in flight.
                            enabled = validatingUrl == null,
                            onClick = {
                                errorMessage = null
                                validatingUrl = marked.entry.url
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(
    entry: HolidayCatalogEntry,
    alreadyAdded: Boolean,
    validating: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val clickable = enabled && !alreadyAdded
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (clickable) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                validating -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                alreadyAdded -> Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = AccentColors.Green,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (alreadyAdded) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        if (alreadyAdded) {
            Text(
                stringResource(R.string.holiday_catalog_already_added),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CatalogEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.holiday_catalog_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(2.dp))
        Text(
            stringResource(R.string.holiday_catalog_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun AttributionFooter() {
    val licenseName = stringResource(R.string.holiday_catalog_attribution_license)
    val template = stringResource(R.string.holiday_catalog_attribution, licenseName)
    // Split the template around the license token so the license becomes a
    // tappable link while the rest stays plain text.
    val before = template.substringBefore(licenseName)
    val after = template.substringAfter(licenseName)
    val annotated = buildAnnotatedString {
        append(before)
        withLink(
            LinkAnnotation.Url(
                LICENSE_URL,
                TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            ),
        ) {
            append(licenseName)
        }
        append(after)
    }
    Text(
        annotated,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp, bottom = 4.dp),
    )
}
