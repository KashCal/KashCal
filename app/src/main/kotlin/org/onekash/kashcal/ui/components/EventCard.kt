package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.contacts.ContactEventTitleFormatter
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
internal fun EventCard(
    displayEvent: DisplayEvent,
    isPast: Boolean,
    selectedDate: Long,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    onClick: () -> Unit
) {
    val resources = LocalContext.current.resources
    val displayTitle = remember(displayEvent, showEventEmojis) {
        formatDisplayEventTitle(displayEvent, showEventEmojis, resources)
    }

    val effectiveAlpha = if (isPast) 0.5f else 1f
    val stripeColor = Color(displayEvent.calendarColor)
    val fillColor = Color(displayEvent.eventColor ?: displayEvent.calendarColor)
    val fillAlpha = displayEvent.cardFillAlpha()
    val leftStripeColor = if (displayEvent.isFree) stripeColor.copy(alpha = 0.4f) else stripeColor

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(effectiveAlpha)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = fillColor.copy(alpha = fillAlpha)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(leftStripeColor)
            )
            Column(modifier = Modifier.padding(12.dp).weight(1f)) {
                Text(
                    displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val timeText = formatDisplayEventTimeDisplay(displayEvent, selectedDate, resources, timePattern = timePattern)
                Text(
                    timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                if (!displayEvent.location.isNullOrEmpty()) {
                    Text(
                        displayEvent.location!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * Card body tint alpha. Overridden events ride higher (0.35) so the user's chosen
 * color resists simultaneous-contrast shift from the adjacent calendar stripe;
 * non-override events stay at 0.15 for a calm baseline.
 */
internal fun DisplayEvent.cardFillAlpha(): Float = if (eventColor != null) 0.40f else 0.15f

internal fun formatDisplayEventTitle(displayEvent: DisplayEvent, showEmojis: Boolean, resources: android.content.res.Resources): String {
    return when (displayEvent) {
        is DisplayEvent.Room -> formatEventTitle(displayEvent.event, displayEvent.occurrence.startTs, showEmojis, resources)
        is DisplayEvent.Device -> EmojiMatcher.formatWithEmoji(displayEvent.title, showEmojis)
    }
}

internal fun formatEventTitle(event: Event, occurrenceTs: Long?, showEmojis: Boolean = true, resources: android.content.res.Resources): String {
    val baseTitle = ContactEventTitleFormatter.format(event, occurrenceTs, resources)
    return EmojiMatcher.formatWithEmoji(baseTitle, showEmojis)
}

internal fun formatDisplayEventTimeDisplay(
    displayEvent: DisplayEvent,
    selectedDateMillis: Long,
    resources: android.content.res.Resources,
    zoneId: ZoneId = ZoneId.systemDefault(),
    timePattern: String = "h:mm a"
): String {
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())
    val recurringIndicator = if (displayEvent.hasRrule) " \uD83D\uDD01" else ""

    val startDate = DateTimeUtils.eventTsToLocalDate(displayEvent.startTs, displayEvent.isAllDay, zoneId)
    val endDate = DateTimeUtils.eventTsToLocalDate(displayEvent.endTs, displayEvent.isAllDay, zoneId)
    val isMultiDay = endDate.isAfter(startDate)

    if (!isMultiDay) {
        return if (displayEvent.isAllDay) resources.getString(R.string.label_all_day) + recurringIndicator
        else {
            val startTime = Instant.ofEpochMilli(displayEvent.startTs).atZone(zoneId).format(timeFormatter)
            val endTime = Instant.ofEpochMilli(displayEvent.endTs).atZone(zoneId).format(timeFormatter)
            "$startTime - $endTime$recurringIndicator"
        }
    }

    val totalDays = DateTimeUtils.calculateTotalDays(displayEvent.startTs, displayEvent.endTs, displayEvent.isAllDay, zoneId)
    val currentDay = calculateCurrentDayForEvent(displayEvent.startTs, selectedDateMillis, displayEvent.isAllDay, zoneId)
        .coerceIn(1, totalDays)

    return when {
        currentDay == 1 && !displayEvent.isAllDay -> {
            val startTime = Instant.ofEpochMilli(displayEvent.startTs).atZone(zoneId).format(timeFormatter)
            resources.getString(R.string.day_starts, totalDays, startTime) + recurringIndicator
        }
        currentDay == totalDays && !displayEvent.isAllDay -> {
            val endTime = Instant.ofEpochMilli(displayEvent.endTs).atZone(zoneId).format(timeFormatter)
            resources.getString(R.string.day_ends, currentDay, totalDays, endTime) + recurringIndicator
        }
        else -> resources.getString(R.string.day_x_of_y, currentDay, totalDays) + recurringIndicator
    }
}

internal fun calculateCurrentDayForEvent(
    eventStartTs: Long,
    selectedDateMillis: Long,
    isAllDay: Boolean,
    zoneId: ZoneId
): Int {
    val startDate = DateTimeUtils.eventTsToLocalDate(eventStartTs, isAllDay, zoneId)
    val selectedDate = DateTimeUtils.eventTsToLocalDate(selectedDateMillis, isAllDay = false, zoneId)
    return ChronoUnit.DAYS.between(startDate, selectedDate).toInt() + 1
}
