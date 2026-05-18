package org.onekash.kashcal.ui.components.attendees

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import org.onekash.kashcal.R

/**
 * Read-only chip row for an event's attendees.
 *
 * Three render modes via [AttendeeChipRowState.compute]:
 * - [AttendeeChipRowMode.Empty] — renders nothing (no spacer, no container)
 * - [AttendeeChipRowMode.LavenderCount] — collapsed default for off-list
 *   events; tap flips `expanded` so the same row re-renders as Inline
 * - [AttendeeChipRowMode.Inline] — wrap-able chip flow with either a
 *   "+N more" (on-list collapsed) or "Show less" (any expanded) disclosure
 *
 * Inline expand stays inline (compose state) — Material 3 forbids nested
 * ModalBottomSheets so there is no secondary sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttendeeChipRow(
    models: List<AttendeeUiModel>,
    isCurrentUserOnList: Boolean,
    modifier: Modifier = Modifier
) {
    // Don't key remember on `models` — every Flow re-emit churns the list and
    // collapsing the disclosure mid-tap would drop the user's intent.
    var expanded by remember { mutableStateOf(false) }
    val mode = remember(models, isCurrentUserOnList, expanded) {
        AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = isCurrentUserOnList,
            expanded = expanded
        )
    }

    when (mode) {
        AttendeeChipRowMode.Empty -> Unit

        is AttendeeChipRowMode.LavenderCount -> {
            // Talkback action label only — the chip's visible "N attendees"
            // text remains the primary spoken readout. Without `onClick(label =
            // ...)`, screen readers announce just the count and "double-tap to
            // activate" without naming the action; with it, they say
            // "5 attendees, double-tap to show all attendees".
            val showAllAction = stringResource(R.string.attendee_show_all)
            AssistChip(
                onClick = { expanded = true },
                modifier = modifier
                    .testTag(TEST_TAG_LAVENDER_COUNT)
                    .semantics { onClick(label = showAllAction, action = null) },
                label = {
                    Text(
                        text = pluralStringResource(
                            R.plurals.attendee_count_off_list,
                            mode.totalCount,
                            mode.totalCount
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            )
        }

        is AttendeeChipRowMode.Inline -> {
            FlowRow(
                modifier = modifier
                    .fillMaxWidth()
                    .testTag(TEST_TAG_CHIP_ROW),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                mode.visible.forEach { model ->
                    // Slot identity tracks the attendee, not the list position,
                    // so per-chip state survives Flow re-emits.
                    key(model.bareAddress, model.isSynthesized) {
                        AttendeeChip(model = model)
                    }
                }
                // Render the disclosure when the row is collapsed-with-hidden
                // OR currently expanded (so the user can collapse back).
                // Without the second condition, expanding makes hiddenCount=0
                // and the chip disappears — leaving the user no way to collapse.
                if (expanded || mode.hiddenCount > 0) {
                    AssistChip(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.testTag(TEST_TAG_SHOW_MORE),
                        label = {
                            Text(
                                text = if (expanded) {
                                    stringResource(R.string.attendee_show_less)
                                } else {
                                    pluralStringResource(
                                        R.plurals.attendee_show_n_more,
                                        mode.hiddenCount,
                                        mode.hiddenCount
                                    )
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * Compact day-card status badge — count + a single status indicator.
 * Renders nothing when [models] is empty.
 *
 * - Hosting (organizer): 👑 N · Hosting
 * - Off-list: 👥 N (lavender, count only — same tertiaryContainer as the
 *   full chip row's lavender variant)
 * - On-list: 👥 N · {Going|Pending|Declined|Tentative}
 */
@Composable
fun EventCardAttendeeBadge(
    models: List<AttendeeUiModel>,
    modifier: Modifier = Modifier
) {
    if (models.isEmpty()) return

    val you = models.firstOrNull { it.isYou }
    // Exclude the user themselves from the badge count — "👥 5" should
    // mean "5 OTHER people," not "5 including you." Counting `!isYou`
    // handles multi-alias users where multiple rows match the account
    // (e.g. me.com + icloud.com both on the attendee list).
    val count = models.count { !it.isYou }
    if (count <= 0) return
    val (badgeIcon, label, color) = when {
        you?.isOrganizer == true -> Triple(
            "👑",
            androidx.compose.ui.res.stringResource(R.string.attendee_card_hosting),
            MaterialTheme.colorScheme.primary
        )
        you == null -> Triple(
            "👥",
            null, // count-only when off-list
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        you.status == AttendeeStatus.Accepted -> Triple(
            "👥",
            androidx.compose.ui.res.stringResource(R.string.attendee_card_going),
            MaterialTheme.colorScheme.primary
        )
        you.status == AttendeeStatus.Declined -> Triple(
            "👥",
            androidx.compose.ui.res.stringResource(R.string.attendee_card_declined),
            MaterialTheme.colorScheme.error
        )
        you.status == AttendeeStatus.Tentative -> Triple(
            "👥",
            androidx.compose.ui.res.stringResource(R.string.attendee_card_tentative),
            MaterialTheme.colorScheme.secondary
        )
        else -> Triple(
            "👥",
            androidx.compose.ui.res.stringResource(R.string.attendee_card_pending),
            MaterialTheme.colorScheme.tertiary
        )
    }

    Row(
        modifier = modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "$badgeIcon $count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        if (label != null) {
            Text(
                text = "·",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}
