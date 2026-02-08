package org.onekash.kashcal.ui.screens

import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.text.format.DateFormat
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.ui.util.MonthPagerUtils
import androidx.compose.foundation.pager.PagerState
import org.onekash.kashcal.data.contacts.ContactBirthdayRepository
import org.onekash.kashcal.data.contacts.ContactBirthdayUtils
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.data.db.dao.EventWithNextOccurrence
import org.onekash.kashcal.data.db.entity.Calendar
import android.content.Intent
import android.net.Uri
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.reader.EventReader.OccurrenceWithEvent
import org.onekash.kashcal.ui.components.SyncBanner
import org.onekash.kashcal.ui.components.YearOverlay
import org.onekash.kashcal.ui.components.pickers.InlineDatePickerContent
import org.onekash.kashcal.ui.components.weekview.WeekViewContent
import org.onekash.kashcal.ui.viewmodels.ViewMode
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
    onCreateEventWithDateTime: (Long) -> Unit = {},  // timestamp for pre-filled event form
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
    // View picker callbacks
    onViewPickerClick: () -> Unit = {},
    onViewPickerDismiss: () -> Unit = {},
    onViewSelect: (ViewMode) -> Unit = {},
    onSetDefaultView: (ViewMode) -> Unit = {},
    // Year overlay callbacks
    onMonthHeaderClick: () -> Unit = {},
    onYearOverlayDismiss: () -> Unit = {},
    onMonthSelected: (Int, Int) -> Unit = { _, _ -> },
    // Week view callbacks (infinite day pager)
    onPreviousPage: () -> Unit = {},
    onNextPage: () -> Unit = {},
    onDayPagerPageChanged: (Int) -> Unit = {},
    onWeekDatePickerRequest: () -> Unit = {},
    onWeekDatePickerDismiss: () -> Unit = {},
    onWeekDateSelected: (Long) -> Unit = {},
    onWeekScrollPositionChange: (Int) -> Unit = {},
    onClearPendingWeekPagerPosition: () -> Unit = {},
    // Agenda scroll callback
    onClearScrollAgendaToTop: () -> Unit = {},
    // Snackbar callback
    onClearSnackbar: () -> Unit = {},
    // URL callback (for error actions that open URLs)
    onClearPendingUrl: () -> Unit = {},
    // Day pager cache callbacks
    onLoadEventsForDayPagerRange: (Long) -> Unit = {},
    shouldRefreshDayPagerCache: (Long) -> Boolean = { true }
) {
    // HorizontalPager for smooth month swiping (~100 years each direction)
    val initialPage = MonthPagerUtils.INITIAL_PAGE
    val pagerState = rememberPagerState(initialPage = initialPage) { MonthPagerUtils.TOTAL_PAGES }
    val coroutineScope = rememberCoroutineScope()

    // Time format pattern based on user preference and device setting
    val context = LocalContext.current
    val is24HourDevice = DateFormat.is24HourFormat(context)
    val timePattern = remember(uiState.timeFormat, is24HourDevice) {
        DateTimeUtils.getTimePattern(uiState.timeFormat, is24HourDevice)
    }

    // Handle system back button - close overlays before returning to default view
    // Priority: Search (if active) > Non-default view > Default (close app)
    BackHandler(enabled = uiState.isSearchActive || uiState.viewMode != uiState.defaultViewMode) {
        when {
            uiState.isSearchActive -> onSearchClose()
            uiState.viewMode != uiState.defaultViewMode -> onViewSelect(uiState.defaultViewMode)
        }
    }

    // Refresh key for today-dependent values - increments on app resume
    // This triggers recomposition of today highlights, day numbers, and agenda filters
    var refreshKey by remember { mutableIntStateOf(0) }

    LifecycleResumeEffect(Unit) {
        refreshKey++  // Triggers recomposition of today-dependent values
        onPauseOrDispose { }
    }

    // Today's date for reference - refreshes on resume via refreshKey
    val todayCal = remember(refreshKey) { JavaCalendar.getInstance() }
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

    // Handle pending URL to open (from error actions)
    LaunchedEffect(uiState.pendingUrlToOpen) {
        uiState.pendingUrlToOpen?.let { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
            onClearPendingUrl()
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
                refreshKey = refreshKey,
                onSearchClick = onSearchClick,
                onSearchClose = onSearchClose,
                onSearchQueryChange = onSearchQueryChange,
                onViewPickerClick = onViewPickerClick,
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
        },
        // No bottom bar - week view is now in agenda panel
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
                                showEventEmojis = uiState.showEventEmojis,
                                timePattern = timePattern,
                                onResultClick = onSearchResultClick,
                                onFilterSelect = onSearchDateFilterChange,
                                onCustomDateClick = onSearchShowDatePicker
                            )
                        }
                        uiState.viewMode == ViewMode.AGENDA || uiState.viewMode == ViewMode.THREE_DAYS -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                ViewHeaderRow(
                                    viewMode = uiState.viewMode,
                                    pagerPosition = uiState.weekViewPagerPosition,
                                    onMonthClick = onWeekDatePickerRequest,
                                    onPreviousPage = onPreviousPage,
                                    onNextPage = onNextPage
                                )

                                // Agenda list scroll state
                                val agendaListState = rememberLazyListState()

                                // Handle scroll to top when Today button is pressed in agenda view
                                LaunchedEffect(uiState.pendingScrollAgendaToTop) {
                                    if (uiState.pendingScrollAgendaToTop) {
                                        agendaListState.animateScrollToItem(0)
                                        onClearScrollAgendaToTop()
                                    }
                                }

                                when (uiState.viewMode) {
                                    ViewMode.AGENDA -> {
                                        if (uiState.isLoadingAgenda) {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator()
                                            }
                                        } else {
                                            AgendaContent(
                                                occurrences = uiState.agendaOccurrences,
                                                calendars = uiState.calendars,
                                                listState = agendaListState,
                                                showEventEmojis = uiState.showEventEmojis,
                                                timePattern = timePattern,
                                                refreshKey = refreshKey,
                                                onEventClick = { event, occurrenceTs ->
                                                    onEventClick(event, occurrenceTs)
                                                }
                                            )
                                        }
                                    }
                                    ViewMode.THREE_DAYS -> {
                                        WeekViewContent(
                                            timedOccurrences = uiState.weekViewOccurrences,
                                            timedEvents = uiState.weekViewEvents,
                                            allDayOccurrences = uiState.weekViewAllDayOccurrences,
                                            allDayEvents = uiState.weekViewAllDayEvents,
                                            calendars = uiState.calendars,
                                            isLoading = uiState.isLoadingWeekView,
                                            error = uiState.weekViewError,
                                            scrollPosition = uiState.weekViewScrollPosition,
                                            showEventEmojis = uiState.showEventEmojis,
                                            timePattern = timePattern,
                                            onDatePickerRequest = onWeekDatePickerRequest,
                                            onEventClick = { event, occurrence ->
                                                onEventClick(event, occurrence.startTs)
                                            },
                                            onLongPress = { date, hour, minute ->
                                                // Convert date/time to timestamp for event creation
                                                val calendar = JavaCalendar.getInstance().apply {
                                                    set(date.year, date.monthValue - 1, date.dayOfMonth, hour, minute, 0)
                                                    set(JavaCalendar.MILLISECOND, 0)
                                                }
                                                onCreateEventWithDateTime(calendar.timeInMillis)
                                            },
                                            onScrollPositionChange = onWeekScrollPositionChange,
                                            onPageChanged = onDayPagerPageChanged,
                                            pendingNavigateToPage = uiState.pendingWeekViewPagerPosition,
                                            onNavigationConsumed = onClearPendingWeekPagerPosition,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    else -> {} // MONTH handled below
                                }
                            }
                        }
                        else -> {
                            // Month view with pager
                            // CRITICAL: userScrollEnabled must be true for month view
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                userScrollEnabled = true  // Always enabled in month view
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
                                    DayOfWeekHeaders(firstDayOfWeek = uiState.firstDayOfWeek)
                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Calendar grid
                                    CalendarGrid(
                                        year = pageYear,
                                        month = pageMonth,
                                        selectedDate = uiState.selectedDate,
                                        eventDots = uiState.eventDots,
                                        onDateSelected = onDateSelected,
                                        firstDayOfWeekPref = uiState.firstDayOfWeek,
                                        refreshKey = refreshKey
                                    )
                                }
                            }

                            // Day events pager (swipeable) below calendar
                            HorizontalDivider()
                            DayEventsPager(
                                uiState = uiState,
                                calendars = uiState.calendars,
                                monthPagerState = pagerState,
                                monthPagerInitialPage = initialPage,
                                todayYear = todayYear,
                                todayMonth = todayMonth,
                                timePattern = timePattern,
                                onEventClick = onEventClick,
                                onDateSelected = onDateSelected,
                                onLoadEventsForRange = onLoadEventsForDayPagerRange,
                                shouldRefreshCache = shouldRefreshDayPagerCache
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
            onDismiss = onSearchHideDatePicker,
            firstDayOfWeek = uiState.firstDayOfWeek
        )
    }

    // Week view date picker bottom sheet
    if (uiState.showWeekViewDatePicker) {
        WeekViewDatePickerSheet(
            currentWeekStartMs = uiState.weekViewStartDate,
            onDateSelected = onWeekDateSelected,
            onDismiss = onWeekDatePickerDismiss,
            firstDayOfWeek = uiState.firstDayOfWeek
        )
    }

    // View picker bottom sheet
    if (uiState.showViewPicker) {
        val viewPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        org.onekash.kashcal.ui.components.ViewPickerSheet(
            sheetState = viewPickerSheetState,
            currentView = uiState.viewMode,
            defaultView = uiState.defaultViewMode,
            onViewSelect = onViewSelect,
            onSetDefault = onSetDefaultView,
            onDismiss = onViewPickerDismiss
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopAppBar(
    uiState: HomeUiState,
    searchFocusRequester: FocusRequester,
    refreshKey: Int,
    onSearchClick: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onViewPickerClick: () -> Unit,
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
                        IconButton(onClick = onViewPickerClick) {
                            Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "Calendar view", modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = onSearchClick) {
                            Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(28.dp))
                        }
                    }
                },
                actions = {
                    TodayButton(onClick = onGoToToday, refreshKey = refreshKey)
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Tune, contentDescription = "Settings", modifier = Modifier.size(28.dp))
                    }
                }
            )
        }
    }
}

@Composable
private fun TodayButton(onClick: () -> Unit, refreshKey: Int) {
    val today = remember(refreshKey) { JavaCalendar.getInstance() }
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
private fun DayOfWeekHeaders(firstDayOfWeek: Int) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val daysOfWeek = remember(firstDayOfWeek) {
            DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek)
        }
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
    onDateSelected: (Long) -> Unit,
    firstDayOfWeekPref: Int = java.util.Calendar.SUNDAY,
    refreshKey: Int = 0
) {
    val monthKey = remember(year, month) { String.format("%04d-%02d", year, month + 1) }
    val monthDots = remember(eventDots, monthKey) { eventDots[monthKey] ?: emptyMap() }

    val calendar = JavaCalendar.getInstance().apply { set(year, month, 1) }
    val daysInMonth = calendar.getActualMaximum(JavaCalendar.DAY_OF_MONTH)
    val gridOffset = DateTimeUtils.getFirstDayOffset(calendar, firstDayOfWeekPref)

    // Get ordered days of week for weekend detection by column position
    val orderedDays = remember(firstDayOfWeekPref) {
        DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeekPref)
    }

    val today = remember(refreshKey) { JavaCalendar.getInstance() }
    val selectedCal = JavaCalendar.getInstance().apply { timeInMillis = selectedDate }
    val selectedInThisMonth = selectedCal.get(JavaCalendar.MONTH) == month &&
                               selectedCal.get(JavaCalendar.YEAR) == year

    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        var dayCounter = 1
        for (week in 0..5) {
            if (dayCounter > daysInMonth) break

            Row(modifier = Modifier.fillMaxWidth()) {
                for (dayOfWeekIdx in 0..6) {
                    if (week == 0 && dayOfWeekIdx < gridOffset || dayCounter > daysInMonth) {
                        Box(modifier = Modifier.weight(1f).height(44.dp))
                    } else {
                        val day = dayCounter
                        val dateCal = JavaCalendar.getInstance().apply { set(year, month, day) }
                        val isToday = dateCal.get(JavaCalendar.DAY_OF_YEAR) == today.get(JavaCalendar.DAY_OF_YEAR) &&
                                      dateCal.get(JavaCalendar.YEAR) == today.get(JavaCalendar.YEAR)
                        val isSelected = selectedInThisMonth && day == selectedCal.get(JavaCalendar.DAY_OF_MONTH)
                        // Check actual day of week for weekend highlighting, not column position
                        val columnDay = orderedDays[dayOfWeekIdx]
                        val isWeekend = columnDay == java.time.DayOfWeek.SATURDAY || columnDay == java.time.DayOfWeek.SUNDAY

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

/**
 * Day events pager with horizontal swipe navigation.
 * Swiping left/right navigates to next/previous day.
 * Syncs with calendar grid and month pager when crossing boundaries.
 */
@Composable
private fun ColumnScope.DayEventsPager(
    uiState: HomeUiState,
    calendars: ImmutableList<Calendar>,
    monthPagerState: PagerState,
    monthPagerInitialPage: Int,
    todayYear: Int,
    todayMonth: Int,
    timePattern: String = "h:mm a",
    onEventClick: (Event, Long?) -> Unit,
    onDateSelected: (Long) -> Unit,
    onLoadEventsForRange: (Long) -> Unit,
    shouldRefreshCache: (Long) -> Boolean
) {
    // Calculate stable today reference
    val todayMs = remember { DayPagerUtils.getTodayMidnightMs() }

    // Day pager state
    val dayPagerState = rememberPagerState(
        initialPage = DayPagerUtils.INITIAL_PAGE
    ) { DayPagerUtils.TOTAL_PAGES }

    val coroutineScope = rememberCoroutineScope()

    // SYNC 1: Day pager settled → Update selectedDate + navigate month if boundary crossed
    LaunchedEffect(dayPagerState.settledPage) {
        val newDateMs = DayPagerUtils.pageToDateMs(dayPagerState.settledPage, todayMs)

        // Update selectedDate if changed
        if (newDateMs != uiState.selectedDate) {
            onDateSelected(newDateMs)
        }

        // Refresh cache if needed
        if (shouldRefreshCache(newDateMs)) {
            onLoadEventsForRange(newDateMs)
        }

        // Navigate month pager if crossed boundary
        val newCal = JavaCalendar.getInstance().apply { timeInMillis = newDateMs }
        val newYear = newCal.get(JavaCalendar.YEAR)
        val newMonth = newCal.get(JavaCalendar.MONTH)

        if (newYear != uiState.viewingYear || newMonth != uiState.viewingMonth) {
            val monthsDiff = (newYear - todayYear) * 12 + (newMonth - todayMonth)
            monthPagerState.animateScrollToPage(monthPagerInitialPage + monthsDiff)
        }
    }

    // SYNC 2: Calendar tap → Scroll day pager
    LaunchedEffect(uiState.selectedDate) {
        if (uiState.selectedDate > 0) {
            val targetPage = DayPagerUtils.dateToPage(uiState.selectedDate, todayMs)
            if (targetPage != dayPagerState.currentPage && !dayPagerState.isScrollInProgress) {
                dayPagerState.animateScrollToPage(targetPage)
            }
        }
    }

    // Initial load
    LaunchedEffect(Unit) {
        if (uiState.cacheRangeCenter == 0L && uiState.selectedDate > 0) {
            onLoadEventsForRange(uiState.selectedDate)
        }
    }

    // Check if selected date is in viewing month (for empty state)
    val selectedCal = remember(uiState.selectedDate) {
        JavaCalendar.getInstance().apply { timeInMillis = uiState.selectedDate }
    }
    val isSelectedInViewingMonth = selectedCal.get(JavaCalendar.MONTH) == uiState.viewingMonth &&
        selectedCal.get(JavaCalendar.YEAR) == uiState.viewingYear

    if (!isSelectedInViewingMonth) {
        // Show message when user swipes month pager without selecting a day
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Pick a day. Seize it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    } else {
        HorizontalPager(
            state = dayPagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            beyondViewportPageCount = 1,
            key = { page -> page }
        ) { page ->
            val pageDateMs = DayPagerUtils.pageToDateMs(page, todayMs)
            val dayCode = DayPagerUtils.msToDayCode(pageDateMs)
            val events = uiState.dayEventsCache[dayCode] ?: persistentListOf()
            val isLoaded = uiState.loadedDayCodes.contains(dayCode)

            DayEventsPage(
                dateMs = pageDateMs,
                events = events,
                calendars = calendars,
                showEventEmojis = uiState.showEventEmojis,
                timePattern = timePattern,
                isLoading = !isLoaded && uiState.cacheRangeCenter != 0L,
                onEventClick = onEventClick
            )
        }
    }
}

/**
 * Content for a single day page in the day pager.
 */
@Composable
private fun DayEventsPage(
    dateMs: Long,
    events: ImmutableList<OccurrenceWithEvent>,
    calendars: ImmutableList<Calendar>,
    showEventEmojis: Boolean,
    timePattern: String = "h:mm a",
    isLoading: Boolean,
    onEventClick: (Event, Long?) -> Unit
) {
    val colorMap = remember(calendars) {
        calendars.associate { it.id to Color(it.color) }
    }
    val defaultColor = Color(0xFF6200EE)

    when {
        isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        events.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Nothing to see here; go touch grass?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                events.forEach { occWithEvent ->
                    val event = occWithEvent.event
                    val occurrence = occWithEvent.occurrence
                    val eventColor = colorMap[event.calendarId] ?: defaultColor
                    val isPast = DateTimeUtils.isEventPast(occurrence.endTs, occurrence.endDay, event.isAllDay)

                    EventCard(
                        event = event,
                        eventColor = eventColor,
                        isPast = isPast,
                        selectedDate = dateMs,
                        occurrenceTs = occurrence.startTs,
                        showEventEmojis = showEventEmojis,
                        timePattern = timePattern,
                        onClick = { onEventClick(event, occurrence.startTs) }
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
    occurrenceTs: Long?,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    onClick: () -> Unit
) {
    val displayTitle = remember(event, occurrenceTs, showEventEmojis) {
        formatEventTitle(event, occurrenceTs, showEventEmojis)
    }

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
                    displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val timeText = formatEventTimeDisplay(event, selectedDate, timePattern = timePattern)
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
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // For recurring events with nextOccurrenceTs, show "Next: date" format
    // For non-recurring events, keep original format
    val dateString = remember(event, nextOccurrenceTs, timePattern) {
        formatSearchResultDateWithOccurrence(event, nextOccurrenceTs, timePattern = timePattern)
    }

    // Format title with age for birthday events and optional emoji
    val displayTitle = remember(event, nextOccurrenceTs, showEventEmojis) {
        formatEventTitle(event, nextOccurrenceTs, showEventEmojis)
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
                    displayTitle,
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
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
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
                    // For non-recurring events, use event.endTs with timezone-aware isPast check
                    val endDay = DateTimeUtils.eventTsToDayCode(event.endTs, event.isAllDay)
                    val isPast = !event.isRecurring && !event.isException &&
                        DateTimeUtils.isEventPast(event.endTs, endDay, event.isAllDay)
                    SearchResultCard(
                        event = event,
                        nextOccurrenceTs = result.nextOccurrenceTs,
                        eventColor = eventColor,
                        isPast = isPast,
                        showEventEmojis = showEventEmojis,
                        timePattern = timePattern,
                        onClick = { onResultClick(event, result.nextOccurrenceTs) }
                    )
                }
            }
        }
    }
}

/**
 * Header row for non-month views - shows context-appropriate title.
 * For 3-day view: Month/year with navigation arrows (matches MonthNavHeader style)
 * For agenda view: Centered "Upcoming Events"
 *
 * Today button is in the top bar (always visible), so not duplicated here.
 */
@Composable
private fun ViewHeaderRow(
    viewMode: ViewMode,
    pagerPosition: Int,
    onMonthClick: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit
) {
    val displayText = remember(viewMode, pagerPosition) {
        when (viewMode) {
            ViewMode.AGENDA -> "Upcoming Events"
            ViewMode.THREE_DAYS -> org.onekash.kashcal.ui.components.weekview.WeekViewUtils.formatMonthYear(pagerPosition)
            ViewMode.MONTH -> ""
        }
    }

    when (viewMode) {
        ViewMode.THREE_DAYS -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousPage) {
                    Icon(Icons.Default.ChevronLeft, "Previous 3 days")
                }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onMonthClick)
                )
                IconButton(onClick = onNextPage) {
                    Icon(Icons.Default.ChevronRight, "Next 3 days")
                }
            }
        }
        ViewMode.AGENDA -> {
            Text(
                text = displayText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )
        }
        ViewMode.MONTH -> {} // Uses MonthNavHeader directly
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
    listState: LazyListState = rememberLazyListState(),
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    refreshKey: Int = 0,
    onEventClick: (Event, Long) -> Unit  // (event, occurrenceStartTs)
) {
    val colorMap = remember(calendars) { calendars.associate { it.id to Color(it.color) } }
    val defaultColor = Color(0xFF6200EE)
    val dateFormat = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()) }

    // Calculate today's day code for filtering - refreshes on resume via refreshKey
    val todayDayCode = remember(refreshKey) {
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
        }.distinctBy { item ->
            // Deduplicate by key to prevent LazyColumn crash (duplicate occurrences in DB)
            "${item.occWithEvent.event.id}-${item.occWithEvent.occurrence.startTs}-${item.displayDay}"
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
            state = listState,
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
                        showEventEmojis = showEventEmojis,
                        timePattern = timePattern,
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
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    onClick: () -> Unit
) {
    val event = item.occWithEvent.event
    val occurrence = item.occWithEvent.occurrence
    val dateString = formatAgendaCardDate(event, occurrence, item.dayNumber, item.totalDays, timePattern)

    // Format title with age for birthday events and optional emoji
    val displayTitle = remember(event, occurrence.startTs, showEventEmojis) {
        formatEventTitle(event, occurrence.startTs, showEventEmojis)
    }

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
                    displayTitle,
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
 * Format event title, adding age for birthday events and optional emoji.
 *
 * For contact birthday events (caldavUrl starts with "contact_birthday:"):
 * - Decodes birth year from event description
 * - Calculates age at the occurrence date
 * - Returns "Name's Xth Birthday" format
 *
 * For all other events, returns the original title with optional emoji prefix.
 *
 * @param event The event to format title for
 * @param occurrenceTs The occurrence timestamp for age calculation
 * @param showEmojis Whether to prefix auto-detected emoji to the title
 * @return Formatted title string
 */
private fun formatEventTitle(event: Event, occurrenceTs: Long?, showEmojis: Boolean = true): String {
    // Check if this is a contact birthday event
    val isBirthdayEvent = event.caldavUrl?.startsWith("${ContactBirthdayRepository.SOURCE_PREFIX}:") == true

    val baseTitle = if (!isBirthdayEvent || occurrenceTs == null) {
        event.title
    } else {
        // Decode birth year from description
        val birthYear = ContactBirthdayUtils.decodeBirthYear(event.description)

        // Display name is the raw title (e.g., "John Smith")
        val displayName = event.title

        if (birthYear == null) {
            // No birth year - show "Name's Birthday" without age
            "$displayName's Birthday"
        } else {
            // Calculate age and format title
            val age = ContactBirthdayUtils.calculateAge(birthYear, occurrenceTs)

            if (age > 0 && age < 150) {
                "$displayName's ${ContactBirthdayUtils.formatOrdinal(age)} Birthday"
            } else {
                // Invalid age - show "Name's Birthday" without age
                "$displayName's Birthday"
            }
        }
    }

    // Apply emoji formatting if enabled
    return EmojiMatcher.formatWithEmoji(baseTitle, showEmojis)
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
    totalDays: Int,
    timePattern: String = "h:mm a"
): String {
    val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())
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
    zoneId: ZoneId = ZoneId.systemDefault(),
    timePattern: String = "h:mm a"
): String {
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())

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
 * @param timePattern Time format pattern (default "h:mm a" for 12-hour)
 * @return Formatted date string for search/agenda display
 */
internal fun formatSearchResultDate(
    event: Event,
    zoneId: ZoneId = ZoneId.systemDefault(),
    timePattern: String = "h:mm a"
): String {
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())

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
 * @param timePattern Time format pattern (default "h:mm a" for 12-hour)
 * @return Formatted date string for search display
 */
internal fun formatSearchResultDateWithOccurrence(
    event: Event,
    nextOccurrenceTs: Long?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    timePattern: String = "h:mm a"
): String {
    val isRecurring = event.isRecurring || event.isException

    // For non-recurring events or missing nextOccurrenceTs, use existing format
    if (!isRecurring || nextOccurrenceTs == null) {
        return formatSearchResultDate(event, zoneId, timePattern)
    }

    // For recurring events with nextOccurrenceTs, show "Next: date" format
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())

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
    onDismiss: () -> Unit,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
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
                },
                firstDayOfWeek = firstDayOfWeek
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

// ==================== Week View Date Picker ====================

/**
 * Modal bottom sheet for selecting a date to navigate to in the 3-day view.
 * Single tap on any date navigates to the week containing that date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekViewDatePickerSheet(
    currentWeekStartMs: Long,
    onDateSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Stable current date - prevents recomposition jank
    val stableCurrentDate = remember(currentWeekStartMs) {
        if (currentWeekStartMs > 0L) currentWeekStartMs else System.currentTimeMillis()
    }

    // Track displayed month - opens to current week's month
    var displayedMonth by remember {
        mutableStateOf(
            JavaCalendar.getInstance().apply {
                timeInMillis = stableCurrentDate
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
            // Header
            Text(
                text = "Go to date",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Calendar picker
            InlineDatePickerContent(
                selectedDateMillis = stableCurrentDate,
                displayedMonth = displayedMonth,
                onDateSelect = { dateMs ->
                    onDateSelected(dateMs)
                    onDismiss()
                },
                onMonthChange = { newMonth ->
                    displayedMonth = newMonth
                },
                firstDayOfWeek = firstDayOfWeek
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
