package org.onekash.kashcal.ui.components.attendees

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

/**
 * Shows a day card's attendee badge: the count of other people and the user's status. Shows
 * nothing when no one but the user is on the list.
 *
 * - The user organizes: 👑 N · Hosting
 * - Off-list: 👥 N, no status
 * - On-list: 👥 N · {Going|Pending|Declined|Tentative}
 */
@Composable
fun EventCardAttendeeBadge(
    models: List<AttendeeUiModel>,
    modifier: Modifier = Modifier
) {
    if (models.isEmpty()) return

    val you = models.firstOrNull { it.isYou }
    // "👥 5" means five other people. Counting `!isYou` also drops every row of a user listed
    // under several aliases (me.com and icloud.com, for example).
    val count = models.count { !it.isYou }
    if (count <= 0) return
    val (badgeIcon, label, color) = when {
        you?.isOrganizer == true -> Triple(
            "👑",
            stringResource(R.string.attendee_card_hosting),
            MaterialTheme.colorScheme.primary
        )
        you == null -> Triple(
            "👥",
            null, // count-only when off-list
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        you.status == AttendeeStatus.Accepted -> Triple(
            "👥",
            stringResource(R.string.attendee_card_going),
            MaterialTheme.colorScheme.primary
        )
        you.status == AttendeeStatus.Declined -> Triple(
            "👥",
            stringResource(R.string.attendee_card_declined),
            MaterialTheme.colorScheme.error
        )
        you.status == AttendeeStatus.Tentative -> Triple(
            "👥",
            stringResource(R.string.attendee_card_tentative),
            MaterialTheme.colorScheme.secondary
        )
        else -> Triple(
            "👥",
            stringResource(R.string.attendee_card_pending),
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
