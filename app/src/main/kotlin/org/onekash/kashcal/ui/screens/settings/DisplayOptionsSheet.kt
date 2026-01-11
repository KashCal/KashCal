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
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.ui.shared.EVENT_DURATION_OPTIONS

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
                "Event Emojis",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Event Emojis Toggle
            SettingsCard {
                SettingsToggleRow(
                    label = "Auto-detect Emojis",
                    subtitle = "Show emojis based on event titles",
                    checked = showEventEmojis,
                    onCheckedChange = onShowEventEmojisChange,
                    showDivider = false
                )
            }

            // Preview section (animated visibility)
            AnimatedVisibility(visible = showEventEmojis) {
                Column {
                    Text(
                        "PREVIEW",
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
    // Find selected duration option
    val selectedDurationOption = EVENT_DURATION_OPTIONS.find { it.minutes == defaultEventDuration }
        ?: EVENT_DURATION_OPTIONS[1]  // Default to 30 minutes

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
                "Default Event Length",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Duration picker
            SettingsCard {
                SettingsDropdownRow(
                    label = "New events",
                    options = EVENT_DURATION_OPTIONS,
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

/**
 * Preview card showing example event titles with emojis.
 */
@Composable
private fun EmojiPreviewCard() {
    val examples = listOf(
        "Coffee with Sarah",
        "Mom's Birthday",
        "Flight to NYC",
        "Team standup"
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
