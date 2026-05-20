package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.domain.reader.PendingInvitation
import org.onekash.kashcal.ui.components.attendees.AttendeeStatus
import org.onekash.kashcal.ui.components.attendees.RespondSection
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.widget.formatUpcomingDayHeader

@Composable
fun InvitationCard(
    invitation: PendingInvitation,
    todayDayCode: Int,
    tomorrowDayCode: Int,
    timePattern: String,
    todayLabel: String,
    tomorrowLabel: String,
    organizerSuffix: String,
    allDayLabel: String,
    onRsvp: (Long, AttendeeStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    val dayCode = DayPagerUtils.msToDayCode(invitation.occurrenceStartTs)
    val dateLabel = formatUpcomingDayHeader(
        dayCode = dayCode,
        todayDayCode = todayDayCode,
        tomorrowDayCode = tomorrowDayCode,
        todayLabel = todayLabel,
        tomorrowLabel = tomorrowLabel
    )
    val timeLabel = if (invitation.event.isAllDay) {
        allDayLabel
    } else {
        WeekViewUtils.formatTimeRange(
            startTs = invitation.occurrenceStartTs,
            endTs = invitation.occurrenceEndTs,
            timePattern = timePattern
        )
    }
    val organizerText = "${invitation.organizerLabel} $organizerSuffix"

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(invitation.calendarColor))
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "$dateLabel · $timeLabel",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = invitation.event.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (invitation.organizerLabel.isNotEmpty()) {
                Text(
                    text = organizerText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val location = invitation.event.location?.takeIf { it.isNotBlank() }
            if (location != null) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            RespondSection(
                currentUserPartstat = AttendeeStatus.NeedsAction,
                onRsvp = { status -> onRsvp(invitation.event.id, status) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

