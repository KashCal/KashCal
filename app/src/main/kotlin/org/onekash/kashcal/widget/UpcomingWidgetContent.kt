package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.color.ColorProvider
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
 * Upcoming Events widget — internal items, builder, formatters.
 *
 * Composables are added by the Glance wiring chunk; this file currently holds
 * only pure logic so it is fully unit-testable.
 *
 * The widget renders a [UPCOMING_HORIZON_DAYS]-day horizon (inclusive today ..
 * today + [UPCOMING_HORIZON_DAYS] - 1) with empty days suppressed and past
 * events hidden. Past-filtering consumes the
 * `WidgetEvent.isPast` flag already populated by [WidgetDataRepository] — we
 * never re-derive that here.
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
     * Trailing row shown when the item cap ([MAX_UPCOMING_ITEMS]) forces us to
     * drop one or more whole days from the end of the window. Tapping opens
     * MainActivity via [ACTION_GO_TO_TODAY]. [daysDropped] is always >= 1.
     * Uses [Long.MAX_VALUE] as itemId — only one Footer per list so uniqueness
     * is trivial, and it sits well beyond the ~2e12 range Event itemIds occupy.
     */
    data class Footer(val daysDropped: Int) : UpcomingWidgetItem(Long.MAX_VALUE)

    companion object {
        const val ITEM_ID_EVENT_OFFSET = 100_000_000L
    }
}

/**
 * Maximum total items (Header + Event + Footer combined) the widget will emit.
 *
 * Why 100: heavy calendars produced 300+ items pre-cap, whose serialized
 * RemoteViews approached the per-process 1 MB Binder limit — launchers
 * silently rejected the transaction and left the widget stuck on
 * `widget_loading.xml` indefinitely. 100 items × ~3 KB/item ≈ 300 KB,
 * comfortably under the ~500-800 KB practical failure threshold.
 */
internal const val MAX_UPCOMING_ITEMS = 100

/**
 * Horizon in calendar days (inclusive: today .. today + [UPCOMING_HORIZON_DAYS] - 1).
 * Shortened from 30 to 10 to reduce cold-start query work on first widget add,
 * which can otherwise exceed the BroadcastReceiver `goAsync` budget and leave
 * the widget stuck on the `widget_loading.xml` placeholder.
 */
internal const val UPCOMING_HORIZON_DAYS = 10

/**
 * Collapse an events-by-day map into a flat list for LazyColumn rendering.
 *
 * - Days whose events are all past are skipped entirely (no Header).
 * - Days are emitted in ascending dayCode order regardless of map iteration order.
 * - Within a day, event ordering is preserved (caller — [WidgetDataRepository] —
 *   is responsible for intra-day sort).
 * - Each surviving day produces: one [UpcomingWidgetItem.Header] + one
 *   [UpcomingWidgetItem.Event] per non-past event.
 * - [UpcomingWidgetItem.Header.eventCount] reflects the count AFTER past-filtering.
 *
 * **Cap behaviour ([MAX_UPCOMING_ITEMS]):** Items are added a day at a time
 * in ascending dayCode order. If adding a day's header + events would push
 * total items past the cap, that entire day is dropped (never split
 * mid-list) and a [UpcomingWidgetItem.Footer] is appended instead. The first
 * non-empty day is ALWAYS included even if it alone exceeds the cap — a
 * widget showing nothing for today is worse than rendering today in full.
 * No Footer is emitted when `daysDropped` would be 0 (empty list or only the
 * first overflowing day present).
 *
 * Returns an empty list if no non-past events remain across any day — the
 * caller renders the widget's empty state in that case.
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
 * Build the [ActionParameters] that the footer row dispatches when tapped.
 * Extracted as a testable helper so the click-wiring is unit-tested, not
 * just compile-checked.
 */
internal fun footerActionParameters(): ActionParameters =
    actionParametersOf(ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_TODAY)

/**
 * Compute tomorrow's dayCode given today's dayCode.
 *
 * Uses [java.time.LocalDate.plusDays] — NOT integer `+1` on the YYYYMMDD
 * dayCode, which would produce invalid codes across month/year boundaries
 * (e.g., `20260430 + 1 = 20260431`).
 */
internal fun tomorrowDayCodeOf(todayDayCode: Int): Int {
    val tomorrow = DayPagerUtils.dayCodeToLocalDate(todayDayCode).plusDays(1)
    return tomorrow.year * 10000 + tomorrow.monthValue * 100 + tomorrow.dayOfMonth
}

/**
 * Format a day-header label for the upcoming widget.
 *
 * Returns:
 * - `todayLabel` when [dayCode] equals [todayDayCode]
 * - `tomorrowLabel` when [dayCode] equals [tomorrowDayCode]
 * - Otherwise, a locale-aware "EEE, MMM d" formatting (e.g., "Fri, May 1").
 *
 * [tomorrowDayCode] is a parameter (not recomputed internally) so that callers
 * rendering many headers per frame can compute it once and pass it in.
 *
 * The Today/Tomorrow labels are injected so this function stays Context-free
 * and unit-testable. Callers resolve the strings via `getString(R.string.label_today)`
 * and `getString(R.string.label_tomorrow)` before calling.
 */
internal fun formatUpcomingDayHeader(
    dayCode: Int,
    todayDayCode: Int,
    tomorrowDayCode: Int,
    todayLabel: String,
    tomorrowLabel: String
): String {
    if (dayCode == todayDayCode) return todayLabel
    if (dayCode == tomorrowDayCode) return tomorrowLabel
    val date = DayPagerUtils.dayCodeToLocalDate(dayCode)
    val formatter = DateTimeFormatter.ofPattern(
        DateTimeUtils.localizedPattern("EEEMMMd"),
        Locale.getDefault()
    )
    return date.format(formatter)
}

/**
 * Compute the inclusive [horizonDays]-day window `(startDayCode, endDayCode)` for the
 * upcoming widget, where `startDayCode` is today and `endDayCode` is today +
 * [horizonDays] - 1.
 *
 * Uses [java.time.LocalDate.plusDays] for day arithmetic — NEVER integer
 * addition on YYYYMMDD, which fails across month/year boundaries (e.g.,
 * `20260430 + 1` is not a valid dayCode).
 *
 * [zone] is injectable for deterministic testing; production callers pass
 * [ZoneId.systemDefault()]. [horizonDays] defaults to [UPCOMING_HORIZON_DAYS].
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
 * Upcoming Events widget content. Renders a scrollable list of day headers +
 * events for the next [UPCOMING_HORIZON_DAYS] calendar days; days without any
 * non-past events are skipped. An empty state is shown when no events remain
 * across the whole window.
 */
@Composable
fun UpcomingWidgetContent(
    eventsByDay: Map<Int, List<WidgetDataRepository.WidgetEvent>>,
    todayDayCode: Int,
    showEventEmojis: Boolean,
    timePattern: String
) {
    val items = remember(eventsByDay) { buildFlatUpcomingItems(eventsByDay) }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.contentBackground)
            .cornerRadius(16.dp)
    ) {
        UpcomingWidgetHeader()
        if (items.isEmpty()) {
            UpcomingEmptyState()
        } else {
            UpcomingItemsList(items, todayDayCode, showEventEmojis, timePattern)
        }
    }
}

@Composable
private fun UpcomingWidgetHeader() {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(WidgetTheme.headerBackground)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 0.dp),
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
                    color = WidgetTheme.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
        Box(
            modifier = GlanceModifier
                .size(40.dp)
                .clickable(
                    actionStartActivity<MainActivity>(
                        parameters = actionParametersOf(
                            ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_CREATE_EVENT
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = context.getString(R.string.cd_widget_add_event),
                modifier = GlanceModifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun UpcomingItemsList(
    items: List<UpcomingWidgetItem>,
    todayDayCode: Int,
    showEventEmojis: Boolean,
    timePattern: String
) {
    val context = LocalContext.current
    val todayLabel = context.getString(R.string.label_today)
    val tomorrowLabel = context.getString(R.string.label_tomorrow)
    val allDayLabel = context.getString(R.string.label_all_day)
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
                        eventCount = item.eventCount
                    )
                }
                is UpcomingWidgetItem.Event -> item(itemId = item.itemId) {
                    UpcomingEventRow(
                        event = item.event,
                        dayCode = item.dayCode,
                        showEventEmojis = showEventEmojis,
                        timePattern = timePattern,
                        allDayLabel = allDayLabel
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
            .background(WidgetTheme.dividerColor)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(actionStartActivity<MainActivity>(parameters = footerActionParameters())),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = moreDaysText,
            style = TextStyle(
                color = WidgetTheme.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        Text(
            text = openLabel,
            style = TextStyle(
                color = WidgetTheme.secondaryText,
                fontSize = 12.sp
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
    eventCount: Int
) {
    val context = LocalContext.current
    val isToday = dayCode == todayDayCode
    val background = if (isToday) WidgetTheme.headerBackground else WidgetTheme.dividerColor

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp)
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
            text = formatUpcomingDayHeader(dayCode, todayDayCode, tomorrowDayCode, todayLabel, tomorrowLabel),
            style = TextStyle(
                color = WidgetTheme.primaryText,
                fontSize = 13.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium
            )
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        Text(
            text = context.resources.getQuantityString(
                R.plurals.widget_event_count_plural,
                eventCount,
                eventCount
            ),
            style = TextStyle(
                color = WidgetTheme.secondaryText,
                fontSize = 11.sp
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
    allDayLabel: String
) {
    val displayTitle = EmojiMatcher.formatWithEmoji(event.title, showEventEmojis)
    val calendarColor = Color(event.calendarColor)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
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
        Text(
            text = formatWidgetEventTime(event, dayCode, timePattern, allDayLabel),
            style = TextStyle(
                color = WidgetTheme.secondaryText,
                fontSize = 12.sp
            ),
            modifier = GlanceModifier.width(58.dp)
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = displayTitle,
            style = TextStyle(
                color = WidgetTheme.primaryText,
                fontSize = 14.sp
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .cornerRadius(4.dp)
                .background(ColorProvider(day = calendarColor, night = calendarColor)),
            contentAlignment = Alignment.Center
        ) {}
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
                fontSize = 14.sp
            )
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Text(
            text = context.getString(R.string.widget_add_event),
            style = TextStyle(
                color = WidgetTheme.accentColor,
                fontSize = 14.sp
            )
        )
    }
}

/**
 * Top-level scaffold for the Upcoming widget: branches on [UpcomingState] and dispatches
 * to Loading / Error / Loaded sub-composables. Extracted so it is unit-testable via
 * `provideComposable { UpcomingWidgetScaffold(state = X) }` without the full `provideGlance`
 * lifecycle.
 */
@Composable
internal fun UpcomingWidgetScaffold(state: UpcomingState) {
    when (state) {
        UpcomingState.Loading -> UpcomingLoadingContent()
        UpcomingState.Error -> UpcomingErrorContent()
        is UpcomingState.Loaded -> UpcomingWidgetContent(
            eventsByDay = state.eventsByDay,
            todayDayCode = state.todayDayCode,
            showEventEmojis = state.showEventEmojis,
            timePattern = state.timePattern
        )
    }
}

/** Themed loading state shown while the fetcher runs. */
@Composable
internal fun UpcomingLoadingContent() {
    UpcomingStatePlaceholder(textRes = R.string.widget_loading_upcoming)
}

/** Themed error state — tapping opens the app so the user can recover. */
@Composable
internal fun UpcomingErrorContent() {
    UpcomingStatePlaceholder(
        textRes = R.string.widget_error_load_events,
        onTapAction = actionStartActivity<MainActivity>(
            parameters = actionParametersOf(
                ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_TODAY
            )
        )
    )
}

/**
 * Shared scaffolding for the Upcoming widget's non-loaded states: header + centered text.
 * Pass [onTapAction] to make the whole placeholder tappable.
 */
@Composable
private fun UpcomingStatePlaceholder(
    textRes: Int,
    onTapAction: Action? = null
) {
    val context = LocalContext.current
    val baseModifier = GlanceModifier
        .fillMaxSize()
        .background(WidgetTheme.contentBackground)
        .cornerRadius(16.dp)
    val modifier = if (onTapAction != null) baseModifier.clickable(onTapAction) else baseModifier
    Column(modifier = modifier) {
        UpcomingWidgetHeader()
        Box(
            modifier = GlanceModifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = context.getString(textRes),
                style = TextStyle(
                    color = WidgetTheme.secondaryText,
                    fontSize = 14.sp
                )
            )
        }
    }
}
