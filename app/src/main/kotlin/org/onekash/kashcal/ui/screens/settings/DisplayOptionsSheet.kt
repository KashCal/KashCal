package org.onekash.kashcal.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.ui.shared.getEventDurationOptions

/**
 * Bottom sheet for event emoji settings.
 *
 * Shows toggle for auto-detecting emojis from event titles,
 * with animated preview of example events.
 *
 * @param sheetState Material3 sheet state
 * @param showEventEmojis Current emoji preference value
 * @param onShowEventEmojisChange Callback when preference changes
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEmojisSheet(
    sheetState: SheetState,
    showEventEmojis: Boolean,
    onShowEventEmojisChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                stringResource(R.string.settings_event_emojis),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Event Emojis Toggle
            SettingsCard {
                SettingsToggleRow(
                    label = stringResource(R.string.settings_auto_detect_emojis),
                    subtitle = stringResource(R.string.settings_emoji_subtitle),
                    checked = showEventEmojis,
                    onCheckedChange = onShowEventEmojisChange,
                    showDivider = false
                )
            }

            // Preview section (animated visibility)
            AnimatedVisibility(visible = showEventEmojis) {
                Column {
                    Text(
                        stringResource(R.string.settings_preview),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = 24.dp,
                            end = 24.dp,
                            top = 16.dp,
                            bottom = 8.dp
                        )
                    )
                    EmojiPreviewCard()
                }
            }
        }
    }
}

/**
 * Bottom sheet for default event duration setting.
 *
 * Shows duration options for new events.
 *
 * @param sheetState Material3 sheet state
 * @param defaultEventDuration Current default event duration (minutes)
 * @param onEventDurationChange Callback when duration changes
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDurationSheet(
    sheetState: SheetState,
    defaultEventDuration: Int,
    onEventDurationChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val durationOptions = getEventDurationOptions(LocalResources.current)
    val selectedDurationOption = durationOptions.find { it.minutes == defaultEventDuration }
        ?: durationOptions[1]  // Default to 30 minutes

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                stringResource(R.string.settings_default_event_length),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Duration picker
            SettingsCard {
                SettingsDropdownRow(
                    label = stringResource(R.string.settings_new_events),
                    options = durationOptions,
                    selectedOption = selectedDurationOption,
                    onOptionSelected = { onEventDurationChange(it.minutes) },
                    optionLabel = { it.label },
                    iconEmoji = "🕐",
                    showDivider = false
                )
            }
        }
    }
}

private val WIDGET_EVENT_LIMIT_OPTIONS = listOf(3, 5, 8, 10, 15)

/**
 * Bottom sheet for configuring widget event limit.
 *
 * @param sheetState Material3 sheet state
 * @param currentLimit Current widget event limit
 * @param onLimitChange Callback when limit changes
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetEventLimitSheet(
    sheetState: SheetState,
    currentLimit: Int,
    onLimitChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedOption = WIDGET_EVENT_LIMIT_OPTIONS.find { it == currentLimit } ?: 5

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.settings_widget_event_limit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            val eventsDefaultTemplate = stringResource(R.string.settings_n_events_default)
            val eventsTemplate = stringResource(R.string.settings_n_events)
            SettingsCard {
                SettingsDropdownRow(
                    label = stringResource(R.string.settings_events_per_day),
                    options = WIDGET_EVENT_LIMIT_OPTIONS,
                    selectedOption = selectedOption,
                    onOptionSelected = { onLimitChange(it) },
                    optionLabel = { if (it == 5) eventsDefaultTemplate.format(it) else eventsTemplate.format(it) },
                    showDivider = false
                )
            }
        }
    }
}

/**
 * Preview card showing example event titles with emojis.
 */
@Composable
private fun EmojiPreviewCard() {
    val examples = listOf(
        "Coffee with Kash",
        "Run in the park",
        "Movie night",
        "Mom's Birthday"
    )

    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            examples.forEach { title ->
                val displayTitle = EmojiMatcher.formatWithEmoji(title, true)
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}
