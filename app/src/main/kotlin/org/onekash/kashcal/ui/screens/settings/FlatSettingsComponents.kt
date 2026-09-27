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
 * Draws a flat settings row: optional icon, label with optional [badge], optional subtitle, and
 * a trailing value plus chevron or custom [trailing] content.
 *
 * @param iconEmoji emoji shown when [icon] is null
 * @param value shown at the end of the row
 * @param badge rendered inline after the label, for example [BetaBadge]
 * @param trailing replaces the chevron
 * @param showChevron defaults to true only when [trailing] is null
 * @param searchQuery when not blank, its matches in the label, subtitle and value are
 *   highlighted
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Highlighted too: a row can match on its value alone when the value is
                // registered as its search subtitle, and an unhighlighted match gives no cue
                // why the row surfaced.
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

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp), // Aligns with the text after the icon
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * Draws a settings row with a switch; tapping anywhere on the row toggles it. The whole row is
 * the touch target, so the [Switch] drops the 48dp minimum and the row keeps the single-line
 * height of neighbouring [SettingsRow]s.
 *
 * @param subtitle omit for single-line height
 * @param iconEmoji emoji shown when [icon] is null
 * @param info explanation shown by a trailing ⓘ button ([SettingsInfoButton]); tapping ⓘ
 *   doesn't toggle the row
 * @param badge rendered inline after the label, for example [BetaBadge]
 * @param searchQuery when not blank, its matches in the label and subtitle are highlighted
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (info != null) {
                    SettingsInfoButton(info)
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Switch(
                        checked = checked,
                        onCheckedChange = onCheckedChange
                    )
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * Holds the content of a settings row's ⓘ tooltip.
 *
 * @param title tooltip title, also used in the ⓘ button's content description
 * @param text explanation shown inside the rich tooltip
 */
data class SettingsRowInfo(
    val title: String,
    val text: String
)

/**
 * Draws an ⓘ button that shows a persistent [RichTooltip] in place, without a sheet. This
 * [IconButton] consumes the click, so it doesn't toggle the row it sits on.
 *
 * @param compact when true, the default for dense settings rows, the button is glyph-sized and
 *   drops the 48dp minimum touch target so the row keeps single-line height. Pass false on
 *   taller rows that want the full 48dp target.
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
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                button()
            }
        } else {
            button()
        }
    }
}

/**
 * Shows the app version at the bottom of the settings screen. A tap calls [onClick]; a long
 * press gives haptic feedback and calls [onLongPress], which the settings screen uses to open
 * the debug menu.
 *
 * @param versionName app version, for example "2026.09.25-1"
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

/** Draws a [SettingsRow] whose value is [badgeCount] in parentheses, such as "(3)". */
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
 * Returns the highlight style for search matches in settings rows: primary container colors,
 * which stay legible in light and dark themes.
 */
@Composable
internal fun settingsSearchHighlightStyle(): SpanStyle = SpanStyle(
    background = MaterialTheme.colorScheme.primaryContainer,
    color = MaterialTheme.colorScheme.onPrimaryContainer
)
