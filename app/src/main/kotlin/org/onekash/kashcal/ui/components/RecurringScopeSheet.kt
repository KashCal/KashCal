package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.viewmodels.EditScope

/**
 * Visual weight of a [ScopeOption]: its icon tile, label and border colors.
 *
 * - [Neutral]: the default.
 * - [Warn]: "All events" when editing or moving. Broad but not destructive; the warm tint is a
 *   brake on misclicks.
 * - [Destructive]: "All events" when deleting. The card sits apart from the safe options.
 */
enum class ScopeTint { Neutral, Warn, Destructive }

/**
 * One card in [RecurringScopeSheet], built by the caller. A disabled option stays visible at
 * reduced opacity; the context shows why it doesn't apply.
 */
data class ScopeOption(
    val scope: EditScope,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val tint: ScopeTint = ScopeTint.Neutral,
)

/**
 * Commits an enabled option's scope through [onSelect]; never calls a cancel callback. The host
 * dismisses the sheet by clearing its pending state when [onSelect] fires. A cancel call here
 * would race the host's cancel handler against an in-flight save and re-enable Save mid-flight.
 */
internal fun scopeOptionTap(option: ScopeOption, onSelect: (EditScope) -> Unit) {
    if (!option.enabled) return
    onSelect(option.scope)
}

/**
 * Asks how far an edit, delete or drag-reschedule applies across a recurring series. One tap
 * commits: a card fires [onSelect] with no second confirmation, and the host dismisses the
 * sheet. Cancel, or a tap outside, calls [onCancel] without applying the change.
 *
 * Stateless apart from the sheet state; the options, enabled flags and tints come from the
 * caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScopeSheet(
    title: String,
    options: List<ScopeOption>,
    onSelect: (EditScope) -> Unit,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        dragHandle = {},
        sheetGesturesEnabled = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            )

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEachIndexed { index, option ->
                    // A gap sets the destructive card apart from the safe options.
                    if (option.tint == ScopeTint.Destructive && index > 0) {
                        Spacer(Modifier.height(4.dp))
                    }
                    ScopeOptionCard(
                        option = option,
                        onSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scopeOptionTap(option, onSelect)
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // A centered text button below the cards, so it doesn't read as another scope.
            Text(
                text = stringResource(R.string.action_cancel),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCancel() }
                    .padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun ScopeOptionCard(
    option: ScopeOption,
    onSelect: () -> Unit,
) {
    val tileBg = when {
        !option.enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        option.tint == ScopeTint.Destructive -> MaterialTheme.colorScheme.errorContainer
        option.tint == ScopeTint.Warn -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val tileFg = when {
        !option.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        option.tint == ScopeTint.Destructive -> MaterialTheme.colorScheme.onErrorContainer
        option.tint == ScopeTint.Warn -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val labelColor = when {
        !option.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        option.tint == ScopeTint.Destructive -> MaterialTheme.colorScheme.error
        option.tint == ScopeTint.Warn -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderColor = when {
        !option.enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        option.tint == ScopeTint.Destructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (option.enabled) it.clickable { onSelect() } else it },
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (option.enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tileBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = option.icon,
                    contentDescription = null,
                    tint = tileFg,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = labelColor,
            )
        }
    }
}
