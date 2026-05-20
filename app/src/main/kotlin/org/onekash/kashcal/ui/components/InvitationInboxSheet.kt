package org.onekash.kashcal.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.reader.PendingInvitation
import org.onekash.kashcal.ui.components.attendees.AttendeeStatus
import org.onekash.kashcal.ui.util.DayPagerUtils

/**
 * Half-height ModalBottomSheet that lists all pending CalDAV invitations
 * across the user's accounts. Uses the default Material3 drag handle so
 * the user can drag-to-expand to full height per spec.
 *
 * Cards are keyed by `event.id` so [LazyColumn]'s item-placement
 * animation runs when sync writes mutate the underlying Flow.
 *
 * When the list empties after the last RSVP, an "All caught up" message
 * is shown and tapping it dismisses the sheet (the user can also tap
 * outside or drag down).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitationInboxSheet(
    invitations: List<PendingInvitation>,
    timePattern: String,
    onRsvp: (Long, AttendeeStatus) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val todayDayCode = DayPagerUtils.msToDayCode(System.currentTimeMillis())
    val tomorrowDayCode = run {
        val tomorrow = DayPagerUtils.dayCodeToLocalDate(todayDayCode).plusDays(1)
        tomorrow.year * 10000 + tomorrow.monthValue * 100 + tomorrow.dayOfMonth
    }
    val todayLabel = stringResource(R.string.label_today)
    val tomorrowLabel = stringResource(R.string.label_tomorrow)
    val organizerSuffix = stringResource(R.string.inbox_organizer_suffix)
    val allDayLabel = stringResource(R.string.label_all_day)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        if (invitations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.inbox_empty_caught_up),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                items(
                    items = invitations,
                    key = { invite -> "invite_${invite.event.id}" }
                ) { invitation ->
                    InvitationCard(
                        invitation = invitation,
                        todayDayCode = todayDayCode,
                        tomorrowDayCode = tomorrowDayCode,
                        timePattern = timePattern,
                        todayLabel = todayLabel,
                        tomorrowLabel = tomorrowLabel,
                        organizerSuffix = organizerSuffix,
                        allDayLabel = allDayLabel,
                        onRsvp = onRsvp
                    )
                }
            }
        }
    }
}
