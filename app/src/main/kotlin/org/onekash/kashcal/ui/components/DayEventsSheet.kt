package org.onekash.kashcal.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.shared.stepSwipe
import org.onekash.kashcal.ui.viewmodels.DayDetailState
import org.onekash.kashcal.util.DateTimeUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/** Test tag of the sheet's swipe area: everything below the date row. */
internal const val DAY_SHEET_BODY_TAG = "day_sheet_body"

/**
 * Shows a bottom sheet of every event on [day], tapped in the full-height month view (in place
 * of the month view's `DayEventsPager`) or the Planner, with a New event button for that day.
 * Its arrows and a sideways swipe anywhere below the date ([stepSwipe]) ask [onStepDay] for the
 * previous (-1) or next (1) day, mirrored right to left, as far as [DayDetailState.canGoBack] and
 * [DayDetailState.canGoForward] allow. A new day slides in from the side it was asked from; new
 * events for the same day don't slide (`DayEventsSheetStepTest`). It always opens at full
 * height, so it doesn't stop half way and doesn't change size when the day's events arrive
 * after it opened (`DayEventsSheetSizeTest`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayEventsSheet(
    day: DayDetailState,
    showEventEmojis: Boolean,
    timePattern: String,
    onEventClick: (Event, Long?) -> Unit,
    onDeviceEventClick: (DisplayEvent.Device) -> Unit,
    onCreateEvent: (Long) -> Unit,
    onDismiss: () -> Unit,
    onStepDay: (Int) -> Unit,
    attendeesByEventId: Map<Long, List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel>> = emptyMap()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val locale = LocalLocale.current.platformLocale
    val dateFormat = remember(locale) { SimpleDateFormat(DateTimeUtils.localizedPattern("yEEEEMMMMd"), locale) }
    // A SimpleDateFormat keeps the zone it was built in, and the sheet can stay open across a
    // zone change.
    val dateLabel = dateFormat.apply { timeZone = TimeZone.getDefault() }.format(Date(day.dateMs))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                // Announce the sheet by its date when it opens and when it moves to another day.
                .semantics { paneTitle = dateLabel }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                IconButton(onClick = { onStepDay(-1) }, enabled = day.canGoBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.cd_previous_day)
                    )
                }
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                IconButton(onClick = { onStepDay(1) }, enabled = day.canGoForward) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.cd_next_day)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(DAY_SHEET_BODY_TAG)
                    .stepSwipe(
                        canGoBack = day.canGoBack,
                        canGoForward = day.canGoForward,
                        onStep = onStepDay,
                    )
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Takes only the room its day needs, so New event follows the last event; a
                    // long day scrolls here and keeps New event in view.
                    AnimatedContent(
                        targetState = day,
                        contentKey = { it.dayCode },
                        transitionSpec = { daySlide() },
                        label = "day-sheet-day",
                        modifier = Modifier.weight(1f, fill = false)
                    ) { shown ->
                        DayEventsList(
                            shown = shown,
                            showEventEmojis = showEventEmojis,
                            timePattern = timePattern,
                            attendeesByEventId = attendeesByEventId,
                            onEventClick = onEventClick,
                            onDeviceEventClick = onDeviceEventClick,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { onCreateEvent(day.dateMs) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(stringResource(R.string.shortcut_new_event))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Slides a later day in from the reading direction's end and an earlier day in from its start;
 * Start and End mirror right to left.
 */
private fun AnimatedContentTransitionScope<DayDetailState>.daySlide() =
    if (targetState.dayCode > initialState.dayCode) {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start) togetherWith
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start)
    } else {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End) togetherWith
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End)
    }

/**
 * Shows [shown]'s events, or "No events". It takes its day from [shown], not the sheet's current
 * day, so a leaving day keeps its own events during the slide.
 */
@Composable
private fun DayEventsList(
    shown: DayDetailState,
    showEventEmojis: Boolean,
    timePattern: String,
    attendeesByEventId: Map<Long, List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel>>,
    onEventClick: (Event, Long?) -> Unit,
    onDeviceEventClick: (DisplayEvent.Device) -> Unit,
) {
    if (shown.events.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.day_events_no_events),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown.events, key = { event ->
                when (event) {
                    is DisplayEvent.Room -> "room-${event.event.id}-${event.occurrence.startTs}"
                    is DisplayEvent.Device -> "device-${event.instance.instanceId}-${event.startTs}"
                }
            }) { displayEvent ->
                val isPast = DateTimeUtils.isEventPast(
                    displayEvent.endTs, displayEvent.endDay, displayEvent.isAllDay
                )

                val rowAttendees = (displayEvent as? DisplayEvent.Room)
                    ?.let { attendeesByEventId[it.event.id] }
                    .orEmpty()
                EventCard(
                    displayEvent = displayEvent,
                    isPast = isPast,
                    selectedDate = shown.dateMs,
                    showEventEmojis = showEventEmojis,
                    timePattern = timePattern,
                    attendees = rowAttendees,
                    onClick = {
                        when (displayEvent) {
                            is DisplayEvent.Room -> onEventClick(
                                displayEvent.event,
                                displayEvent.occurrence.startTs
                            )
                            is DisplayEvent.Device -> onDeviceEventClick(displayEvent)
                        }
                    }
                )
            }
        }
    }
}
