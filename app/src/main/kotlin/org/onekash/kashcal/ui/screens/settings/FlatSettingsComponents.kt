package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable flat settings row component.
 *
 * Used for the new flat list design that replaces nested accordions.
 *
 * @param icon Optional Material icon
 * @param iconEmoji Optional emoji icon (alternative to Material icon)
 * @param label Primary text label
 * @param value Optional value displayed on the right
 * @param subtitle Optional secondary text below label
 * @param onClick Callback when row is tapped
 * @param trailing Optional custom trailing composable (overrides default chevron)
 * @param showChevron Whether to show chevron (default true when trailing is null)
 * @param showDivider Whether to show bottom divider
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconEmoji: String? = null,
    value: String? = null,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    showChevron: Boolean = trailing == null,
    showDivider: Boolean = true
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = null
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading icon + text
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Icon (Material or Emoji)
                when {
                    icon != null -> {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    iconEmoji != null -> {
                        Text(iconEmoji, fontSize = 20.sp)
                    }
                }

                // Label and subtitle
                Column {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Trailing section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Value text
                if (value != null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Custom trailing or chevron
                if (trailing != null) {
                    trailing()
                } else if (showChevron) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Divider
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp), // Align with text after icon
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * Settings row with toggle switch.
 *
 * Used for boolean preferences that can be toggled on/off.
 * The entire row is clickable to toggle the switch.
 *
 * @param label Primary text label
 * @param subtitle Secondary text below label
 * @param checked Current toggle state
 * @param onCheckedChange Callback when toggle changes
 * @param icon Optional Material icon
 * @param iconEmoji Optional emoji icon (alternative to Material icon)
 * @param showDivider Whether to show bottom divider
 */
@Composable
fun SettingsToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconEmoji: String? = null,
    showDivider: Boolean = true
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading icon + text
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Icon (Material or Emoji)
                when {
                    icon != null -> {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    iconEmoji != null -> {
                        Text(iconEmoji, fontSize = 20.sp)
                    }
                }

                // Label and subtitle
                Column {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Toggle switch
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }

        // Divider
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * Version footer component for the bottom of settings screen.
 *
 * Long-press opens the debug menu.
 *
 * @param versionName App version (e.g., "4.2.4")
 * @param onLongPress Callback when long-pressed (opens debug menu)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VersionFooter(
    versionName: String,
    onLongPress: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .combinedClickable(
                onClick = { /* No action on regular click */ },
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
                onLongClickLabel = "Open debug menu"
            )
            .semantics {
                contentDescription = "KashCal version $versionName. Long press for debug options."
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "KashCal v$versionName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Settings row with badge indicator (e.g., subscription count).
 *
 * @param label Primary text label
 * @param badgeCount Number to display in badge
 * @param onClick Callback when row is tapped
 * @param iconEmoji Emoji icon
 * @param subtitle Optional secondary text
 */
@Composable
fun SettingsRowWithBadge(
    label: String,
    badgeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconEmoji: String? = null,
    subtitle: String? = null
) {
    SettingsRow(
        label = label,
        onClick = onClick,
        modifier = modifier,
        iconEmoji = iconEmoji,
        subtitle = subtitle,
        value = "($badgeCount)",
        showChevron = true
    )
}

/**
 * Settings row with dropdown selector.
 *
 * Shows label + current value, click opens dropdown menu with options.
 * Compact alternative to expanded radio lists for settings screens.
 *
 * @param label Primary text label
 * @param options List of available options
 * @param selectedOption Currently selected option
 * @param onOptionSelected Callback when an option is selected
 * @param optionLabel Function to get display label for each option
 * @param icon Optional Material icon
 * @param iconEmoji Optional emoji icon (alternative to Material icon)
 * @param showDivider Whether to show bottom divider
 */
@Composable
fun <T> SettingsDropdownRow(
    label: String,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconEmoji: String? = null,
    showDivider: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Leading icon + label
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Icon (Material or Emoji)
                    when {
                        icon != null -> {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        iconEmoji != null -> {
                            Text(iconEmoji, fontSize = 20.sp)
                        }
                    }

                    // Label
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                // Trailing: selected value + dropdown indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        optionLabel(selectedOption),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Select option",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Dropdown menu
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                optionLabel(option),
                                style = if (option == selectedOption) {
                                    MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    MaterialTheme.typography.bodyLarge
                                }
                            )
                        },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }

        // Divider
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}
