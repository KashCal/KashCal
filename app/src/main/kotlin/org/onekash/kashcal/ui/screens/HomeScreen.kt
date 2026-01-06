package org.onekash.kashcal.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.db.dao.EventWithNextOccurrence
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.reader.EventReader.OccurrenceWithEvent
import org.onekash.kashcal.ui.components.SyncBanner
import org.onekash.kashcal.ui.components.YearOverlay
import org.onekash.kashcal.ui.components.pickers.InlineDatePickerContent
import org.onekash.kashcal.ui.viewmodels.DateFilter
import org.onekash.kashcal.ui.viewmodels.HomeUiState
import org.onekash.kashcal.util.DateTimeUtils
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.Calendar as JavaCalendar

private const val TAG = "HomeScreen"

/**
 * Main calendar screen for KashCal.
 *
 * Features:
 * - Month view with horizontal paging
 * - Event dots on calendar days
 * - Day selection with event list
 * - Search functionality
 * - Pull-to-refresh sync
 * - Offline indicator
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    isOnline: Boolean = true,
    // Navigation callbacks
    onDateSelected: (Long) -> Unit,
    onGoToToday: () -> Unit,
    onSetViewingMonth: (Int, Int) -> Unit,
    onClearNavigateToToday: () -> Unit,
    onClearNavigateToMonth: () -> Unit,
    // Event callbacks
    onEventClick: (Event, Long?) -> Unit = { _, _ -> },  // (event, occurrenceStartTs)
    onCreateEvent: () -> Unit = {},
    // Sync callbacks
    onRefresh: () -> Unit = {},
    // Search callbacks
    onSearchClick: () -> Unit = {},
    onSearchClose: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onSearchResultClick: (Event, Long?) -> Unit = { _, _ -> },  // (event, nextOccurrenceTs)
    onSearchIncludePastChange: () -> Unit = {},
    // Search date filter callbacks
    onSearchDateFilterChange: (DateFilter) -> Unit = {},
    onSearchShowDatePicker: () -> Unit = {},
    onSearchHideDatePicker: () -> Unit = {},
    onSearchDateSelected: (Long) -> Unit = {},
    // Settings/filter callbacks
    onSettingsClick: () -> Unit = {},
    onFilterClick: () -> Unit = {},
    // Info callbacks
    onInfoClick: () -> Unit = {},
    // Agenda callbacks
    onAgendaClick: () -> Unit = {},
    onAgendaClose: () -> Unit = {},
    // Year overlay callbacks
    onMonthHeaderClick: () -> Unit = {},
    onYearOverlayDismiss: () -> Unit = {},
    onMonthSelected: (Int, Int) -> Unit = { _, _ -> },
    // Snackbar callback
    onClearSnackbar: () -> Unit = {}
) {
    // HorizontalPager for smooth month swiping (1200 pages = ~100 years, centered at 600)
    val initialPage = 600
    val pagerState = rememberPagerState(initialPage = initialPage) { 1200 }
    val coroutineScope = rememberCoroutineScope()

    // Today's date for reference
    val todayCal = JavaCalendar.getInstance()
    val todayYear = todayCal.get(JavaCalendar.YEAR)
    val todayMonth = todayCal.get(JavaCalendar.MONTH)

    // Focus requester for search field
    val searchFocusRequester = remember { FocusRequester() }

    // Request focus when search becomes active
    LaunchedEffect(uiState.isSearchActive) {
        if (uiState.isSearchActive) {
            try {
                searchFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus may not be available yet
            }
        }
    }

    // Snackbar state
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle snackbar messages
    LaunchedEffect(uiState.pendingSnackbarMessage) {
        uiState.pendingSnackbarMessage?.let { message ->
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (uiState.pendingSnackbarAction != null) "View" else null,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                uiState.pendingSnackbarAction?.invoke()
            }
            onClearSnackbar()
        }
    }

    // Sync pager with ViewModel when pager settles
    LaunchedEffect(pagerState.settledPage) {
        val monthOffset = pagerState.settledPage - initialPage
        val targetCal = JavaCalendar.getInstance().apply {
            set(todayYear, todayMonth, 1)
            add(JavaCalendar.MONTH, monthOffset)
        }
        val targetYear = targetCal.get(JavaCalendar.YEAR)
        val targetMonth = targetCal.get(JavaCalendar.MONTH)
        if (targetYear != uiState.viewingYear || targetMonth != uiState.viewingMonth) {
            onSetViewingMonth(targetYear, targetMonth)
        }
    }

    // Handle Today button navigation
    LaunchedEffect(uiState.pendingNavigateToToday) {
        if (uiState.pendingNavigateToToday) {
            pagerState.animateScrollToPage(initialPage)
            onClearNavigateToToday()
        }
    }

    // Handle year overlay month navigation
    LaunchedEffect(uiState.pendingNavigateToMonth) {
        uiState.pendingNavigateToMonth?.let { (targetYear, targetMonth) ->
            val monthsDiff = (targetYear - todayYear) * 12 + (targetMonth - todayMonth)
            val targetPage = initialPage + monthsDiff
            pagerState.animateScrollToPage(targetPage)
            onClearNavigateToMonth()
        }
    }

    // Year overlay for quick month navigation (tap month header to open)
    YearOverlay(
        visible = uiState.showYearOverlay,
        currentYear = uiState.viewingYear,
        currentMonth = uiState.viewingMonth,
        onMonthSelected = onMonthSelected,
        onDismiss = onYearOverlayDismiss
    )

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary
                )
            }
        },
        topBar = {
            HomeTopAppBar(
                uiState = uiState,
                searchFocusRequester = searchFocusRequester,
                onSearchClick = onSearchClick,
                onSearchClose = onSearchClose,
                onSearchQueryChange = onSearchQueryChange,
                onAgendaClick = onAgendaClick,
                onAgendaClose = onAgendaClose,
                onGoToToday = onGoToToday,
                onSettingsClick = onSettingsClick,
                onInfoClick = onInfoClick
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateEvent,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create event")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Offline banner
            AnimatedVisibility(visible = !isOnline) {
                OfflineBanner()
            }

            // Sync progress banner
            AnimatedVisibility(visible = uiState.showSyncBanner) {
                SyncBanner(message = uiState.syncBannerMessage)
            }

            PullToRefreshBox(
                isRefreshing = uiState.isSyncing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    when {
                        uiState.isLoading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        uiState.isSearchActive && uiState.searchQuery.isNotEmpty() -> {
                            // SearchContent with 4 date filter chips
                            SearchContent(
                                results = uiState.searchResults,
                                calendars = uiState.calendars,
                                currentFilter = uiState.searchDateFilter,
                                onResultClick = onSearchResultClick,
                                onFilterSelect = onSearchDateFilterChange,
                                onCustomDateClick = onSearchShowDatePicker
                            )
                        }
                        uiState.showAgendaPanel -> {
                            if (uiState.isLoadingAgenda) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                AgendaContent(
                                    occurrences = uiState.agendaOccurrences,
                                    calendars = uiState.calendars,
                                    onEventClick = { event, occurrenceTs ->
                                        onEventClick(event, occurrenceTs)
                                    }
                                )
                            }
                        }
                        else -> {
                            // Calendar with pager
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) { page ->
                                val monthOffset = page - initialPage
                                val pageCal = JavaCalendar.getInstance().apply {
                                    set(todayYear, todayMonth, 1)
                                    add(JavaCalendar.MONTH, monthOffset)
                                }
                                val pageYear = pageCal.get(JavaCalendar.YEAR)
                                val pageMonth = pageCal.get(JavaCalendar.MONTH)

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.Top
                                ) {
                                    // Month navigation header
                                    MonthNavHeader(
                                        year = pageYear,
                                        month = pageMonth,
                                        onPrevious = {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                            }
                                        },
                                        onNext = {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                            }
                                        },
                                        onMonthClick = onMonthHeaderClick
                                    )

                                    // Day of week headers
                                    DayOfWeekHeaders()
                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Calendar grid
                                    CalendarGrid(
                                        year = pageYear,
                                        month = pageMonth,
                                        selectedDate = uiState.selectedDate,
                                        eventDots = uiState.eventDots,
                                        onDateSelected = onDateSelected
                                    )
                                }
                            }

                            // Event list below calendar
                            HorizontalDivider()
                            EventListForSelectedDay(
                                uiState = uiState,
                                calendars = uiState.calendars,
                                onEventClick = onEventClick
                            )
                        }
                    }
                }
            }
        }
    }

    // Search date picker bottom sheet
    if (uiState.showSearchDatePicker) {
        SearchDatePickerSheet(
            selectedDateMs = uiState.searchDateRangeStart,
            onDateSelected = onSearchDateSelected,
            onDismiss = onSearchHideDatePicker
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopAppBar(
    uiState: HomeUiState,
    searchFocusRequester: FocusRequester,
    onSearchClick: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onAgendaClick: () -> Unit,
    onAgendaClose: () -> Unit,
    onGoToToday: () -> Unit,
    onSettingsClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    when {
        uiState.isSearchActive -> {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onSearchClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(28.dp))
                        }
                        BasicTextField(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier.weight(1f).focusRequester(searchFocusRequester),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    if (uiState.searchQuery.isEmpty()) {
                                        Text("Search events...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
            )
        }
        uiState.showAgendaPanel -> {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onAgendaClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(28.dp))
                        }
                        Text("Agenda", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
        else -> {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "KashCal",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onInfoClick() }
                    )
                },
                navigationIcon = {
                    Row {
                        IconButton(onClick = onSearchClick) {
                            Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = onAgendaClick) {
                            Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "Agenda", modifier = Modifier.size(28.dp))
                        }
                    }
                },
                actions = {
                    TodayButton(onClick = onGoToToday)
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Tune, contentDescription = "Settings", modifier = Modifier.size(28.dp))
                    }
                }
            )
        }
    }
}

@Composable
private fun TodayButton(onClick: () -> Unit) {
    val today = JavaCalendar.getInstance()
    val dayOfMonth = today.get(JavaCalendar.DAY_OF_MONTH)

    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.CalendarToday,
                contentDescription = "Today",
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = dayOfMonth.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(y = 2.dp)
            )
        }
    }
}

@Composable
private fun OfflineBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Offline - Changes will sync when connected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun MonthNavHeader(
    year: Int,
    month: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMonthClick: () -> Unit
) {
    val calendar = JavaCalendar.getInstance().apply { set(year, month, 1) }
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.ChevronLeft, "Previous")
        }
        Text(
            text = monthFormat.format(calendar.time),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onMonthClick)
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Default.ChevronRight, "Next")
        }
    }
}

@Composable
private fun DayOfWeekHeaders() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val daysOfWeek = listOf(
            java.time.DayOfWeek.SUNDAY, java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY,
            java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY, java.time.DayOfWeek.SATURDAY
        )
        daysOfWeek.forEach { day ->
            val isWeekend = day == java.time.DayOfWeek.SUNDAY || day == java.time.DayOfWeek.SATURDAY
            Text(
                text = day.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (isWeekend) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    year: Int,
    month: Int,
    selectedDate: Long,
    eventDots: ImmutableMap<String, ImmutableMap<Int, ImmutableList<Int>>>,
    onDateSelected: (Long) -> Unit
) {
    val monthKey = remember(year, month) { String.format("%04d-%02d", year, month + 1) }
    val monthDots = remember(eventDots, monthKey) { eventDots[monthKey] ?: emptyMap() }

    val calendar = JavaCalendar.getInstance().apply { set(year, month, 1) }
    val daysInMonth = calendar.getActualMaximum(JavaCalendar.DAY_OF_MONTH)
    val firstDayOfWeek = calendar.get(JavaCalendar.DAY_OF_WEEK) - 1

    val today = JavaCalendar.getInstance()
    val selectedCal = JavaCalendar.getInstance().apply { timeInMillis = selectedDate }
    val selectedInThisMonth = selectedCal.get(JavaCalendar.MONTH) == month &&
                               selectedCal.get(JavaCalendar.YEAR) == year

    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        var dayCounter = 1
        for (week in 0..5) {
            if (dayCounter > daysInMonth) break

            Row(modifier = Modifier.fillMaxWidth()) {
                for (dayOfWeek in 0..6) {
                    if (week == 0 && dayOfWeek < firstDayOfWeek || dayCounter > daysInMonth) {
                        Box(modifier = Modifier.weight(1f).height(44.dp))
                    } else {
                        val day = dayCounter
                        val dateCal = JavaCalendar.getInstance().apply { set(year, month, day) }
                        val isToday = dateCal.get(JavaCalendar.DAY_OF_YEAR) == today.get(JavaCalendar.DAY_OF_YEAR) &&
                                      dateCal.get(JavaCalendar.YEAR) == today.get(JavaCalendar.YEAR)
                        val isSelected = selectedInThisMonth && day == selectedCal.get(JavaCalendar.DAY_OF_MONTH)
                        val isWeekend = dayOfWeek == 0 || dayOfWeek == 6

                        val dayColors = monthDots[day] ?: emptyList()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.primaryContainer
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable {
                                    val clickedCal = JavaCalendar.getInstance().apply { set(year, month, day) }
                                    onDateSelected(clickedCal.timeInMillis)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = day.toString(),
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                                        isWeekend -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                if (dayColors.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        dayColors.take(3).forEach { colorInt ->
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(colorInt))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        dayCounter++
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.EventListForSelectedDay(
    uiState: HomeUiState,
    calendars: ImmutableList<Calendar>,
    onEventClick: (Event, Long?) -> Unit  // (event, occurrenceStartTs)
) {
    val colorMap = remember(calendars) { calendars.associate { it.id to Color(it.color) } }
    val defaultColor = Color(0xFF6200EE)

    val selectedCal = remember(uiState.selectedDate) {
        JavaCalendar.getInstance().apply { timeInMillis = uiState.selectedDate }
    }
    val isSelectedInViewingMonth = selectedCal.get(JavaCalendar.MONTH) == uiState.viewingMonth &&
        selectedCal.get(JavaCalendar.YEAR) == uiState.viewingYear

    when {
        !isSelectedInViewingMonth -> {
            Text(
                "Tap a day to see events",
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        uiState.isLoadingDayEvents -> {
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        uiState.selectedDayEvents.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No events for this day", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else -> {
            // Map eventId to occurrence for correct endTs (recurring events)
            // BUG FIX: Use exceptionEventId if present, so exception events can be looked up by their ID
            val occurrenceMap = remember(uiState.selectedDayOccurrences) {
                uiState.selectedDayOccurrences.associateBy { occ ->
                    occ.exceptionEventId ?: occ.eventId
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.selectedDayEvents.forEach { event ->
                    val eventColor = colorMap[event.calendarId] ?: defaultColor
                    // Use occurrence's endTs for recurring events (not master event's endTs)
                    val occurrenceEndTs = occurrenceMap[event.id]?.endTs ?: event.endTs
                    val isPast = occurrenceEndTs < System.currentTimeMillis()
                    EventCard(
                        event = event,
                        eventColor = eventColor,
                        isPast = isPast,
                        selectedDate = uiState.selectedDate,
                        onClick = {
                            val occurrenceTs = occurrenceMap[event.id]?.startTs
                            onEventClick(event, occurrenceTs)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EventCard(
    event: Event,
    eventColor: Color,
    isPast: Boolean,
    selectedDate: Long,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isPast) 0.5f else 1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = eventColor.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(eventColor)
            )
            Column(modifier = Modifier.padding(12.dp).weight(1f)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val timeText = formatEventTimeDisplay(event, selectedDate)
                Text(
                    timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                if (!event.location.isNullOrEmpty()) {
                    Text(
                        event.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
@Composable
private fun SearchResultCard(
    event: Event,
    nextOccurrenceTs: Long?,
    eventColor: Color,
    isPast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // For recurring events with nextOccurrenceTs, show "Next: date" format
    // For non-recurring events, keep original format
    val dateString = remember(event, nextOccurrenceTs) {
        formatSearchResultDateWithOccurrence(event, nextOccurrenceTs)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isPast) 0.5f else 1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = eventColor.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(eventColor)
            )
            Column(modifier = Modifier.padding(12.dp).weight(1f)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    dateString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!event.location.isNullOrEmpty()) {
                    Text(
                        event.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Search content - displays search results with date filter chips.
 * Chips outside LazyColumn to avoid crash (no horizontalScroll needed).
 * Simplified to 4 essential chips: All, Week, Month, Date picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchContent(
    results: ImmutableList<EventWithNextOccurrence>,
    calendars: ImmutableList<Calendar>,
    currentFilter: DateFilter,
    onResultClick: (Event, Long?) -> Unit,
    onFilterSelect: (DateFilter) -> Unit,
    onCustomDateClick: () -> Unit
) {
    val colorMap = remember(calendars) { calendars.associate { it.id to Color(it.color) } }
    val defaultColor = Color(0xFF6200EE)

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter chips row - OUTSIDE LazyColumn, no scroll needed for 4 chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            FilterChip(
                selected = currentFilter == DateFilter.AnyTime,
                onClick = { onFilterSelect(DateFilter.AnyTime) },
                label = { Text("All") }
            )
            FilterChip(
                selected = currentFilter == DateFilter.ThisWeek,
                onClick = { onFilterSelect(DateFilter.ThisWeek) },
                label = { Text("Week") }
            )
            FilterChip(
                selected = currentFilter == DateFilter.ThisMonth,
                onClick = { onFilterSelect(DateFilter.ThisMonth) },
                label = { Text("Month") }
            )
            // Date picker chip - shows selected date or calendar icon
            val isCustom = currentFilter is DateFilter.SingleDay || currentFilter is DateFilter.CustomRange
            FilterChip(
                selected = isCustom,
                onClick = onCustomDateClick,
                label = { Text(if (isCustom) currentFilter.displayName else "Date") }
            )
        }

        // Content area with weight(1f)
        if (results.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No events found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = results,
                    key = { it.event.id }
                ) { result ->
                    val event = result.event
                    val eventColor = colorMap[event.calendarId] ?: defaultColor
                    // Use nextOccurrenceTs for isPast check if available
                    val checkTs = result.nextOccurrenceTs ?: event.endTs
                    val isPast = !event.isRecurring && !event.isException && checkTs < System.currentTimeMillis()
                    SearchResultCard(
                        event = event,
                        nextOccurrenceTs = result.nextOccurrenceTs,
                        eventColor = eventColor,
                        isPast = isPast,
                        onClick = { onResultClick(event, result.nextOccurrenceTs) }
                    )
                }
            }
        }
    }
}

/**
 * Display item for agenda - represents one day of an occurrence.
 * For multi-day events, one occurrence becomes multiple display items.
 */
private data class AgendaDisplayItem(
    val occWithEvent: OccurrenceWithEvent,
    val displayDay: Int,      // YYYYMMDD for this display entry
    val dayNumber: Int,       // 1, 2, 3... (which day of multi-day event)
    val totalDays: Int        // Total days in event (1 for single-day)
)

/**
 * Agenda content - shows upcoming 30 days of occurrences.
 * Each recurring event instance is shown separately.
 * Multi-day events appear on each day they span with "Day X of Y" indicator.
 * Groups occurrences by date with date headers.
 */
@Composable
private fun AgendaContent(
    occurrences: ImmutableList<OccurrenceWithEvent>,
    calendars: ImmutableList<Calendar>,
    onEventClick: (Event, Long) -> Unit  // (event, occurrenceStartTs)
) {
    val colorMap = remember(calendars) { calendars.associate { it.id to Color(it.color) } }
    val defaultColor = Color(0xFF6200EE)
    val dateFormat = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()) }

    // Calculate today's day code for filtering
    val todayDayCode = remember {
        val cal = JavaCalendar.getInstance()
        cal.get(JavaCalendar.YEAR) * 10000 +
            (cal.get(JavaCalendar.MONTH) + 1) * 100 +
            cal.get(JavaCalendar.DAY_OF_MONTH)
    }

    // Expand multi-day occurrences into separate display items per day
    val expandedItems = remember(occurrences, todayDayCode) {
        occurrences.flatMap { occWithEvent ->
            val occ = occWithEvent.occurrence
            if (occ.isMultiDay) {
                // Create entry for each day the event spans
                val items = mutableListOf<AgendaDisplayItem>()
                var currentDay = occ.startDay
                var dayNum = 1
                val total = occ.totalDays
                while (currentDay <= occ.endDay) {
                    items.add(AgendaDisplayItem(occWithEvent, currentDay, dayNum, total))
                    currentDay = Occurrence.incrementDayCode(currentDay)
                    dayNum++
                }
                items
            } else {
                listOf(AgendaDisplayItem(occWithEvent, occ.startDay, 1, 1))
            }
        }.filter { item ->
            // Only show items from today onwards
            item.displayDay >= todayDayCode
        }.sortedWith(compareBy(
            { it.displayDay },  // Primary: by date
            { it.occWithEvent.occurrence.startTs }  // Secondary: by original start time
        ))
    }

    if (expandedItems.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No upcoming events", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Group by display day
            val grouped = expandedItems.groupBy { it.displayDay }

            grouped.forEach { (displayDay, dayItems) ->
                item {
                    // Format the display day for header
                    val headerDate = Occurrence.dayFormatToCalendar(displayDay).time
                    Text(
                        dateFormat.format(headerDate),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                items(
                    items = dayItems,
                    key = { "${it.occWithEvent.event.id}-${it.occWithEvent.occurrence.startTs}-${it.displayDay}" }
                ) { item ->
                    val eventColor = colorMap[item.occWithEvent.event.calendarId] ?: defaultColor
                    val isPast = item.displayDay < todayDayCode
                    AgendaCard(
                        item = item,
                        eventColor = eventColor,
                        isPast = isPast,
                        onClick = { onEventClick(item.occWithEvent.event, item.occWithEvent.occurrence.startTs) }
                    )
                }
            }
        }
    }
}

/**
 * Card for displaying an agenda occurrence.
 * Shows time using occurrence timestamps (correct for recurring events).
 * Shows "Day X of Y" for multi-day events.
 */
@Composable
private fun AgendaCard(
    item: AgendaDisplayItem,
    eventColor: Color,
    isPast: Boolean,
    onClick: () -> Unit
) {
    val event = item.occWithEvent.event
    val occurrence = item.occWithEvent.occurrence
    val dateString = formatAgendaCardDate(event, occurrence, item.dayNumber, item.totalDays)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isPast) 0.5f else 1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = eventColor.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(eventColor)
            )
            Column(modifier = Modifier.padding(12.dp).weight(1f)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    dateString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!event.location.isNullOrEmpty()) {
                    Text(
                        event.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Format time display for agenda card.
 * Uses occurrence timestamps for correct recurring event display.
 * Shows "Day X of Y" for multi-day events.
 */
private fun formatAgendaCardDate(
    event: Event,
    occurrence: Occurrence,
    dayNumber: Int,
    totalDays: Int
): String {
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val isRecurring = event.isRecurring || event.isException
    val recurringIndicator = if (isRecurring) " \uD83D\uDD01" else ""

    return when {
        totalDays > 1 -> {
            // Multi-day event - show "Day X of Y" with context
            val dayIndicator = "Day $dayNumber of $totalDays"
            when {
                event.isAllDay -> "$dayIndicator (All day)$recurringIndicator"
                dayNumber == 1 -> {
                    val startTime = timeFormat.format(Date(occurrence.startTs))
                    "$dayIndicator \u2022 Starts $startTime$recurringIndicator"
                }
                dayNumber == totalDays -> {
                    val endTime = timeFormat.format(Date(occurrence.endTs))
                    "$dayIndicator \u2022 Ends $endTime$recurringIndicator"
                }
                else -> "$dayIndicator (All day)$recurringIndicator"
            }
        }
        event.isAllDay -> "All day$recurringIndicator"
        else -> {
            val startTime = timeFormat.format(Date(occurrence.startTs))
            val endTime = timeFormat.format(Date(occurrence.endTs))
            "$startTime - $endTime$recurringIndicator"
        }
    }
}

/**
 * Format event time display with multi-day indicator.
 * Shows "Day X of Y" for multi-day events.
 *
 * @param event The event to format
 * @param selectedDateMillis The currently selected date (to determine which day of multi-day)
 * @param zoneId Timezone for conversion (default: system default, injectable for testing)
 */
internal fun formatEventTimeDisplay(
    event: Event,
    selectedDateMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    // Use DateTimeUtils for correct timezone handling:
    // - All-day events use UTC to preserve calendar date
    // - Timed events use local timezone
    val startDate = DateTimeUtils.eventTsToLocalDate(event.startTs, event.isAllDay, zoneId)
    val endDate = DateTimeUtils.eventTsToLocalDate(event.endTs, event.isAllDay, zoneId)
    // Selected date from calendar picker is ALWAYS local time, regardless of event type
    val selectedDate = DateTimeUtils.eventTsToLocalDate(selectedDateMillis, isAllDay = false, zoneId)

    // Check if multi-day (endDate > startDate)
    val isMultiDay = endDate.isAfter(startDate)

    if (!isMultiDay) {
        // Single day event - include recurring indicator for recurring/exception events
        val recurringIndicator = if (event.isRecurring || event.isException) " \uD83D\uDD01" else ""
        return if (event.isAllDay) "All day$recurringIndicator"
        else {
            val startTime = Instant.ofEpochMilli(event.startTs).atZone(zoneId).format(timeFormatter)
            val endTime = Instant.ofEpochMilli(event.endTs).atZone(zoneId).format(timeFormatter)
            "$startTime - $endTime$recurringIndicator"
        }
    }

    // Multi-day event - use DateTimeUtils for correct day calculation
    val totalDays = DateTimeUtils.calculateTotalDays(event.startTs, event.endTs, event.isAllDay, zoneId)
    // Use helper that correctly handles selectedDateMillis as ALWAYS local time
    val currentDay = calculateCurrentDayForEvent(event.startTs, selectedDateMillis, event.isAllDay, zoneId)
        .coerceIn(1, totalDays)
    // Include recurring indicator for both master recurring events and exception events
    val recurringIndicator = if (event.isRecurring || event.isException) " \uD83D\uDD01" else ""

    return when {
        currentDay == 1 && !event.isAllDay -> {
            val startTime = Instant.ofEpochMilli(event.startTs).atZone(zoneId).format(timeFormatter)
            "Day 1 of $totalDays · starts $startTime$recurringIndicator"
        }
        currentDay == totalDays && !event.isAllDay -> {
            val endTime = Instant.ofEpochMilli(event.endTs).atZone(zoneId).format(timeFormatter)
            "Day $totalDays of $totalDays · ends $endTime$recurringIndicator"
        }
        else -> "Day $currentDay of $totalDays$recurringIndicator"
    }
}

/**
 * Calculate which day of a multi-day event the selected date falls on.
 *
 * Critical: selectedDateMillis is ALWAYS local time (from calendar picker),
 * while eventStartTs uses isAllDay to determine UTC vs local interpretation.
 *
 * @param eventStartTs Event start timestamp (UTC for all-day, local for timed)
 * @param selectedDateMillis Calendar picker selection (ALWAYS local time)
 * @param isAllDay Whether the event is all-day (affects eventStartTs interpretation)
 * @param zoneId Timezone for local time calculations
 * @return 1-based day number (1 = first day of event)
 */
private fun calculateCurrentDayForEvent(
    eventStartTs: Long,
    selectedDateMillis: Long,
    isAllDay: Boolean,
    zoneId: ZoneId
): Int {
    // Event start uses isAllDay for correct timezone handling
    val startDate = DateTimeUtils.eventTsToLocalDate(eventStartTs, isAllDay, zoneId)
    // Selected date is ALWAYS local time (from calendar picker)
    val selectedDate = DateTimeUtils.eventTsToLocalDate(selectedDateMillis, isAllDay = false, zoneId)
    return ChronoUnit.DAYS.between(startDate, selectedDate).toInt() + 1
}

/**
 * Format search result date display string.
 *
 * Returns:
 * - Multi-day: "Dec 20, 2023 → Dec 25, 2023 🔁" (with recur indicator if applicable)
 * - Single-day all-day: "Dec 20, 2023"
 * - Single-day timed: "Dec 20, 2023 · 9:00 AM"
 *
 * For all-day events, DTEND is exclusive per RFC 5545 (3-day event Dec 20-22 has endTs = Dec 23).
 *
 * @param event The event to format
 * @param zoneId Timezone for date calculations (injectable for testing)
 * @return Formatted date string for search/agenda display
 */
internal fun formatSearchResultDate(
    event: Event,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    // Convert timestamps to LocalDate with correct timezone handling
    val startDate = DateTimeUtils.eventTsToLocalDate(event.startTs, event.isAllDay, zoneId)
    val endDate = DateTimeUtils.eventTsToLocalDate(event.endTs, event.isAllDay, zoneId)
    // endTs is already inclusive (parsers subtract 1 sec from exclusive DTEND)
    // No need to subtract another day here
    val displayEndDate = endDate
    val isMultiDay = displayEndDate.isAfter(startDate)
    // Exception events have originalEventId but no rrule
    val isRecurring = event.isRecurring || event.isException

    val startDateStr = startDate.format(dateFormatter)

    return buildString {
        append(startDateStr)
        if (isMultiDay) {
            val endDateStr = displayEndDate.format(dateFormatter)
            append(" \u2192 $endDateStr")
        } else if (!event.isAllDay) {
            val startTime = Instant.ofEpochMilli(event.startTs).atZone(zoneId).format(timeFormatter)
            append(" \u00B7 $startTime")
        }
        if (isRecurring) append(" \uD83D\uDD01")
    }
}

/**
 * Format search result date with next occurrence for recurring events.
 *
 * For recurring events with nextOccurrenceTs: shows "Next: Jan 15, 2025 🔁"
 * For non-recurring or no nextOccurrenceTs: delegates to formatSearchResultDate()
 *
 * @param event The event to format
 * @param nextOccurrenceTs Next occurrence timestamp for recurring events (null for non-recurring)
 * @param zoneId Timezone for date calculations (injectable for testing)
 * @return Formatted date string for search display
 */
internal fun formatSearchResultDateWithOccurrence(
    event: Event,
    nextOccurrenceTs: Long?,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val isRecurring = event.isRecurring || event.isException

    // For non-recurring events or missing nextOccurrenceTs, use existing format
    if (!isRecurring || nextOccurrenceTs == null) {
        return formatSearchResultDate(event, zoneId)
    }

    // For recurring events with nextOccurrenceTs, show "Next: date" format
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    val nextDate = Instant.ofEpochMilli(nextOccurrenceTs)
        .atZone(zoneId)
        .toLocalDate()
    val nextDateStr = nextDate.format(dateFormatter)

    return buildString {
        append("Next: ")
        append(nextDateStr)
        if (!event.isAllDay) {
            val nextTime = Instant.ofEpochMilli(nextOccurrenceTs).atZone(zoneId).format(timeFormatter)
            append(" \u00B7 $nextTime")
        }
        append(" \uD83D\uDD01")  // Recurring indicator
    }
}

// ==================== Search Date Picker Components ====================

/**
 * Modal bottom sheet for selecting a custom date or date range.
 * Uses InlineDatePickerContent for consistent calendar picker UX.
 *
 * Selection behavior:
 * - First tap: Highlights date (stored in selectedDateMs)
 * - Second tap same date: Creates SingleDay filter
 * - Second tap different date: Creates CustomRange filter
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchDatePickerSheet(
    selectedDateMs: Long?,
    onDateSelected: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Stable selected date - prevents recomposition during animation
    // System.currentTimeMillis() returns different value each frame, causing jank
    val stableSelectedDate = remember(selectedDateMs) {
        selectedDateMs ?: System.currentTimeMillis()
    }

    // Track displayed month - opens to selected date's month (or current month)
    var displayedMonth by remember {
        mutableStateOf(
            JavaCalendar.getInstance().apply {
                timeInMillis = selectedDateMs ?: System.currentTimeMillis()
            }
        )
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
            // Header with instructions
            Text(
                text = if (selectedDateMs == null) {
                    "Select a date"
                } else {
                    "Tap same date for single day, or another date for range"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Calendar picker
            InlineDatePickerContent(
                selectedDateMillis = stableSelectedDate,
                displayedMonth = displayedMonth,
                onDateSelect = { dateMs ->
                    onDateSelected(dateMs)
                },
                onMonthChange = { newMonth ->
                    displayedMonth = newMonth
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Cancel button
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Cancel")
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
