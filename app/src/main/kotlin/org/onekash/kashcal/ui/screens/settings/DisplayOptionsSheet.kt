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

/**
 * Bottom sheet for display options.
 *
 * Currently includes:
 * - Event emoji toggle (auto-detect emojis from titles)
 *
 * Future additions planned:
 * - Time format (12h vs 24h)
 *
 * @param sheetState Material3 sheet state
 * @param showEventEmojis Current emoji preference value
 * @param onShowEventEmojisChange Callback when preference changes
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayOptionsSheet(
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
                "Display Options",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Event Emojis Toggle
            SettingsCard {
                SettingsToggleRow(
                    label = "Event Emojis",
                    subtitle = "Auto-detect from event titles",
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
