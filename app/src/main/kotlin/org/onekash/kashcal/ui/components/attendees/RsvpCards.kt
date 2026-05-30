package org.onekash.kashcal.ui.components.attendees

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

// Material 3's default button contentPadding (24.dp horizontal) is sized
// for full-width CTA buttons. In a 3-up weighted row at narrow phone
// widths, the per-card text budget shrinks to ~57dp — long labels at
// SemiBold weight clip. Use compact padding so each card has ~80dp+
// for the label, plus a minimum touch height that respects M3's 48dp
// accessibility floor.
private val RSVP_CARD_CONTENT_PADDING = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
private val RSVP_CARD_HEIGHT = 44.dp

/**
 * RSVP three-card row used by [InviteesBlock] (QuickView + form
 * read-only banner) and [InvitationCard] (inbox).
 *
 * Word labels (Yes / Maybe / No), with the chosen status filled in
 * primary blue. When no status is chosen yet (NeedsAction or null),
 * "Yes" gets the weighted primary fill as the hopeful default —
 * Apple Calendar's pattern.
 *
 * Behavior:
 * - Tap any card → [onRsvp] fires with the corresponding status.
 *   Light haptic on commit, matching the form's save haptic.
 * - Disabled cards stay visible at reduced opacity; today's path
 *   doesn't actually disable any card, but [enabled] supports
 *   future use cases (e.g., past-event RSVP suppression).
 */
@Composable
fun RsvpCards(
    currentUserPartstat: AttendeeStatus?,
    onRsvp: (AttendeeStatus) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val hasResponded = currentUserPartstat != null &&
        currentUserPartstat != AttendeeStatus.NeedsAction

    // Pre-response: "Yes" is weighted-primary, others are outlined
    // alternatives. Post-response: only the chosen one is filled;
    // the others fall back to outlined so the user sees what they
    // can switch to.
    val yesIsFilled = currentUserPartstat == AttendeeStatus.Accepted ||
        (!hasResponded && currentUserPartstat == AttendeeStatus.NeedsAction) ||
        (!hasResponded && currentUserPartstat == null)
    val maybeIsFilled = currentUserPartstat == AttendeeStatus.Tentative
    val noIsFilled = currentUserPartstat == AttendeeStatus.Declined

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RsvpCard(
            label = stringResource(R.string.rsvp_action_yes),
            isFilled = yesIsFilled,
            isPrimaryDefault = !hasResponded, // wider weight pre-response
            enabled = enabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRsvp(AttendeeStatus.Accepted)
            },
            modifier = Modifier.weight(1f),
        )
        RsvpCard(
            label = stringResource(R.string.rsvp_action_maybe),
            isFilled = maybeIsFilled,
            isPrimaryDefault = false,
            enabled = enabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRsvp(AttendeeStatus.Tentative)
            },
            modifier = Modifier.weight(1f),
        )
        RsvpCard(
            label = stringResource(R.string.rsvp_action_no),
            isFilled = noIsFilled,
            isPrimaryDefault = false,
            enabled = enabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRsvp(AttendeeStatus.Declined)
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RsvpCard(
    label: String,
    isFilled: Boolean,
    isPrimaryDefault: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isFilled) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(RSVP_CARD_HEIGHT),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = RSVP_CARD_CONTENT_PADDING,
        ) {
            Text(
                text = label,
                fontWeight = if (isPrimaryDefault) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Visible,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(RSVP_CARD_HEIGHT),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            contentPadding = RSVP_CARD_CONTENT_PADDING,
        ) {
            Text(
                text = label,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Visible,
            )
        }
    }
}
