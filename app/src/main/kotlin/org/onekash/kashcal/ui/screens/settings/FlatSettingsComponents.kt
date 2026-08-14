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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.util.text.highlighted

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
 * @param badge Optional composable rendered inline after the label (e.g., BetaBadge)
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
    badge: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    showChevron: Boolean = trailing == null,
    showDivider: Boolean = true,
    searchQuery: String = ""
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (searchQuery.isBlank()) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        } else {
                            Text(
                                highlighted(label, searchQuery, settingsSearchHighlightStyle()),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        badge?.invoke()
                    }
                    if (subtitle != null) {
                        if (searchQuery.isBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                highlighted(subtitle, searchQuery, settingsSearchHighlightStyle()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Trailing section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Value text — highlight the match when searching, since a row can
                // match on its value alone (the value is registered as search text),
                // and an unhighlighted match gives no cue why the row surfaced.
                if (value != null) {
                    if (searchQuery.isBlank()) {
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            highlighted(value, searchQuery, settingsSearchHighlightStyle()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
 * The [Switch] opts out of the 48dp minimum interactive size so the row
 * sits at single-line height, matching the neighbouring [SettingsRow]s
 * instead of standing taller. The whole row is the touch target.
 *
 * @param label Primary text label
 * @param checked Current toggle state
 * @param onCheckedChange Callback when toggle changes
 * @param subtitle Optional secondary text below label (omit for single-line height)
 * @param icon Optional Material icon
 * @param iconEmoji Optional emoji icon (alternative to Material icon)
 * @param info Optional rich-tooltip explanation shown by a trailing ⓘ button;
 *   tapping ⓘ reveals the tooltip without toggling the row.
 * @param badge Optional composable rendered inline after the label (e.g., BetaBadge)
 * @param showDivider Whether to show bottom divider
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconEmoji: String? = null,
    info: SettingsRowInfo? = null,
    badge: @Composable (() -> Unit)? = null,
    showDivider: Boolean = true,
    searchQuery: String = ""
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

                // Label (+ optional inline badge) and (optional) subtitle
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (searchQuery.isBlank()) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        } else {
                            Text(
                                highlighted(label, searchQuery, settingsSearchHighlightStyle()),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        badge?.invoke()
                    }
                    if (subtitle != null) {
                        if (searchQuery.isBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                highlighted(subtitle, searchQuery, settingsSearchHighlightStyle()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Trailing: optional info tooltip + toggle switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (info != null) {
                    SettingsInfoButton(info)
                }
                // Opt the switch out of the 48dp minimum so the row keeps
                // single-line height, matching sibling rows.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Switch(
                        checked = checked,
                        onCheckedChange = onCheckedChange
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

/**
 * Content for the trailing ⓘ tooltip on a settings row.
 *
 * @param title Tooltip title (also the ⓘ button's content description)
 * @param text Supporting explanation shown inside the rich tooltip
 */
data class SettingsRowInfo(
    val title: String,
    val text: String
)

/**
 * Trailing ⓘ button that anchors a dismissible [RichTooltip]. Tapping it
 * shows the explanation in place (no sheet); it does not toggle the row
 * because the click is consumed by this [IconButton].
 *
 * @param compact when true (the default, for dense settings rows) the button
 *   opts out of the 48dp minimum touch target so it doesn't inflate the row
 *   above single-line height. Set false on taller rows that want the full 48dp
 *   accessible target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsInfoButton(info: SettingsRowInfo, compact: Boolean = true) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        tooltip = {
            RichTooltip(
                title = { Text(info.title) },
                text = { Text(info.text) }
            )
        },
        state = tooltipState
    ) {
        val button: @Composable () -> Unit = {
            IconButton(
                onClick = { scope.launch { tooltipState.show() } },
                // Compact keeps the button at glyph size; non-compact lets the
                // IconButton keep its default 48dp accessible touch target.
                modifier = if (compact) Modifier.size(24.dp) else Modifier
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.cd_about_setting, info.title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (compact) {
            // Opt out of the 48dp minimum touch target so it doesn't inflate the
            // row above single-line height (same treatment as the Switch).
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                button()
            }
        } else {
            button()
        }
    }
}

/**
 * Version footer component for the bottom of settings screen.
 *
 * Long-press opens the debug menu.
 *
 * @param versionName App version (e.g., "4.2.4")
 * @param onClick Callback when tapped (opens app info)
 * @param onLongPress Callback when long-pressed (opens debug menu)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VersionFooter(
    versionName: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    val cdVersionInfo = stringResource(R.string.cd_version_info, versionName)
    val longClickLabel = stringResource(R.string.label_open_debug_menu)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
                onLongClickLabel = longClickLabel
            )
            .semantics {
                contentDescription = cdVersionInfo
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.status_version, versionName),
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
    subtitle: String? = null,
    searchQuery: String = ""
) {
    SettingsRow(
        label = label,
        onClick = onClick,
        modifier = modifier,
        iconEmoji = iconEmoji,
        subtitle = subtitle,
        value = "($badgeCount)",
        showChevron = true,
        searchQuery = searchQuery
    )
}

/**
 * Highlight style for matched substrings during settings search. Uses
 * the primary container to read correctly under both light and dark.
 */
@Composable
internal fun settingsSearchHighlightStyle(): SpanStyle = SpanStyle(
    background = MaterialTheme.colorScheme.primaryContainer,
    color = MaterialTheme.colorScheme.onPrimaryContainer
)
