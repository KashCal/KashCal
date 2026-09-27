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

// M3's default button padding (24.dp horizontal) leaves each card of a 3-up row about 57dp
// for text on a narrow phone, and long SemiBold labels clip. Compact padding gives the label
// about 80dp+, with a touch height that respects M3's 48dp accessibility floor.
private val RSVP_CARD_CONTENT_PADDING = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
private val RSVP_CARD_HEIGHT = 44.dp

/**
 * Shows the Yes / Maybe / No RSVP row for [InviteesBlock] and [InvitationCard].
 *
 * The chosen status is filled in primary. With no response yet (NeedsAction or null), "Yes"
 * is filled with a SemiBold label as the hopeful default.
 *
 * A tap calls [onRsvp] with the card's status after the same LongPress haptic as the form's
 * save. Disabled cards stay visible at reduced opacity; no caller passes [enabled] today.
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

    // Pre-response only "Yes" is filled; post-response only the chosen card is, and the
    // outlined others show what the user can switch to.
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
            isPrimaryDefault = !hasResponded, // SemiBold label pre-response
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
