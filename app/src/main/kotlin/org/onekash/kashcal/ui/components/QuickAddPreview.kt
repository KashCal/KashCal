package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.quickadd.QuickAddResult
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.ui.components.pickers.rememberRruleDisplayStrings
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@Composable
fun QuickAddPreview(
    result: QuickAddResult,
    modifier: Modifier = Modifier
) {
    val hasContent = result.title.isNotBlank() || result.startTime != null || result.location != null

    if (!hasContent) return

    val today = remember { LocalDate.now() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (result.title.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (result.emoji != null) {
                    Text(
                        text = result.emoji,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        val todayLabel = stringResource(R.string.label_today)
        val tomorrowLabel = stringResource(R.string.label_tomorrow)
        val yesterdayLabel = stringResource(R.string.label_yesterday)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatDate(result.startDate, today, todayLabel, tomorrowLabel, yesterdayLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (result.startTime != null) {
                val timeText = buildString {
                    append(result.startTime.format(timeFormatter))
                    if (result.endTime != null) {
                        append(" – ")
                        append(result.endTime.format(timeFormatter))
                    }
                }
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.label_all_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!result.location.isNullOrBlank()) {
            Text(
                text = result.location,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (result.rrule != null) {
            val rruleStrings = rememberRruleDisplayStrings()
            Text(
                text = RruleBuilder.formatForDisplay(result.rrule, rruleStrings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatDate(
    date: LocalDate,
    today: LocalDate,
    todayLabel: String,
    tomorrowLabel: String,
    yesterdayLabel: String
): String {
    return when (date) {
        today -> todayLabel
        today.plusDays(1) -> tomorrowLabel
        today.minusDays(1) -> yesterdayLabel
        else -> date.format(dateFormatter)
    }
}
