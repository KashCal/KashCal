package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.onekash.kashcal.R

/**
 * Header strip beneath the drag handle: calendar dot+name pill on the left,
 * Share-as-card icon on the right. Shared by both [EventQuickViewSheet] and
 * [DeviceEventQuickViewSheet] so Room and device-calendar events surface the
 * same share affordance.
 *
 * On first appearance (when [showTooltip] is true), a one-shot tooltip is
 * programmatically shown via [rememberTooltipState] + [TooltipState.show],
 * auto-hides after 5s, and dismisses on tap of the Share icon. Persistence
 * is recorded via [onTooltipDisplayed] BEFORE show() so a sheet-dismiss
 * (which cancels the LaunchedEffect mid-show) doesn't lose the flag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareAsCardTopRow(
    calendarColor: Int,
    calendarName: String,
    onShareClick: () -> Unit,
    showTooltip: Boolean,
    onTooltipDisplayed: () -> Unit,
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val coroutineScope = rememberCoroutineScope()
    val label = stringResource(R.string.share_as_card_action)

    LaunchedEffect(showTooltip) {
        if (showTooltip) {
            // Persist the dismissal flag IMMEDIATELY so a sheet-dismiss
            // (which cancels this LaunchedEffect mid-show) doesn't leave
            // the tooltip un-marked-as-shown for the next open.
            onTooltipDisplayed()
            tooltipState.show()
        }
    }
    // Auto-hide after 5s so the tooltip doesn't linger if the user
    // ignores it. Independent of show() so cancellation is clean.
    LaunchedEffect(showTooltip) {
        if (showTooltip) {
            delay(5000)
            tooltipState.dismiss()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Calendar pill — dot + name. Quietly identifies the calendar this
        // event belongs to.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = Color(calendarColor),
                        shape = RoundedCornerShape(50),
                    ),
            )
            SelectionContainer {
                Text(
                    text = calendarName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                PlainTooltip { Text(label) }
            },
            state = tooltipState,
        ) {
            IconButton(
                onClick = {
                    coroutineScope.launch { tooltipState.dismiss() }
                    onShareClick()
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = label,
                )
            }
        }
    }
}
