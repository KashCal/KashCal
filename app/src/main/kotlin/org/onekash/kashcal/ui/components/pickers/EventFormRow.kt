package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

@Composable
fun EventFormRow(
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    iconContentDescription: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    isExpanded: Boolean = false,
    showExpandIcon: Boolean = false,
    onToggle: (() -> Unit)? = null,
    enabled: Boolean = true,
    // Vertical alignment of the icon and content. Rows whose content is a
    // single line stay centered (the default); a row with a tall multi-line
    // field (e.g. Notes) passes Top so the icon lines up with the first line
    // instead of floating to the field's vertical middle.
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    // Extra top offset for the icon when [verticalAlignment] is Top. Tall rows
    // start their content below the row top by different amounts — a text
    // field carries internal top padding, a chip/button row is centered in a
    // taller box — so each Top-aligned row sets the offset that drops its icon
    // onto its own first line. Ignored when centered.
    iconTopPadding: Dp = 0.dp,
    expandedContent: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    Column(modifier = modifier.alpha(if (enabled) 1f else 0.6f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onToggle != null && enabled)
                        Modifier.clickable { onToggle() }
                    else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = verticalAlignment
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = iconContentDescription,
                    tint = iconTint,
                    // When top-aligned, drop the icon by the row-specific
                    // offset so it meets that row's first line (a text field's
                    // first line, a chip row's chips, etc.).
                    modifier = Modifier
                        .then(
                            if (verticalAlignment == Alignment.Top && iconTopPadding > 0.dp)
                                Modifier.padding(top = iconTopPadding)
                            else Modifier
                        )
                        .size(24.dp)
                )
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = verticalAlignment
            ) {
                content()
            }

            if (showExpandIcon) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (expandedContent != null) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    expandedContent()
                }
            }
        }
    }
}
