package org.onekash.kashcal.ui.components.attendees

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.R

val RESPOND_PILL_HEIGHT_DP = 32.dp

/**
 * Three glyph-only RSVP pills (Accept / Tentative / Decline) used by both
 * the EventQuickViewSheet "Respond" section and the EventFormSheet
 * read-only banner.
 *
 * The button corresponding to [currentUserPartstat] renders filled-primary;
 * the others render with a per-status glyph tint (success-green, amber,
 * error-red) on a neutral tonal background. Accessibility content
 * descriptions use the existing rsvp_action_* string resources so
 * TalkBack still announces "Accept" / "Tentative" / "Decline".
 *
 * Hoisted from EventQuickViewSheet so EventFormSheet's banner can reuse
 * the exact same pill row.
 */
@Composable
fun RespondSection(
    currentUserPartstat: AttendeeStatus?,
    onRsvp: (AttendeeStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        RespondButton(
            status = AttendeeStatus.Accepted,
            glyph = "✓",
            currentUserPartstat = currentUserPartstat,
            onClick = { onRsvp(AttendeeStatus.Accepted) },
            modifier = Modifier.weight(1f)
        )
        RespondButton(
            status = AttendeeStatus.Tentative,
            glyph = "?",
            currentUserPartstat = currentUserPartstat,
            onClick = { onRsvp(AttendeeStatus.Tentative) },
            modifier = Modifier.weight(1f)
        )
        RespondButton(
            status = AttendeeStatus.Declined,
            glyph = "✕",
            currentUserPartstat = currentUserPartstat,
            onClick = { onRsvp(AttendeeStatus.Declined) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RespondButton(
    status: AttendeeStatus,
    glyph: String,
    currentUserPartstat: AttendeeStatus?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSelected = isRespondButtonSelected(status, currentUserPartstat)
    val tintRole = respondGlyphTintRole(status, currentUserPartstat)
    val a11yLabel = stringResource(status.respondActionLabelResId)
    val haptic = LocalHapticFeedback.current

    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        // surfaceContainerHighest is lower-chroma than the default
        // secondaryContainer, giving the success/amber/error glyph tints
        // enough luminance contrast to read clearly under Material You.
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        glyphTintColor(tintRole)
    }

    FilledTonalButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier
            .height(RESPOND_PILL_HEIGHT_DP)
            .semantics(mergeDescendants = true) {
                contentDescription = a11yLabel
            },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(
            text = glyph,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun glyphTintColor(role: GlyphTintRole): Color = when (role) {
    GlyphTintRole.Selected -> MaterialTheme.colorScheme.onPrimary
    GlyphTintRole.Success -> MaterialTheme.colorScheme.primary
    GlyphTintRole.Tentative -> MaterialTheme.colorScheme.tertiary
    GlyphTintRole.Error -> MaterialTheme.colorScheme.error
}

private val AttendeeStatus.respondActionLabelResId: Int
    get() = when (this) {
        AttendeeStatus.Accepted -> R.string.rsvp_action_accept
        AttendeeStatus.Tentative -> R.string.rsvp_action_tentative
        AttendeeStatus.Declined -> R.string.rsvp_action_decline
        AttendeeStatus.Delegated, AttendeeStatus.NeedsAction ->
            R.string.rsvp_action_accept // fallback; never surfaced
    }
