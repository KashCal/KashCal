package org.onekash.kashcal.ui.screens

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.search.MatchField
import org.onekash.kashcal.domain.search.SearchHit
import org.onekash.kashcal.domain.search.SearchOutcome
import org.onekash.kashcal.domain.search.SearchQuery
import org.onekash.kashcal.domain.search.SearchSplitter
import org.onekash.kashcal.ui.components.cardFillAlpha
import org.onekash.kashcal.ui.components.declinedCardAlpha
import org.onekash.kashcal.ui.components.eventStateDescription
import org.onekash.kashcal.ui.components.formatEventTitle
import org.onekash.kashcal.ui.components.pickers.InlineDatePickerContent
import org.onekash.kashcal.ui.screens.settings.settingsSearchHighlightStyle
import org.onekash.kashcal.ui.shared.formatSyncLookback
import org.onekash.kashcal.ui.util.LocalDisplayZone
import org.onekash.kashcal.ui.util.SelectedDay
import org.onekash.kashcal.ui.util.relativeSearchDate
import org.onekash.kashcal.ui.util.text.highlighted
import org.onekash.kashcal.ui.viewmodels.DateFilter
import org.onekash.kashcal.ui.viewmodels.SearchResultsState
import org.onekash.kashcal.ui.viewmodels.SearchTab
import org.onekash.kashcal.util.DateTimeUtils
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which list a search result card sits in; it decides the "Next:" or "Last:" prefix. */
internal enum class SearchList { UPCOMING, PAST, RANGE }

/** The search highlight: the Settings search tint, in bold. */
@Composable
private fun searchHighlightStyle(): SpanStyle = settingsSearchHighlightStyle().copy(fontWeight = FontWeight.Bold)

/**
 * Returns a search result's date line: the date (a range for a multi-day event, the time for a
 * timed one, " 🔁" for a series), "Next:" or "Last:" before a series' date under Upcoming or
 * Past, then how far it is from [today].
 */
@Composable
internal fun searchHitDateLine(hit: SearchHit, list: SearchList, today: LocalDate, zone: ZoneId, timePattern: String): String {
    val e = hit.displayEvent
    val date = remember(hit, timePattern, zone) {
        formatSearchResultDate(e.startTs, e.endTs, e.isAllDay, hit.isSeries, zone, timePattern)
    }
    val dated = when {
        !hit.isSeries || list == SearchList.RANGE -> date
        list == SearchList.UPCOMING -> stringResource(R.string.search_next, date)
        else -> stringResource(R.string.search_last, date)
    }
    val day = DateTimeUtils.eventTsToLocalDate(e.startTs, e.isAllDay, zone)
    return stringResource(R.string.search_date_relative, dated, remember(day, today) { relativeSearchDate(day, today) })
}

@Composable
internal fun SearchResultCard(
    searchResult: SearchHit,
    list: SearchList,
    words: List<String>,
    today: LocalDate,
    isPast: Boolean,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayEvent = searchResult.displayEvent
    val stripeColor = Color(displayEvent.calendarColor)
    val fillColor = Color(displayEvent.eventColor ?: displayEvent.calendarColor)
    val fillAlpha = displayEvent.cardFillAlpha()
    val zone = LocalDisplayZone.current
    val highlight = searchHighlightStyle()

    val dateString = searchHitDateLine(searchResult, list, today, zone, timePattern)

    // Title with the age on the shown date for birthday events, and the optional emoji
    val resources = LocalResources.current
    val displayTitle = remember(searchResult, showEventEmojis) {
        when (displayEvent) {
            is DisplayEvent.Room -> formatEventTitle(displayEvent.event, searchResult.displayTs, showEventEmojis, resources)
            is DisplayEvent.Device -> EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis)
        }
    }
    val reason = searchResult.reason
    val reasonLabel = reason?.let {
        stringResource(
            when (it.field) {
                MatchField.LOCATION -> R.string.search_reason_location
                MatchField.NOTES -> R.string.search_reason_notes
                MatchField.GUEST -> R.string.search_reason_guest
            }
        )
    }

    val searchStateLabel = eventStateDescription(isPast, displayEvent.isDeclinedByMe, displayEvent.isCancelled)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(declinedCardAlpha(isPast, displayEvent.isDeclinedByMe, displayEvent.isCancelled))
            .then(if (searchStateLabel != null) Modifier.semantics { stateDescription = searchStateLabel } else Modifier)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = fillColor.copy(alpha = fillAlpha)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(stripeColor)
            )
            Column(modifier = Modifier.padding(12.dp).weight(1f)) {
                Text(
                    highlighted(displayTitle, words, highlight),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("search_card_title")
                )
                Text(
                    dateString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("search_card_date")
                )
                if (searchResult.isHidden) {
                    Text(
                        stringResource(R.string.search_hidden_calendar, displayEvent.calendarName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("search_card_hidden")
                    )
                }
                if (reason != null && reasonLabel != null) {
                    Text(
                        buildAnnotatedString {
                            append(reasonLabel)
                            append(' ')
                            append(highlighted(reason.text, words, highlight))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("search_card_reason")
                    )
                }
                if (!displayEvent.location.isNullOrEmpty() && reason?.field != MatchField.LOCATION) {
                    Text(
                        displayEvent.location!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Returns the years a Past list labels: each hit's year in [zone] where it differs from the hit
 * before, or nothing when every hit falls in one year.
 */
internal fun pastYearHeaders(hits: List<SearchHit>, zone: ZoneId): Map<Int, Int> {
    val years = hits.map { DateTimeUtils.eventTsToLocalDate(it.displayEvent.startTs, it.displayEvent.isAllDay, zone).year }
    if (years.distinct().size < 2) return emptyMap()
    return years.withIndex().filter { (i, year) -> i == 0 || years[i - 1] != year }.associate { it.index to it.value }
}

/**
 * Returns the hits a search list shows: the [tab]'s list, or with [hasRange] the range list,
 * plus the matches in hidden calendars when [includeHidden], in the list's order
 * ([SearchSplitter.byDate]: Past most recent first, the others soonest first).
 */
internal fun searchListHits(
    outcome: SearchOutcome,
    tab: SearchTab,
    hasRange: Boolean,
    includeHidden: Boolean,
    zone: ZoneId,
): List<SearchHit> {
    val (shown, hidden, latestFirst) = when {
        hasRange -> Triple(outcome.range.orEmpty(), outcome.hiddenRange.orEmpty(), false)
        tab == SearchTab.PAST -> Triple(outcome.past, outcome.hiddenPast, true)
        else -> Triple(outcome.upcoming, outcome.hiddenUpcoming, false)
    }
    if (!includeHidden || hidden.isEmpty()) return shown
    val merged = shown + hidden
    val order = SearchSplitter.byDate(zone)
    return merged.sortedWith(if (latestFirst) order.reversed() else order)
}

/** Returns how many hits the Upcoming or Past list shows; see [searchListHits]. */
internal fun searchCount(outcome: SearchOutcome, tab: SearchTab, includeHidden: Boolean): Int = when (tab) {
    SearchTab.UPCOMING -> outcome.upcoming.size + if (includeHidden) outcome.hiddenUpcoming.size else 0
    SearchTab.PAST -> outcome.past.size + if (includeHidden) outcome.hiddenPast.size else 0
}

/** Most results a chip counts in digits; above it the chip reads "99+". */
private const val MAX_CHIP_COUNT = 99

/**
 * Returns the localized range of [startMs] to [endMs] (the last millisecond of the range) in
 * [zone], as one line: "Nov 3 – Dec 15", with the year only when the range leaves the current
 * year ("Dec 28, 2026 – Jan 4, 2027").
 */
internal fun formatSearchRange(context: Context, startMs: Long, endMs: Long, zone: ZoneId): String {
    val thisYear = LocalDate.now(zone).year
    val inThisYear = Instant.ofEpochMilli(startMs).atZone(zone).year == thisYear &&
        Instant.ofEpochMilli(endMs).atZone(zone).year == thisYear
    val year = if (inThisYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or year
    // DateUtils takes the end as exclusive.
    return DateUtils.formatDateRange(context, java.util.Formatter(), startMs, endMs + 1, flags, zone.id).toString()
}

/** Separators a localized date range puts between its ends, most specific first. */
private val RANGE_SEPARATORS = listOf("\u2013", "\u2014", "\uFF5E", "\u301C", "~", " - ")

/**
 * Puts a range's end on a second line, breaking after the locale's separator ("Nov 3 –" over
 * "Dec 15"); a label with no separator stays on one line.
 */
internal fun breakSearchRange(range: String): String {
    val (index, separator) = RANGE_SEPARATORS.firstNotNullOfOrNull { sep ->
        range.indexOf(sep).takeIf { it > 0 }?.let { it to sep }
    } ?: return range
    val head = range.substring(0, index + separator.length).trimEnd()
    val tail = range.substring(index + separator.length).trimStart()
    return if (tail.isEmpty()) range else "$head\n$tail"
}

/**
 * Returns the Date chip's label for [filter], on one line: the menu item's name for This week
 * and This month, the localized range for picked dates, or null for no filter.
 */
@Composable
internal fun searchDateLabel(filter: DateFilter?, zone: ZoneId): String? {
    val context = LocalContext.current
    return when (filter) {
        null -> null
        DateFilter.ThisWeek -> stringResource(R.string.search_date_this_week)
        DateFilter.ThisMonth -> stringResource(R.string.search_date_this_month)
        is DateFilter.SingleDay, is DateFilter.CustomRange -> remember(filter, zone, context) {
            val (from, to) = filter.getTimeRange(zone)
            formatSearchRange(context, from, to, zone)
        }
    }
}

/** Which of the three search chips is selected. */
internal enum class SearchChip { UPCOMING, PAST, DATE }

/** Formats a chip count: "–" before results arrive, "99+" above [MAX_CHIP_COUNT]. */
@Composable
private fun chipCountText(count: Int?): String {
    val format = remember { NumberFormat.getIntegerInstance() }
    return when {
        count == null -> stringResource(R.string.search_count_pending)
        count > MAX_CHIP_COUNT -> stringResource(R.string.search_count_max, format.format(MAX_CHIP_COUNT))
        else -> format.format(count)
    }
}

/**
 * Shows the three search chips at one width and height: the row is as tall as its tallest
 * chip and each chip fills a third. Upcoming and Past show their name over their count; Date
 * shows its name or, with a range, only the range (a picked range on two lines, start then
 * end). A name that does not fit shrinks to 11 sp, then ends with "…"; the count only shrinks.
 *
 * For TalkBack the chips are one radio group: each reads its name and count ("Past, 14
 * events"), whether it is selected and its place ("2 of 3"); the Date chip reads the range on
 * one line. [dateMenu] is drawn anchored to the Date chip.
 */
@Composable
internal fun SearchChipRow(
    upcomingName: String,
    pastName: String,
    dateName: String,
    upcomingCount: Int?,
    pastCount: Int?,
    selected: SearchChip,
    dateLabel: String?,
    dateLabelTwoLines: Boolean,
    onChipClick: (SearchChip) -> Unit,
    modifier: Modifier = Modifier,
    dateMenu: @Composable () -> Unit = {},
) {
    val resources = LocalResources.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics {
                selectableGroup()
                collectionInfo = CollectionInfo(rowCount = 1, columnCount = 3)
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((index, chip) in SearchChip.entries.withIndex()) {
            val isSelected = chip == selected
            val name = when (chip) {
                SearchChip.UPCOMING -> upcomingName
                SearchChip.PAST -> pastName
                SearchChip.DATE -> dateName
            }
            val count = when (chip) {
                SearchChip.UPCOMING -> upcomingCount
                SearchChip.PAST -> pastCount
                SearchChip.DATE -> null
            }
            val description = when {
                chip == SearchChip.DATE -> dateLabel ?: dateName
                count == null -> name
                else -> resources.getQuantityString(R.plurals.search_chip_events, count, name, count)
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                FilterChip(
                    selected = isSelected,
                    onClick = { onChipClick(chip) },
                    modifier = Modifier
                        .fillMaxSize()
                        .heightIn(min = 48.dp)
                        .testTag("search_chip_${chip.name.lowercase()}")
                        .clearAndSetSemantics {
                            role = Role.RadioButton
                            this.selected = isSelected
                            contentDescription = description
                            collectionItemInfo = CollectionItemInfo(0, 1, index, 1)
                            onClick { onChipClick(chip); true }
                        },
                    label = {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (chip == SearchChip.DATE && dateLabel != null) {
                                ChipText(
                                    text = if (dateLabelTwoLines) breakSearchRange(dateLabel) else dateLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 2,
                                )
                            } else {
                                ChipText(name, MaterialTheme.typography.labelLarge, maxLines = 1)
                                if (chip != SearchChip.DATE) {
                                    ChipText(
                                        text = chipCountText(count),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        ellipsis = false,
                                        modifier = Modifier.testTag("search_chip_count_${chip.name.lowercase()}"),
                                    )
                                }
                            }
                        }
                    },
                    trailingIcon = if (chip == SearchChip.DATE) {
                        {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
                if (chip == SearchChip.DATE) dateMenu()
            }
        }
    }
}

/** A chip line that shrinks to fit, down to 11 sp, then ends with "…" unless not [ellipsis]. */
@Composable
private fun ChipText(
    text: String,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    ellipsis: Boolean = true,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        textAlign = TextAlign.Center,
        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/**
 * Shows search results under three chips, Upcoming and Past with their counts and Date, which
 * opens a menu of This week, This month and Pick dates. One list shows: the selected chip's,
 * or a picked range's in date order with ended matches dimmed. Tapping the selected Upcoming
 * or Past chip scrolls its list to the top. The chips sit outside the LazyColumn.
 */
@Composable
internal fun SearchContent(
    query: String,
    state: SearchResultsState,
    tab: SearchTab,
    dateFilter: DateFilter?,
    showDateMenu: Boolean,
    includeHidden: Boolean,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    onResultClick: (Event, Long?) -> Unit,
    onDeviceEventClick: (DisplayEvent.Device) -> Unit = {},
    onTabSelect: (SearchTab) -> Unit,
    onDateChipClick: () -> Unit,
    onDateMenuDismiss: () -> Unit,
    onDateFilterSelect: (DateFilter) -> Unit,
    onPickDates: () -> Unit,
    onIncludeHiddenChange: (Boolean) -> Unit = {},
    onOpenSyncSettings: () -> Unit = {},
) {
    if (state is SearchResultsState.TooShort) {
        SearchMessage(stringResource(R.string.search_keep_typing), Modifier.fillMaxSize())
        return
    }
    val outcome = (state as? SearchResultsState.Loaded)?.outcome
    val hasRange = dateFilter != null
    val listKey = if (hasRange) "range:$dateFilter" else tab.name
    val listState = key(listKey) { rememberLazyListState() }
    val scope = rememberCoroutineScope()
    val zone = LocalDisplayZone.current
    val words = remember(query) { SearchQuery.of(query).words }
    val today = remember(zone, outcome) { LocalDate.now(zone) }
    val keyboard = LocalSoftwareKeyboardController.current
    // Scrolling the results closes the keyboard, so the list is free to read.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { it }.collect { keyboard?.hide() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (outcome != null) SearchCountsAnnouncement(outcome, includeHidden)
        SearchChipRow(
            upcomingName = stringResource(R.string.search_tab_upcoming),
            pastName = stringResource(R.string.search_tab_past),
            dateName = stringResource(R.string.filter_date),
            upcomingCount = outcome?.let { searchCount(it, SearchTab.UPCOMING, includeHidden) },
            pastCount = outcome?.let { searchCount(it, SearchTab.PAST, includeHidden) },
            selected = when {
                hasRange -> SearchChip.DATE
                tab == SearchTab.PAST -> SearchChip.PAST
                else -> SearchChip.UPCOMING
            },
            dateLabel = searchDateLabel(dateFilter, zone),
            dateLabelTwoLines = dateFilter is DateFilter.SingleDay || dateFilter is DateFilter.CustomRange,
            onChipClick = { chip ->
                when (chip) {
                    SearchChip.DATE -> onDateChipClick()
                    else -> {
                        val chipTab = if (chip == SearchChip.PAST) SearchTab.PAST else SearchTab.UPCOMING
                        if (!hasRange && tab == chipTab) scope.launch { listState.animateScrollToItem(0) } else onTabSelect(chipTab)
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            dateMenu = {
                DropdownMenu(expanded = showDateMenu, onDismissRequest = onDateMenuDismiss) {
                    SearchDateMenuItem(R.string.search_date_this_week, R.string.cd_search_date_this_week) {
                        onDateFilterSelect(DateFilter.ThisWeek)
                    }
                    SearchDateMenuItem(R.string.search_date_this_month, R.string.cd_search_date_this_month) {
                        onDateFilterSelect(DateFilter.ThisMonth)
                    }
                    SearchDateMenuItem(R.string.search_date_pick, R.string.cd_search_date_pick, onPickDates)
                }
            },
        )

        if (outcome == null) return@Column
        val hits = remember(outcome, tab, hasRange, includeHidden, zone) {
            searchListHits(outcome, tab, hasRange, includeHidden, zone)
        }
        val hiddenHits = when {
            hasRange -> outcome.hiddenRange.orEmpty()
            tab == SearchTab.PAST -> outcome.hiddenPast
            else -> outcome.hiddenUpcoming
        }
        val list = when {
            hasRange -> SearchList.RANGE
            tab == SearchTab.PAST -> SearchList.PAST
            else -> SearchList.UPCOMING
        }
        val nothingAnywhere = outcome.upcoming.isEmpty() && outcome.past.isEmpty() &&
            outcome.hiddenUpcoming.isEmpty() && outcome.hiddenPast.isEmpty() &&
            outcome.range.isNullOrEmpty() && outcome.hiddenRange.isNullOrEmpty()

        if (hits.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                if (nothingAnywhere) {
                    SearchNoMatches(query, outcome.lookbackDays, onOpenSyncSettings)
                } else {
                    val empty = when (list) {
                        SearchList.RANGE -> R.string.search_no_events_in_dates
                        SearchList.PAST -> R.string.search_no_past_events
                        SearchList.UPCOMING -> R.string.empty_no_upcoming_events
                    }
                    SearchMessage(stringResource(empty))
                    val pastCount = searchCount(outcome, SearchTab.PAST, includeHidden)
                    if (list == SearchList.UPCOMING && pastCount > 0) {
                        val label = pluralStringResource(R.plurals.search_show_past, pastCount, pastCount)
                        AssistChip(
                            onClick = { onTabSelect(SearchTab.PAST) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("search_show_past"),
                        )
                    }
                    if (!includeHidden && hiddenHits.isNotEmpty()) {
                        val n = hiddenHits.size
                        val description = pluralStringResource(R.plurals.cd_search_show_hidden, n, n)
                        AssistChip(
                            onClick = { onIncludeHiddenChange(true) },
                            label = { Text(pluralStringResource(R.plurals.search_show_in_hidden, n, n)) },
                            modifier = Modifier
                                .testTag("search_show_in_hidden")
                                .semantics { contentDescription = description },
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val headers = if (list == SearchList.PAST) pastYearHeaders(hits, zone) else emptyMap<Int, Int>()
                hits.forEachIndexed { index, hit ->
                    headers[index]?.let { year ->
                        item(key = "year:$year", contentType = "search_year") {
                            Text(
                                text = remember(year) { LocalDate.of(year, 1, 1).format(DateTimeUtils.localizedFormatter("y")) },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 8.dp, start = 4.dp)
                                    .semantics { heading() }
                                    .testTag("search_year_header")
                            )
                        }
                    }
                    item(key = hit.displayEvent.stableKey, contentType = "search_result") {
                        val displayEvent = hit.displayEvent
                        // Only the range list dims what has ended; Past is past by its chip.
                        val isPast = list == SearchList.RANGE &&
                            DateTimeUtils.isEventPast(displayEvent.endTs, displayEvent.endDay, displayEvent.isAllDay)
                        SearchResultCard(
                            searchResult = hit,
                            list = list,
                            words = words,
                            today = today,
                            isPast = isPast,
                            showEventEmojis = showEventEmojis,
                            timePattern = timePattern,
                            modifier = Modifier.animateItem(),
                            onClick = {
                                when (displayEvent) {
                                    is DisplayEvent.Room -> onResultClick(displayEvent.event, hit.displayTs)
                                    is DisplayEvent.Device -> onDeviceEventClick(displayEvent)
                                }
                            }
                        )
                    }
                }
                if (!includeHidden && hiddenHits.isNotEmpty()) {
                    item(key = "hidden_footer", contentType = "search_footer") {
                        val n = hiddenHits.size
                        SearchFooterRow(
                            text = pluralStringResource(R.plurals.search_more_hidden, n, n),
                            action = stringResource(R.string.search_show),
                            actionDescription = pluralStringResource(R.plurals.cd_search_show_hidden, n, n),
                            onAction = { onIncludeHiddenChange(true) },
                            tag = "search_hidden_more",
                        )
                    }
                } else if (includeHidden) {
                    val calendars = hits.filter { it.isHidden }.map { it.displayEvent.calendarName }.distinct().size
                    if (calendars > 0) {
                        item(key = "hidden_footer", contentType = "search_footer") {
                            SearchFooterRow(
                                text = pluralStringResource(R.plurals.search_including_hidden, calendars, calendars),
                                action = stringResource(R.string.search_leave_out),
                                actionDescription = stringResource(R.string.cd_search_leave_out),
                                onAction = { onIncludeHiddenChange(false) },
                                tag = "search_hidden_included",
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One Date menu item, read by TalkBack as the action it takes. */
@Composable
private fun SearchDateMenuItem(label: Int, description: Int, onClick: () -> Unit) {
    val spoken = stringResource(description)
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}

/** A centered line of explanation in the search area. */
@Composable
private fun SearchMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp).testTag("search_message"),
        )
    }
}

/**
 * Says that nothing matched [query] and, when the phone holds only [lookbackDays] of events
 * (not "All events"), how far back it searched, with a link to the Sync lookback setting.
 */
@Composable
private fun SearchNoMatches(query: String, lookbackDays: Int, onOpenSyncSettings: () -> Unit) {
    SearchMessage(stringResource(R.string.search_no_matches, query.trim()))
    if (lookbackDays != Int.MAX_VALUE) {
        val resources = LocalResources.current
        Text(
            stringResource(R.string.search_lookback_line, formatSyncLookback(lookbackDays, resources)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("search_lookback_line"),
        )
        val description = stringResource(R.string.cd_search_sync_settings)
        TextButton(
            onClick = onOpenSyncSettings,
            modifier = Modifier.testTag("search_sync_settings").semantics { contentDescription = description },
        ) {
            Text(stringResource(R.string.search_sync_settings))
        }
    }
}

/** A row under the results: a note and one action, for the hidden-calendar hint and footer. */
@Composable
private fun SearchFooterRow(text: String, action: String, actionDescription: String, onAction: () -> Unit, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction, modifier = Modifier.semantics { contentDescription = actionDescription }) {
            Text(action)
        }
    }
}

/**
 * Tells TalkBack the counts ("2 upcoming, 14 past") through a polite live region. The text
 * changes only when a count does, so it is announced once per new result, not on each redraw.
 */
@Composable
private fun SearchCountsAnnouncement(outcome: SearchOutcome, includeHidden: Boolean) {
    val upcoming = searchCount(outcome, SearchTab.UPCOMING, includeHidden)
    val past = searchCount(outcome, SearchTab.PAST, includeHidden)
    val text = stringResource(
        R.string.search_announce,
        pluralStringResource(R.plurals.search_announce_upcoming, upcoming, upcoming),
        pluralStringResource(R.plurals.search_announce_past, past, past),
    )
    Box(
        modifier = Modifier
            .size(1.dp)
            .testTag("search_counts_announcement")
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            }
    )
}

/**
 * Formats a search result's date line:
 * - Multi-day: "Dec 20, 2023 → Dec 25, 2023"
 * - Single-day all-day: "Dec 20, 2023"
 * - Single-day timed: "Dec 20, 2023 · 9:00 AM"
 *
 * A series or exception adds " 🔁". An all-day [Event.endTs] is inclusive (the exclusive RFC 5545
 * DTEND minus 1 ms), so a Dec 20-22 event, DTEND Dec 23, shows as ending Dec 22.
 *
 * @param zoneId zone for the date calculations; injectable for tests.
 * @param timePattern time pattern; the default "h:mm a" is 12-hour.
 */
internal fun formatSearchResultDate(
    event: Event,
    zoneId: ZoneId = ZoneId.systemDefault(),
    timePattern: String = "h:mm a"
): String = formatSearchResultDate(
    event.startTs, event.endTs, event.isAllDay, event.isRecurring || event.isException, zoneId, timePattern
)

/** Formats the date line of an occurrence from its times, as the [Event] overload describes. */
internal fun formatSearchResultDate(
    startTs: Long,
    endTs: Long,
    isAllDay: Boolean,
    isSeries: Boolean,
    zoneId: ZoneId,
    timePattern: String,
): String {
    val dateFormatter = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMMd"), Locale.getDefault())
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())

    val startDate = DateTimeUtils.eventTsToLocalDate(startTs, isAllDay, zoneId)
    val displayEndDate = DateTimeUtils.eventTsToLocalDate(endTs, isAllDay, zoneId)
    val isMultiDay = DateTimeUtils.spansMultipleDays(startTs, endTs, isAllDay, zoneId)

    return buildString {
        append(startDate.format(dateFormatter))
        if (isMultiDay) {
            append(" \u2192 ${displayEndDate.format(dateFormatter)}")
        } else if (!isAllDay) {
            val startTime = Instant.ofEpochMilli(startTs).atZone(zoneId).format(timeFormatter)
            append(" \u00B7 $startTime")
        }
        if (isSeries) append(" \uD83D\uDD01")
    }
}

// ==================== Search Date Picker Components ====================

/**
 * Shows the search date sheet for picking a single day or a date range, with
 * InlineDatePickerContent. Each tap goes to [onDateSelected]; the ViewModel decides:
 * - First tap: stores the range start, which comes back as [selectedDateMs]
 * - Second tap on the same day: a SingleDay filter
 * - Second tap on another day: a CustomRange filter
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchDatePickerSheet(
    selectedDateMs: Long?,
    onDateSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Remembered so the fallback to now doesn't change on every frame of the sheet animation,
    // which causes jank
    val stableSelectedDate = remember(selectedDateMs) {
        selectedDateMs ?: System.currentTimeMillis()
    }

    // Displayed month; opens on the selected date's month, or the current month
    var displayedMonth by remember {
        mutableStateOf(SelectedDay.yearMonth(selectedDateMs ?: System.currentTimeMillis()))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (selectedDateMs == null) {
                    stringResource(R.string.label_select_date)
                } else {
                    stringResource(R.string.hint_tap_date_range)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            InlineDatePickerContent(
                selectedDateMillis = stableSelectedDate,
                displayedMonth = displayedMonth,
                onDateSelect = { dateMs ->
                    onDateSelected(dateMs)
                },
                onMonthChange = { newMonth ->
                    displayedMonth = newMonth
                },
                firstDayOfWeek = firstDayOfWeek
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.action_cancel))
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
