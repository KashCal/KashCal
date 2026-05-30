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
 * Visual weighting for a [ScopeOption]. Drives the card's icon-tile
 * tint and label color.
 *
 * - [Neutral] — default treatment.
 * - [Warn] — "All events" on the edit/move flow. Broad change but
 *   not destructive; the warm tint is a subtle brake on misclicks.
 * - [Destructive] — "All events" on the delete flow, where the
 *   action is genuinely destructive. The destructive card sits in
 *   its own visual group separated from the safe options.
 */
enum class ScopeTint { Neutral, Warn, Destructive }

/**
 * One card in [RecurringScopeSheet]. Computed by callers so the
 * sheet itself stays stateless. Disabled options stay visible at
 * reduced opacity — the user infers from context why they don't
 * apply.
 */
data class ScopeOption(
    val scope: EditScope,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val tint: ScopeTint = ScopeTint.Neutral,
)

/**
 * Pure handler for a scope-option tap. Commits the option's scope
 * if enabled; never invokes a cancel callback. The host is
 * responsible for dismissing the sheet by clearing the pending
 * state when the select callback fires — adding a parallel cancel
 * call here used to race the host's cancel handler against an
 * in-flight save and re-enable the Save button mid-flight.
 */
internal fun scopeOptionTap(option: ScopeOption, onSelect: (EditScope) -> Unit) {
    if (!option.enabled) return
    onSelect(option.scope)
}

/**
 * Bottom sheet that asks the user how broadly an edit, delete, or
 * drag-reschedule should apply across a recurring series. Single-tap
 * commits — picking a card fires [onSelect] and dismisses the
 * sheet, no second confirmation. Cancel returns to whatever screen
 * triggered the sheet without applying the change.
 *
 * Stateless aside from the sheet state itself; the option list and
 * its enabled / tinted attributes come entirely from the caller.
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
                    // Insert a small gap above the destructive card so
                    // it visually separates from the safe options.
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

            // Cancel sits below the cards as a centered text-button.
            // It's clearly its own visual category — not a fourth
            // scope.
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
