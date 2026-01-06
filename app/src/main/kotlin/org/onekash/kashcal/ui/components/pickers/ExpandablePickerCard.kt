package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Reusable expandable picker card component.
 *
 * Provides a consistent UI pattern for picker cards with:
 * - Header row with label, value, and expand/collapse icon
 * - Animated expandable content area
 * - Focus management (clears focus on toggle)
 *
 * Used as a base component for calendar picker, reminder picker, etc.
 *
 * @param label Left-aligned label (e.g., "Calendar", "Reminder")
 * @param value Right-aligned current value display
 * @param isExpanded Whether the content is visible
 * @param onToggle Called when user taps header to toggle expansion
 * @param enabled When false, card is dimmed and not clickable
 * @param content Composable content to show when expanded
 */
@Composable
fun ExpandablePickerCard(
    label: String,
    value: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val focusManager = LocalFocusManager.current
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        focusManager.clearFocus()
                        onToggle()
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    content()
                }
            }
        }
    }
}

/**
 * Expandable picker card with custom header content.
 *
 * Use this variant when you need custom content in the header row,
 * such as color dots, icons, or multiple text elements.
 *
 * @param label Left-aligned label
 * @param isExpanded Whether the content is visible
 * @param onToggle Called when user taps header to toggle expansion
 * @param enabled When false, card is dimmed and not clickable
 * @param headerContent Custom content for the right side of the header
 * @param content Composable content to show when expanded
 */
@Composable
fun ExpandablePickerCardWithCustomHeader(
    label: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    headerContent: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val focusManager = LocalFocusManager.current
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        focusManager.clearFocus()
                        onToggle()
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    headerContent()
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    content()
                }
            }
        }
    }
}
