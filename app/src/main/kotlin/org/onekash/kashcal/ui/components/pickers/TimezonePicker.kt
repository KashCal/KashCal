package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.util.TimezoneUtils

/**
 * Timezone picker chip with search dropdown.
 *
 * Displays a compact chip showing the current timezone abbreviation.
 * When tapped, opens a search box to find timezones.
 *
 * Design: F2d - positioned to the right of time picker wheels.
 *
 * @param selectedTimezone Current timezone ID (null = device default)
 * @param onTimezoneSelected Called when user selects a timezone
 * @param referenceTimeMs Time used for local preview calculation
 * @param modifier Modifier for the root composable
 * @param onSearchOpenChange Called when search state changes (for parent layout coordination)
 */
@Composable
fun TimezonePickerChip(
    selectedTimezone: String?,
    onTimezoneSelected: (String?) -> Unit,
    referenceTimeMs: Long,
    modifier: Modifier = Modifier,
    onSearchOpenChange: ((Boolean) -> Unit)? = null
) {
    var isSearchOpen by remember { mutableStateOf(false) }

    // Notify parent when search state changes (for layout coordination)
    LaunchedEffect(isSearchOpen) {
        onSearchOpenChange?.invoke(isSearchOpen)
    }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Current timezone info
    val currentTzId = selectedTimezone ?: TimezoneUtils.getDeviceTimezone()
    val abbreviation = TimezoneUtils.getAbbreviation(currentTzId)
    val localPreview = TimezoneUtils.formatLocalTimePreview(referenceTimeMs, selectedTimezone)

    // Search results
    val searchResults = remember(searchQuery) {
        if (searchQuery.isNotBlank()) {
            TimezoneUtils.searchTimezones(searchQuery)
        } else {
            emptyList()
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Chip or Search Box
        AnimatedVisibility(
            visible = !isSearchOpen,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            // Timezone chip - styled to match time picker's center selection highlight (32.dp height)
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { isSearchOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Dynamic text size based on abbreviation length
                    // Short (3-4 chars like "EST"): labelLarge
                    // Medium (5-7 chars like "GMT+5"): labelMedium
                    // Long (8+ chars like "GMT+11:00"): labelSmall
                    val textStyle = when {
                        abbreviation.length <= 4 -> MaterialTheme.typography.labelLarge
                        abbreviation.length <= 7 -> MaterialTheme.typography.labelMedium
                        else -> MaterialTheme.typography.labelSmall
                    }
                    Text(
                        text = abbreviation,
                        style = textStyle,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = "Change timezone",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isSearchOpen,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            // Search dropdown - expands to full available width
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column {
                    // Search input
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                singleLine = true,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (searchResults.isNotEmpty()) {
                                            onTimezoneSelected(searchResults.first().zoneId)
                                            isSearchOpen = false
                                            searchQuery = ""
                                            focusManager.clearFocus()
                                        }
                                    }
                                )
                            )
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                isSearchOpen = false
                                searchQuery = ""
                                focusManager.clearFocus()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    // Search results
                    if (searchResults.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 150.dp)
                        ) {
                            items(searchResults) { tzInfo ->
                                TimezoneResultItem(
                                    tzInfo = tzInfo,
                                    onClick = {
                                        onTimezoneSelected(tzInfo.zoneId)
                                        isSearchOpen = false
                                        searchQuery = ""
                                        focusManager.clearFocus()
                                    }
                                )
                            }
                        }
                    }

                    // "Use device" option when search is active
                    if (selectedTimezone != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onTimezoneSelected(null)
                                    isSearchOpen = false
                                    searchQuery = ""
                                    focusManager.clearFocus()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = "Use device timezone",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Auto-focus search input when opened
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
        }

        // Local time preview (shown below chip when timezone differs from device)
        if (!isSearchOpen && localPreview != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = localPreview,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/**
 * Single timezone result item in the search dropdown.
 */
@Composable
private fun TimezoneResultItem(
    tzInfo: TimezoneUtils.TimezoneInfo,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tzInfo.abbreviation,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = tzInfo.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = tzInfo.offsetFromDevice,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
