package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A row of the Upcoming Events widget's list: a day header, an event, or the dropped-days footer.
 *
 * The widget shows the [UPCOMING_HORIZON_DAYS] days from today, skipping empty days and past
 * events. Past events are the ones [WidgetDataRepository] flags with `WidgetEvent.isPast`; this
 * file never re-derives that.
 */
internal sealed class UpcomingWidgetItem(val itemId: Long) {

    data class Header(
        val dayCode: Int,
        val eventCount: Int
    ) : UpcomingWidgetItem(dayCode.toLong())

    data class Event(
        val dayCode: Int,
        val event: WidgetDataRepository.WidgetEvent
    ) : UpcomingWidgetItem(dayCode.toLong() * 100_000L + event.eventId + ITEM_ID_EVENT_OFFSET)

    /**
     * Trailing row shown when the item cap ([MAX_UPCOMING_ITEMS]) drops whole days from the end
     * of the window. Tapping opens MainActivity via [ACTION_GO_TO_TODAY]. [daysDropped] is
     * always >= 1. The itemId is [Long.MAX_VALUE]: a list has one Footer, and the value sits well
     * beyond the ~2e12 range Event itemIds occupy.
     */
    data class Footer(val daysDropped: Int) : UpcomingWidgetItem(Long.MAX_VALUE)

    companion object {
        const val ITEM_ID_EVENT_OFFSET = 100_000_000L
    }
}

/**
 * Maximum total items (Header, Event and Footer combined) the widget emits.
 *
 * A heavy calendar can produce 300+ items, whose serialized RemoteViews approach the
 * per-process 1 MB Binder limit; launchers silently reject the transaction and leave the widget
 * stuck on `widget_loading.xml`. 100 items at ~3 KB each is ~300 KB, well under the ~500-800 KB
 * practical failure threshold.
 */
internal const val MAX_UPCOMING_ITEMS = 100

/**
 * Horizon in calendar days (inclusive: today .. today + [UPCOMING_HORIZON_DAYS] - 1).
 * Kept short to bound the cold-start query on first widget add: a longer horizon can exceed
 * the BroadcastReceiver `goAsync` budget and leave the widget stuck on `widget_loading.xml`.
 */
internal const val UPCOMING_HORIZON_DAYS = 10

/**
 * Flattens an events-by-day map into the list the LazyColumn renders.
 *
 * - Days whose events are all past are skipped (no Header).
 * - Days come out in ascending dayCode order whatever the map's iteration order.
 * - Within a day, event order is kept; [WidgetDataRepository] sorts it.
 * - Each kept day produces one [UpcomingWidgetItem.Header] and one [UpcomingWidgetItem.Event]
 *   per non-past event; [UpcomingWidgetItem.Header.eventCount] counts only those.
 *
 * ## Cap
 *
 * Days are added whole, in dayCode order. When a day's header and events would push the total
 * past [MAX_UPCOMING_ITEMS], that day and every later day are dropped (a day is never split)
 * and a [UpcomingWidgetItem.Footer] counts them. The first non-empty day is always included,
 * even if it alone exceeds the cap: a widget showing nothing for today is worse than today in
 * full. No Footer is emitted when no day was dropped.
 *
 * Returns an empty list when no non-past event remains; the caller shows the empty state.
 */
internal fun buildFlatUpcomingItems(
    eventsByDay: Map<Int, List<WidgetDataRepository.WidgetEvent>>
): List<UpcomingWidgetItem> {
    val items = mutableListOf<UpcomingWidgetItem>()
    var daysDropped = 0
    for ((dayCode, events) in eventsByDay.toSortedMap()) {
        val kept = events.filterNot { it.isPast }
        if (kept.isEmpty()) continue

        val wouldOverflow = items.size + 1 + kept.size > MAX_UPCOMING_ITEMS
        if (daysDropped > 0 || (wouldOverflow && items.isNotEmpty())) {
            daysDropped++
            continue
        }

        items.add(UpcomingWidgetItem.Header(dayCode, kept.size))
        kept.forEach { event ->
            items.add(UpcomingWidgetItem.Event(dayCode, event))
        }
    }

    if (daysDropped > 0) {
        items.add(UpcomingWidgetItem.Footer(daysDropped))
    }
    return items
}

/**
 * Builds the [ActionParameters] the footer row dispatches when tapped. A separate function so
 * the click wiring is unit-tested, not only compile-checked.
 */
internal fun footerActionParameters(): ActionParameters =
    actionParametersOf(ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_TODAY)

/**
 * Returns the dayCode after [todayDayCode].
 *
 * Uses [java.time.LocalDate.plusDays]: integer `+1` on the YYYYMMDD dayCode gives invalid codes
 * across month and year boundaries (`20260430 + 1 = 20260431`).
 */
internal fun tomorrowDayCodeOf(todayDayCode: Int): Int {
    val tomorrow = DayPagerUtils.dayCodeToLocalDate(todayDayCode).plusDays(1)
    return tomorrow.year * 10000 + tomorrow.monthValue * 100 + tomorrow.dayOfMonth
}

/**
 * Formats a day-header label for the Upcoming widget.
 *
 * - [todayLabel] when [dayCode] is [todayDayCode] ("Today (Tue, Apr 28)" with a template)
 * - [tomorrowLabel] when [dayCode] is [tomorrowDayCode] ("Tomorrow (Wed, Apr 29)")
 * - otherwise a locale-aware "EEE, MMM d" date ("Fri, May 1")
 *
 * With [withDateTemplate] (the localizable "%1$s (%2$s)" template) the Today and Tomorrow
 * labels get the date in brackets, as informative as the Week widget's headers (#253). When it
 * is null they are the plain label, which the in-app invitation card relies on.
 *
 * Labels and template are passed in, resolved by the caller from `R.string.label_today`,
 * `R.string.label_tomorrow` and `R.string.upcoming_widget_day_with_date`, so this function
 * stays Context-free and unit-testable. [tomorrowDayCode] is a parameter so a caller rendering
 * many headers computes it once.
 */
internal fun formatUpcomingDayHeader(
    dayCode: Int,
    todayDayCode: Int,
    tomorrowDayCode: Int,
    todayLabel: String,
    tomorrowLabel: String,
    withDateTemplate: String? = null
): String {
    val relativeLabel = when (dayCode) {
        todayDayCode -> todayLabel
        tomorrowDayCode -> tomorrowLabel
        else -> null
    }
    val date = DayPagerUtils.dayCodeToLocalDate(dayCode)
    val formatter = DateTimeFormatter.ofPattern(
        DateTimeUtils.localizedPattern("EEEMMMd"),
        Locale.getDefault()
    )
    val formattedDate = date.format(formatter)

    if (relativeLabel == null) return formattedDate
    return if (withDateTemplate != null) {
        String.format(withDateTemplate, relativeLabel, formattedDate)
    } else {
        relativeLabel
    }
}

/**
 * Returns the Upcoming widget's inclusive window `(startDayCode, endDayCode)`: today through
 * today + [horizonDays] - 1 in [zone].
 *
 * Day arithmetic uses [java.time.LocalDate.plusDays], never integer addition on YYYYMMDD, which
 * fails across month and year boundaries (`20260430 + 1` is not a valid dayCode). [zone] is a
 * parameter for tests; production uses the system default.
 */
internal fun upcomingWindow(
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    horizonDays: Int = UPCOMING_HORIZON_DAYS
): Pair<Int, Int> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val end = today.plusDays((horizonDays - 1).toLong())
    val startCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
    val endCode = end.year * 10000 + end.monthValue * 100 + end.dayOfMonth
    return startCode to endCode
}

/**
 * Renders the Upcoming Events widget: a scrollable list of day headers and events built by
 * [buildFlatUpcomingItems], or an empty state when no non-past event remains in the window.
 */
@Composable
fun UpcomingWidgetContent(
    eventsByDay: Map<Int, List<WidgetDataRepository.WidgetEvent>>,
    todayDayCode: Int,
    showEventEmojis: Boolean,
    timePattern: String,
    isRefreshing: Boolean = false,
    detailedRows: Boolean = false
) {
    val items = remember(eventsByDay) { buildFlatUpcomingItems(eventsByDay) }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.contentBackground)
            .cornerRadius(16.dp)
    ) {
        UpcomingWidgetHeader(isRefreshing)
        if (items.isEmpty()) {
            UpcomingEmptyState()
        } else {
            UpcomingItemsList(items, todayDayCode, showEventEmojis, timePattern, detailedRows)
        }
    }
}

@Composable
private fun UpcomingWidgetHeader(isRefreshing: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(WidgetTheme.headerBackground)
            // No vertical padding: the 48dp add button sets the header height, so every widget
            // header is 48dp. No end inset either: the add button's glyph centering gives the
            // right margin, as in the month widget header.
            .padding(start = WIDGET_HORIZONTAL_MARGIN_DP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(
                    actionStartActivity<MainActivity>(
                        parameters = actionParametersOf(
                            ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_TODAY
                        )
                    )
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_calendar),
                contentDescription = context.getString(R.string.cd_widget_calendar),
                modifier = GlanceModifier.size(20.dp)
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = context.getString(R.string.upcoming_widget_name),
                style = TextStyle(
                    color = WidgetTheme.onHeaderBackground,
                    fontSize = WidgetTypography.headerTitle,
                    fontWeight = FontWeight.Medium
                ),
                // The refresh and add buttons take ~96dp on the right; on a narrow widget a long
                // localized name must ellipsize on one line, not wrap and grow the header.
                maxLines = 1
            )
        }
        WidgetRefreshButton(kind = WidgetKind.UPCOMING, isRefreshing = isRefreshing)
        WidgetAddButton()
    }
}

@Composable
private fun UpcomingItemsList(
    items: List<UpcomingWidgetItem>,
    todayDayCode: Int,
    showEventEmojis: Boolean,
    timePattern: String,
    detailedRows: Boolean
) {
    val context = LocalContext.current
    val todayLabel = context.getString(R.string.label_today)
    val tomorrowLabel = context.getString(R.string.label_tomorrow)
    val withDateTemplate = context.getString(R.string.upcoming_widget_day_with_date)
    val tomorrowDayCode = remember(todayDayCode) { tomorrowDayCodeOf(todayDayCode) }

    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items.forEach { item ->
            when (item) {
                is UpcomingWidgetItem.Header -> item(itemId = item.itemId) {
                    UpcomingDayHeader(
                        dayCode = item.dayCode,
                        todayDayCode = todayDayCode,
                        tomorrowDayCode = tomorrowDayCode,
                        todayLabel = todayLabel,
                        tomorrowLabel = tomorrowLabel,
                        withDateTemplate = withDateTemplate,
                        eventCount = item.eventCount
                    )
                }
                is UpcomingWidgetItem.Event -> item(itemId = item.itemId) {
                    UpcomingEventRow(
                        event = item.event,
                        dayCode = item.dayCode,
                        showEventEmojis = showEventEmojis,
                        timePattern = timePattern,
                        detailedRows = detailedRows
                    )
                }
                is UpcomingWidgetItem.Footer -> item(itemId = item.itemId) {
                    UpcomingMoreDaysFooter(daysDropped = item.daysDropped)
                }
            }
        }
    }
}

@Composable
private fun UpcomingMoreDaysFooter(daysDropped: Int) {
    val context = LocalContext.current
    val moreDaysText = context.resources.getQuantityString(
        R.plurals.upcoming_widget_more_days,
        daysDropped,
        daysDropped
    )
    val openLabel = context.getString(R.string.upcoming_widget_open_calendar)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(WidgetTheme.rowTintBackground)
            .padding(horizontal = WIDGET_HORIZONTAL_MARGIN_DP.dp, vertical = 8.dp)
            .clickable(actionStartActivity<MainActivity>(parameters = footerActionParameters())),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = moreDaysText,
            style = TextStyle(
                color = WidgetTheme.rowTintText,
                fontSize = WidgetTypography.secondary,
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        Text(
            text = openLabel,
            style = TextStyle(
                color = WidgetTheme.rowTintText,
                fontSize = WidgetTypography.secondary
            )
        )
    }
}

@Composable
private fun UpcomingDayHeader(
    dayCode: Int,
    todayDayCode: Int,
    tomorrowDayCode: Int,
    todayLabel: String,
    tomorrowLabel: String,
    withDateTemplate: String,
    eventCount: Int
) {
    val context = LocalContext.current
    val isToday = dayCode == todayDayCode
    val colors = dayHeaderColors(isToday)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(colors.background.provider())
            .padding(horizontal = WIDGET_HORIZONTAL_MARGIN_DP.dp, vertical = 6.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_DATE,
                        ActionParameters.Key<Int>(EXTRA_DAY_CODE) to dayCode
                    )
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatUpcomingDayHeader(
                dayCode, todayDayCode, tomorrowDayCode, todayLabel, tomorrowLabel, withDateTemplate
            ),
            style = TextStyle(
                color = colors.text.provider(),
                fontSize = WidgetTypography.contentTitle,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium
            ),
            maxLines = 1,
            // Take remaining space and ellipsize so the bracketed date never
            // pushes the event count off a narrow widget.
            modifier = GlanceModifier.defaultWeight()
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = context.resources.getQuantityString(
                R.plurals.widget_event_count_plural,
                eventCount,
                eventCount
            ),
            style = TextStyle(
                color = WidgetTheme.onHeaderBackground,
                fontSize = WidgetTypography.label
            )
        )
    }
}

@Composable
private fun UpcomingEventRow(
    event: WidgetDataRepository.WidgetEvent,
    dayCode: Int,
    showEventEmojis: Boolean,
    timePattern: String,
    detailedRows: Boolean
) {
    val rowContext = LocalContext.current
    // A cancelled event shows only as a strikethrough, so label the whole row (time, title,
    // cancelled) for TalkBack.
    val cancelledLabel = if (event.isCancelled) {
        cancelledRowLabel(
            rowContext, event, dayCode, timePattern,
            EmojiMatcher.formatWithEmoji(event.title, showEventEmojis)
        )
    } else {
        null
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(
                horizontal = WIDGET_HORIZONTAL_MARGIN_DP.dp,
                vertical = eventRowVerticalPaddingDp(detailedRows).dp
            )
            .let { m -> if (cancelledLabel != null) m.semantics { contentDescription = cancelledLabel } else m }
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_SHOW_EVENT,
                        ActionParameters.Key<Long>(EXTRA_EVENT_ID) to event.eventId,
                        ActionParameters.Key<Long>(EXTRA_OCCURRENCE_TS) to event.occurrenceStartTs,
                        ActionParameters.Key<Boolean>(EXTRA_IS_DEVICE_EVENT) to event.isDeviceEvent
                    )
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EventRowInner(event, dayCode, showEventEmojis, timePattern, detailedRows)
    }
}

@Composable
private fun UpcomingEmptyState() {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(16.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_CREATE_EVENT
                    )
                )
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = context.getString(R.string.widget_no_upcoming_events),
            style = TextStyle(
                color = WidgetTheme.secondaryText,
                fontSize = WidgetTypography.contentTitle
            )
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Text(
            text = context.getString(R.string.widget_add_event),
            style = TextStyle(
                color = WidgetTheme.accentColor,
                fontSize = WidgetTypography.contentTitle
            )
        )
    }
}

/**
 * Renders the Upcoming widget for [state]: loading, error or loaded content. A separate function
 * so tests render it through `provideComposable { UpcomingWidgetScaffold(state = X) }` without
 * the full `provideGlance` lifecycle.
 */
@Composable
internal fun UpcomingWidgetScaffold(state: UpcomingState, isRefreshing: Boolean = false) {
    when (state) {
        UpcomingState.Loading -> UpcomingLoadingContent(isRefreshing)
        UpcomingState.Error -> UpcomingErrorContent(isRefreshing)
        is UpcomingState.Loaded -> UpcomingWidgetContent(
            eventsByDay = state.eventsByDay,
            todayDayCode = state.todayDayCode,
            showEventEmojis = state.showEventEmojis,
            timePattern = state.timePattern,
            isRefreshing = isRefreshing,
            detailedRows = state.detailedRows
        )
    }
}

/** Themed loading state shown while the fetcher runs. */
@Composable
internal fun UpcomingLoadingContent(isRefreshing: Boolean = false) {
    UpcomingStatePlaceholder(textRes = R.string.widget_loading_upcoming, isRefreshing = isRefreshing)
}

/** Themed error state; tapping opens the app on today so the user can recover. */
@Composable
internal fun UpcomingErrorContent(isRefreshing: Boolean = false) {
    UpcomingStatePlaceholder(
        textRes = R.string.widget_error_load_events,
        onTapAction = actionStartActivity<MainActivity>(
            parameters = actionParametersOf(
                ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_TODAY
            )
        ),
        isRefreshing = isRefreshing
    )
}

/**
 * Renders the Upcoming widget's loading and error states: the header over centered text.
 * [onTapAction], when given, makes the whole placeholder tappable.
 */
@Composable
private fun UpcomingStatePlaceholder(
    textRes: Int,
    onTapAction: Action? = null,
    isRefreshing: Boolean = false
) {
    val context = LocalContext.current
    val baseModifier = GlanceModifier
        .fillMaxSize()
        .background(WidgetTheme.contentBackground)
        .cornerRadius(16.dp)
    val modifier = if (onTapAction != null) baseModifier.clickable(onTapAction) else baseModifier
    Column(modifier = modifier) {
        UpcomingWidgetHeader(isRefreshing)
        Box(
            modifier = GlanceModifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = context.getString(textRes),
                style = TextStyle(
                    color = WidgetTheme.secondaryText,
                    fontSize = WidgetTypography.contentTitle
                )
            )
        }
    }
}
