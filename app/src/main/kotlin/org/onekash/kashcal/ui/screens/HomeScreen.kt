package org.onekash.kashcal.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import android.text.format.DateFormat
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.onekash.kashcal.ui.model.MonthGrid
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.ui.util.MonthPagerUtils
import androidx.compose.foundation.pager.PagerState
import org.onekash.kashcal.domain.EmojiMatcher
import android.content.Intent
import android.net.Uri
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult
import org.onekash.kashcal.ui.components.CalendarDrawer
import org.onekash.kashcal.ui.components.DayEventsSheet
import org.onekash.kashcal.ui.components.EventCard
import org.onekash.kashcal.ui.components.formatDisplayEventTitle
import org.onekash.kashcal.ui.components.formatEventTitle
import org.onekash.kashcal.ui.components.calculateCurrentDayForEvent
import org.onekash.kashcal.ui.components.SyncBanner
import org.onekash.kashcal.ui.components.YearOverlay
import org.onekash.kashcal.ui.components.pickers.InlineDatePickerContent
import org.onekash.kashcal.ui.components.weekview.WeekViewContent
import org.onekash.kashcal.ui.viewmodels.EditScope
import org.onekash.kashcal.ui.viewmodels.ViewMode
import org.onekash.kashcal.ui.viewmodels.DateFilter
import org.onekash.kashcal.ui.viewmodels.HomeUiState
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import androidx.compose.ui.res.stringResource
import org.onekash.kashcal.R
import java.util.*
import java.util.Calendar as JavaCalendar

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
    onDeviceEventClick: (DisplayEvent.Device) -> Unit = {},  // device calendar event clicked
    onCreateEvent: () -> Unit = {},
    onCreateEventWithDateTime: (Long) -> Unit = {},  // timestamp for pre-filled event form
    // Sync callbacks
    onRefresh: () -> Unit = {},
    // Search callbacks
    onSearchClick: () -> Unit = {},
    onSearchClose: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onSearchResultClick: (Event, Long?) -> Unit = { _, _ -> },  // (event, nextOccurrenceTs)
    // Search date filter callbacks
    onSearchDateFilterChange: (DateFilter) -> Unit = {},
    onSearchShowDatePicker: () -> Unit = {},
    onSearchHideDatePicker: () -> Unit = {},
    onSearchDateSelected: (Long) -> Unit = {},
    // Settings callback
    onSettingsClick: () -> Unit = {},
    // Drawer
    drawerState: DrawerState? = null,
    onDrawerToggleCalendar: (Long) -> Unit = {},
    onDrawerToggleDeviceCalendarVisibility: (Long) -> Unit = {},
    // Info callbacks
    onInfoClick: () -> Unit = {},
    // View picker callback
    onViewSelect: (ViewMode) -> Unit = {},
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
    onWeekHourHeightChange: (Float) -> Unit = {},
    onClearPendingWeekPagerPosition: () -> Unit = {},
    onReschedule: (DisplayEvent, LocalDate, Int) -> Unit = { _, _, _ -> },
    onConfirmReschedule: (EditScope) -> Unit = {},
    onCancelPendingReschedule: () -> Unit = {},
    // Resume callback (reload stale data on app resume)
    onResume: () -> Unit = {},
    // Agenda scroll callback
    onClearScrollAgendaToTop: () -> Unit = {},
    // Snackbar callback
    onClearSnackbar: () -> Unit = {},
    // URL callback (for error actions that open URLs)
    onClearPendingUrl: () -> Unit = {},
    // Day detail sheet callbacks (month view)
    onShowDayDetail: (Long) -> Unit = {},
    onDismissDayDetail: () -> Unit = {},
    // Day pager cache callbacks
    onLoadEventsForDayPagerRange: (Long) -> Unit = {},
    shouldRefreshDayPagerCache: (Long) -> Boolean = { true },
    // Year view callbacks
    onEnsureDotsForYear: (Int) -> Unit = {},
) {
    // HorizontalPager for smooth month swiping (~100 years each direction)
    val initialPage = MonthPagerUtils.INITIAL_PAGE
    val pagerState = rememberPagerState(initialPage = initialPage) { MonthPagerUtils.TOTAL_PAGES }
    val coroutineScope = rememberCoroutineScope()

    // Adaptive layout based on screen dimensions
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isCompactHeight = isLandscape && configuration.screenHeightDp < 480

    // Time format pattern based on user preference and device setting
    val context = LocalContext.current
    val is24HourDevice = DateFormat.is24HourFormat(context)
    val timePattern = remember(uiState.timeFormat, is24HourDevice) {
        DateTimeUtils.getTimePattern(uiState.timeFormat, is24HourDevice)
    }

    // Handle system back button - close search overlay
    BackHandler(enabled = uiState.isSearchActive) {
        onSearchClose()
    }

    // Refresh key for today-dependent values - increments on app resume
    // This triggers recomposition of today highlights, day numbers, and agenda filters
    var refreshKey by remember { mutableIntStateOf(0) }

    LifecycleResumeEffect(Unit) {
        refreshKey++  // Triggers recomposition of today-dependent values
        onResume()    // Reload stale data (THREE_DAYS/WEEK one-shot queries)
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
    val viewActionLabel = stringResource(R.string.action_view)

    // Handle snackbar messages
    LaunchedEffect(uiState.pendingSnackbarMessage) {
        uiState.pendingSnackbarMessage?.let { message ->
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (uiState.pendingSnackbarAction != null) viewActionLabel else null,
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
            pagerState.scrollToPage(targetPage)
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

    val drawerScope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState ?: rememberDrawerState(DrawerValue.Closed),
        drawerContent = {
            CalendarDrawer(
                currentViewMode = uiState.viewMode,
                calendarGroups = uiState.calendarGroups,
                deviceCalendarsEnabled = uiState.deviceCalendarsEnabled,
                enabledDeviceCalendars = uiState.enabledDeviceCalendars,
                hiddenDeviceCalendarIds = uiState.hiddenDeviceCalendarIds,
                onViewSelect = { mode ->
                    drawerScope.launch { drawerState?.close() }
                    onViewSelect(mode)
                },
                onToggleCalendar = onDrawerToggleCalendar,
                onToggleDeviceCalendarVisibility = onDrawerToggleDeviceCalendarVisibility
            )
        }
    ) {
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
                isOnline = isOnline,
                searchFocusRequester = searchFocusRequester,
                refreshKey = refreshKey,
                drawerState = drawerState,
                onSearchClick = onSearchClick,
                onSearchClose = onSearchClose,
                onSearchQueryChange = onSearchQueryChange,
                onMenuClick = {
                    drawerScope.launch {
                        if (drawerState?.isOpen == true) drawerState.close() else drawerState?.open()
                    }
                },
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
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_create_event))
            }
        },
        // No bottom bar - week view is now in agenda panel
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Sync progress banner
            AnimatedVisibility(visible = uiState.showSyncBanner) {
                SyncBanner(
                    state = uiState.syncBannerState,
                    errorDetail = uiState.syncErrorDetail
                )
            }

            val pullToRefreshState = rememberPullToRefreshState()
            val canPullToRefresh = uiState.isConfigured && isOnline
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pullToRefresh(
                        isRefreshing = uiState.isSyncing,
                        state = pullToRefreshState,
                        enabled = canPullToRefresh,
                        onRefresh = onRefresh
                    )
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
                                currentFilter = uiState.searchDateFilter,
                                showEventEmojis = uiState.showEventEmojis,
                                timePattern = timePattern,
                                onResultClick = onSearchResultClick,
                                onDeviceEventClick = onDeviceEventClick,
                                onFilterSelect = onSearchDateFilterChange,
                                onCustomDateClick = onSearchShowDatePicker
                            )
                        }
                        uiState.viewMode == ViewMode.YEAR -> {
                            YearViewContent(
                                eventDots = uiState.eventDots,
                                firstDayOfWeek = uiState.firstDayOfWeek,
                                pendingNavigateToToday = uiState.pendingNavigateToToday,
                                onNavigateToTodayConsumed = onClearNavigateToToday,
                                onMonthClick = { year, month ->
                                    // Update selectedDate BEFORE switching view so that
                                    // syncPagerToSelectedDate() navigates to the correct month
                                    val cal = JavaCalendar.getInstance().apply { set(year, month, 1) }
                                    onDateSelected(cal.timeInMillis)
                                    onViewSelect(ViewMode.MONTH)
                                },
                                onYearChanged = onEnsureDotsForYear,
                                onBackToMonth = { onViewSelect(ViewMode.MONTH) }
                            )
                        }
                        uiState.viewMode == ViewMode.AGENDA || uiState.viewMode == ViewMode.THREE_DAYS || uiState.viewMode == ViewMode.WEEK -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                ViewHeaderRow(
                                    viewMode = uiState.viewMode,
                                    pagerPosition = uiState.weekViewPagerPosition,
                                    firstDayOfWeek = uiState.firstDayOfWeek,
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
                                                events = uiState.agendaEvents,
                                                listState = agendaListState,
                                                showEventEmojis = uiState.showEventEmojis,
                                                timePattern = timePattern,
                                                refreshKey = refreshKey,
                                                onEventClick = { displayEvent ->
                                                    when (displayEvent) {
                                                        is DisplayEvent.Room -> onEventClick(displayEvent.event, displayEvent.occurrence.startTs)
                                                        is DisplayEvent.Device -> onDeviceEventClick(displayEvent)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    ViewMode.THREE_DAYS, ViewMode.WEEK -> {
                                        WeekViewContent(
                                            timedEvents = uiState.weekViewTimedEvents,
                                            allDayEvents = uiState.weekViewAllDayEvents,
                                            isLoading = uiState.isLoadingWeekView,
                                            error = uiState.weekViewError,
                                            scrollPosition = uiState.weekViewScrollPosition,
                                            hourHeight = uiState.weekViewHourHeight,
                                            onHourHeightChange = onWeekHourHeightChange,
                                            showEventEmojis = uiState.showEventEmojis,
                                            timePattern = timePattern,
                                            weekMode = uiState.viewMode == ViewMode.WEEK,
                                            firstDayOfWeek = uiState.firstDayOfWeek,
                                            onDatePickerRequest = onWeekDatePickerRequest,
                                            onEventClick = { displayEvent ->
                                                when (displayEvent) {
                                                    is DisplayEvent.Room -> onEventClick(displayEvent.event, displayEvent.occurrence.startTs)
                                                    is DisplayEvent.Device -> onDeviceEventClick(displayEvent)
                                                }
                                            },
                                            onEmptyTap = { date, hour, minute ->
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
                                            onReschedule = onReschedule,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    else -> {} // MONTH and MONTH_FULL handled below
                                }
                            }
                        }
                        uiState.viewMode == ViewMode.MONTH_FULL -> {
                            // Full-height month view with event snippets in day cells
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.Top,
                                userScrollEnabled = true
                            ) { page ->
                                val monthOffset = page - initialPage
                                val pageCal = JavaCalendar.getInstance().apply {
                                    set(todayYear, todayMonth, 1)
                                    add(JavaCalendar.MONTH, monthOffset)
                                }
                                val pageYear = pageCal.get(JavaCalendar.YEAR)
                                val pageMonth = pageCal.get(JavaCalendar.MONTH)

                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Top
                                ) {
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

                                    DayOfWeekHeaders(
                                        firstDayOfWeek = uiState.firstDayOfWeek,
                                        showWeekNumbers = uiState.showWeekNumbers
                                    )

                                    FullHeightMonthGrid(
                                        year = pageYear,
                                        month = pageMonth,
                                        selectedDate = uiState.selectedDate,
                                        monthEventsMap = uiState.monthEventsMap,
                                        onDateSelected = { dateMs ->
                                            onDateSelected(dateMs)
                                            onShowDayDetail(dateMs)
                                        },
                                        firstDayOfWeekPref = uiState.firstDayOfWeek,
                                        showWeekNumbers = uiState.showWeekNumbers,
                                        showEventEmojis = uiState.showEventEmojis,
                                        refreshKey = refreshKey,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        else -> {
                            // Prevent one-frame flicker on view transition:
                            // When switching from THREE_DAYS/WEEK to MONTH, the pager
                            // re-enters composition at its saved (stale) page. The
                            // LaunchedEffect that scrolls to the correct page fires
                            // AFTER the first draw. Hide content until the scroll lands.
                            // Only active on fresh entry (isFirstComposition resets when
                            // the else branch is disposed); in-view navigation (YearOverlay,
                            // Today) is unaffected because isFirstComposition is already false.
                            var isFirstComposition by remember { mutableStateOf(true) }
                            LaunchedEffect(uiState.pendingNavigateToMonth) {
                                if (uiState.pendingNavigateToMonth == null) {
                                    isFirstComposition = false
                                }
                            }
                            val hideForTransition = isFirstComposition && uiState.pendingNavigateToMonth != null

                            // Month pager content (shared between portrait and landscape)
                            val monthPagerContent: @Composable (pageYear: Int, pageMonth: Int) -> Unit = { pageYear, pageMonth ->
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.Top
                                ) {
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
                                    DayOfWeekHeaders(
                                        firstDayOfWeek = uiState.firstDayOfWeek,
                                        showWeekNumbers = uiState.showWeekNumbers
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    CalendarGrid(
                                        year = pageYear,
                                        month = pageMonth,
                                        selectedDate = uiState.selectedDate,
                                        eventDots = uiState.eventDots,
                                        onDateSelected = onDateSelected,
                                        firstDayOfWeekPref = uiState.firstDayOfWeek,
                                        refreshKey = refreshKey,
                                        showWeekNumbers = uiState.showWeekNumbers,
                                        isCompact = isCompactHeight
                                    )
                                }
                            }

                            val transitionAlpha = if (hideForTransition) 0f else 1f

                            // Hoist day pager state above the landscape/portrait branch
                            // so it survives orientation changes without recreation.
                            val dayPagerTodayMs = remember { DayPagerUtils.getTodayMidnightMs() }
                            val dayPagerInitialPage = if (uiState.selectedDate != 0L) {
                                DayPagerUtils.dateToPage(uiState.selectedDate, dayPagerTodayMs)
                            } else {
                                DayPagerUtils.INITIAL_PAGE
                            }
                            val dayPagerState = rememberPagerState(
                                initialPage = dayPagerInitialPage
                            ) { DayPagerUtils.TOTAL_PAGES }

                            if (isLandscape) {
                                // Landscape: calendar grid left, day events right
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha(transitionAlpha)
                                ) {
                                    // Month pager (left)
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.weight(0.45f),
                                        verticalAlignment = Alignment.Top,
                                        userScrollEnabled = true
                                    ) { page ->
                                        val monthOffset = page - initialPage
                                        val pageCal = JavaCalendar.getInstance().apply {
                                            set(todayYear, todayMonth, 1)
                                            add(JavaCalendar.MONTH, monthOffset)
                                        }
                                        monthPagerContent(pageCal.get(JavaCalendar.YEAR), pageCal.get(JavaCalendar.MONTH))
                                    }
                                    VerticalDivider()
                                    // Day events (right)
                                    Column(modifier = Modifier.weight(0.55f)) {
                                        DayEventsPager(
                                            uiState = uiState,
                                            dayPagerState = dayPagerState,
                                            dayPagerTodayMs = dayPagerTodayMs,
                                            monthPagerState = pagerState,
                                            monthPagerInitialPage = initialPage,
                                            todayYear = todayYear,
                                            todayMonth = todayMonth,
                                            timePattern = timePattern,
                                            onEventClick = onEventClick,
                                            onDeviceEventClick = onDeviceEventClick,
                                            onDateSelected = onDateSelected,
                                            onLoadEventsForRange = onLoadEventsForDayPagerRange,
                                            shouldRefreshCache = shouldRefreshDayPagerCache
                                        )
                                    }
                                }
                            } else {
                                // Portrait: calendar grid top, day events below
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha(transitionAlpha)
                                ) {
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top,
                                        userScrollEnabled = true
                                    ) { page ->
                                        val monthOffset = page - initialPage
                                        val pageCal = JavaCalendar.getInstance().apply {
                                            set(todayYear, todayMonth, 1)
                                            add(JavaCalendar.MONTH, monthOffset)
                                        }
                                        monthPagerContent(pageCal.get(JavaCalendar.YEAR), pageCal.get(JavaCalendar.MONTH))
                                    }
                                    HorizontalDivider()
                                    DayEventsPager(
                                        uiState = uiState,
                                        dayPagerState = dayPagerState,
                                        dayPagerTodayMs = dayPagerTodayMs,
                                        monthPagerState = pagerState,
                                        monthPagerInitialPage = initialPage,
                                        todayYear = todayYear,
                                        todayMonth = todayMonth,
                                        timePattern = timePattern,
                                        onEventClick = onEventClick,
                                        onDeviceEventClick = onDeviceEventClick,
                                        onDateSelected = onDateSelected,
                                        onLoadEventsForRange = onLoadEventsForDayPagerRange,
                                        shouldRefreshCache = shouldRefreshDayPagerCache
                                    )
                                }
                            }
                        }
                    }
                }
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = uiState.isSyncing,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
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

    // Day events bottom sheet (month view)
    if (uiState.showDayDetailSheet) {
        val dayCode = DayPagerUtils.msToDayCode(uiState.dayDetailDate)
        val dayEvents = uiState.monthEventsMap[dayCode] ?: persistentListOf()
        DayEventsSheet(
            dateMs = uiState.dayDetailDate,
            events = dayEvents,
            showEventEmojis = uiState.showEventEmojis,
            timePattern = timePattern,
            onEventClick = onEventClick,
            onDeviceEventClick = onDeviceEventClick,
            onCreateEvent = onCreateEventWithDateTime,
            onDismiss = onDismissDayDetail
        )
    }

    // Recurring event edit scope dialog (drag-to-reschedule)
    uiState.pendingDragReschedule?.let { pending ->
        val isDeviceRecurring = pending.displayEvent is DisplayEvent.Device
        AlertDialog(
            onDismissRequest = onCancelPendingReschedule,
            title = { Text(stringResource(R.string.dialog_edit_recurring_title)) },
            text = { Text(stringResource(R.string.dialog_edit_recurring_message)) },
            confirmButton = {
                TextButton(onClick = { onConfirmReschedule(EditScope.THIS_EVENT) }) {
                    Text(stringResource(R.string.recurring_this_event))
                }
            },
            dismissButton = {
                if (isDeviceRecurring) {
                    TextButton(onClick = onCancelPendingReschedule) {
                        Text(stringResource(R.string.action_cancel))
                    }
                } else {
                    TextButton(onClick = { onConfirmReschedule(EditScope.ALL_EVENTS) }) {
                        Text(stringResource(R.string.recurring_all_events))
                    }
                }
            }
        )
    }

    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopAppBar(
    uiState: HomeUiState,
    isOnline: Boolean,
    searchFocusRequester: FocusRequester,
    refreshKey: Int,
    drawerState: DrawerState?,
    onSearchClick: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onMenuClick: () -> Unit,
    onGoToToday: () -> Unit,
    onSettingsClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    val isDrawerOpen = drawerState?.targetValue == DrawerValue.Open
    val menuRotation by animateFloatAsState(
        targetValue = if (isDrawerOpen) 180f else 0f,
        animationSpec = tween(300),
        label = "menuRotation"
    )
    when {
        uiState.isSearchActive -> {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onSearchClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), modifier = Modifier.size(24.dp))
                        }
                        BasicTextField(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier.weight(1f).focusRequester(searchFocusRequester),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Row(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
                                        .defaultMinSize(minHeight = 48.dp)
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (uiState.searchQuery.isEmpty()) {
                                            Text(
                                                stringResource(R.string.placeholder_search_events),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                    if (uiState.searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { onSearchQueryChange("") },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.cd_clear),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            )
        }
        else -> {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onInfoClick() }
                    )
                },
                navigationIcon = {
                    Row {
                        IconButton(onClick = onMenuClick) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = if (isDrawerOpen) stringResource(R.string.cd_close_drawer) else stringResource(R.string.cd_open_drawer),
                                modifier = Modifier
                                    .size(28.dp)
                                    .graphicsLayer { rotationZ = menuRotation }
                            )
                        }
                        IconButton(onClick = onSearchClick) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search), modifier = Modifier.size(28.dp))
                        }
                    }
                },
                actions = {
                    AnimatedVisibility(visible = !isOnline && uiState.isConfigured) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = stringResource(R.string.cd_offline),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    TodayButton(onClick = onGoToToday, refreshKey = refreshKey)
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.cd_settings), modifier = Modifier.size(28.dp))
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
                contentDescription = stringResource(R.string.cd_today),
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
private fun MonthNavHeader(
    year: Int,
    month: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMonthClick: () -> Unit
) {
    val calendar = JavaCalendar.getInstance().apply { set(year, month, 1) }
    val monthFormat = remember { SimpleDateFormat(DateTimeUtils.localizedPattern("yMMMM"), Locale.getDefault()) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.ChevronLeft, stringResource(R.string.cd_previous))
        }
        Text(
            text = monthFormat.format(calendar.time),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onMonthClick)
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Default.ChevronRight, stringResource(R.string.cd_next))
        }
    }
}

@Composable
private fun DayOfWeekHeaders(firstDayOfWeek: Int, showWeekNumbers: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        if (showWeekNumbers) {
            Spacer(modifier = Modifier.width(24.dp))
        }
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
    refreshKey: Int = 0,
    showWeekNumbers: Boolean = false,
    isCompact: Boolean = false
) {
    val cellHeight = if (isCompact) 32.dp else 44.dp

    val monthGrid = remember(year, month, firstDayOfWeekPref) {
        MonthGrid.compute(year, month, firstDayOfWeekPref)
    }
    val monthKey = remember(year, month) { String.format(java.util.Locale.ROOT, "%04d-%02d", year, month + 1) }
    val monthDots = remember(eventDots, monthKey) { eventDots[monthKey].orEmpty() }

    val today = remember(refreshKey) { JavaCalendar.getInstance() }
    val selectedCal = JavaCalendar.getInstance().apply { timeInMillis = selectedDate }
    val selectedInThisMonth = selectedCal.get(JavaCalendar.MONTH) == month &&
                               selectedCal.get(JavaCalendar.YEAR) == year

    Column(modifier = Modifier.padding(horizontal = 8.dp).animateContentSize()) {
        monthGrid.weeks.forEach { row ->
            if (row.none { it.position == MonthGrid.DayPosition.MonthDate }) return@forEach
            Row(modifier = Modifier.fillMaxWidth()) {
                if (showWeekNumbers) {
                    Box(
                        modifier = Modifier.width(24.dp).height(cellHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = row.first().weekNumber.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
                row.forEach { cell ->
                    when (cell.position) {
                        MonthGrid.DayPosition.InDate, MonthGrid.DayPosition.OutDate -> {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(cellHeight)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val (adjYear, adjMonth) = when (cell.position) {
                                            MonthGrid.DayPosition.InDate ->
                                                if (month == 0) (year - 1) to 11 else year to (month - 1)
                                            MonthGrid.DayPosition.OutDate ->
                                                if (month == 11) (year + 1) to 0 else year to (month + 1)
                                            else -> return@clickable
                                        }
                                        val clickedCal = JavaCalendar.getInstance().apply {
                                            set(adjYear, adjMonth, cell.dayOfMonth)
                                        }
                                        onDateSelected(clickedCal.timeInMillis)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = cell.dayOfMonth.toString(),
                                    style = if (isCompact) MaterialTheme.typography.bodySmall
                                            else LocalTextStyle.current,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
                        }
                        MonthGrid.DayPosition.MonthDate -> {
                            val day = cell.dayOfMonth
                            val isToday = day == today.get(JavaCalendar.DAY_OF_MONTH) &&
                                month == today.get(JavaCalendar.MONTH) &&
                                year == today.get(JavaCalendar.YEAR)
                            val isSelected = selectedInThisMonth && day == selectedCal.get(JavaCalendar.DAY_OF_MONTH)
                            val dayColors = monthDots[day].orEmpty()

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(cellHeight)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isSelected -> MaterialTheme.colorScheme.inverseSurface
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
                                        style = if (isCompact) MaterialTheme.typography.bodySmall
                                                else LocalTextStyle.current,
                                        color = when {
                                            isSelected -> MaterialTheme.colorScheme.inverseOnSurface
                                            isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                                            cell.isWeekend -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
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
                        }
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
    dayPagerState: PagerState,
    dayPagerTodayMs: Long,
    monthPagerState: PagerState,
    monthPagerInitialPage: Int,
    todayYear: Int,
    todayMonth: Int,
    timePattern: String = "h:mm a",
    onEventClick: (Event, Long?) -> Unit,
    onDeviceEventClick: (DisplayEvent.Device) -> Unit = {},
    onDateSelected: (Long) -> Unit,
    onLoadEventsForRange: (Long) -> Unit,
    shouldRefreshCache: (Long) -> Boolean
) {
    val todayMs = dayPagerTodayMs

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
            monthPagerState.scrollToPage(monthPagerInitialPage + monthsDiff)
        }
    }

    // SYNC 2: Calendar tap → Scroll day pager (instant to prevent race with SYNC 1)
    LaunchedEffect(uiState.selectedDate) {
        if (uiState.selectedDate != 0L) {
            val targetPage = DayPagerUtils.dateToPage(uiState.selectedDate, todayMs)
            if (targetPage != dayPagerState.currentPage) {
                dayPagerState.scrollToPage(targetPage)
            }
        }
    }

    // Initial load
    LaunchedEffect(Unit) {
        if (uiState.cacheRangeCenter == 0L && uiState.selectedDate != 0L) {
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
                stringResource(R.string.empty_pick_a_day),
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
                showEventEmojis = uiState.showEventEmojis,
                timePattern = timePattern,
                isLoading = !isLoaded && uiState.cacheRangeCenter != 0L,
                onEventClick = onEventClick,
                onDeviceEventClick = onDeviceEventClick
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
    events: ImmutableList<DisplayEvent>,
    showEventEmojis: Boolean,
    timePattern: String = "h:mm a",
    isLoading: Boolean,
    onEventClick: (Event, Long?) -> Unit,
    onDeviceEventClick: (DisplayEvent.Device) -> Unit = {}
) {
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
                    stringResource(R.string.empty_no_events_day),
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
                events.forEach { displayEvent ->
                    val eventColor = Color(displayEvent.calendarColor)
                    val isPast = DateTimeUtils.isEventPast(displayEvent.endTs, displayEvent.endDay, displayEvent.isAllDay)

                    EventCard(
                        displayEvent = displayEvent,
                        eventColor = eventColor,
                        isPast = isPast,
                        selectedDate = dateMs,
                        showEventEmojis = showEventEmojis,
                        timePattern = timePattern,
                        onClick = {
                            when (displayEvent) {
                                is DisplayEvent.Room -> onEventClick(displayEvent.event, displayEvent.occurrence.startTs)
                                is DisplayEvent.Device -> onDeviceEventClick(displayEvent)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    searchResult: SearchResult,
    isPast: Boolean,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayEvent = searchResult.displayEvent
    val eventColor = Color(displayEvent.calendarColor)

    // Format date: for Room recurring events, show "Next: date" format using displayTs
    val dateString = remember(searchResult, timePattern) {
        when (displayEvent) {
            is DisplayEvent.Room -> {
                // Determine nextOccurrenceTs: non-null if displayTs differs from event start (recurring with future occ)
                val nextOccTs = searchResult.displayTs.takeIf { it != displayEvent.event.startTs }
                formatSearchResultDateWithOccurrence(displayEvent.event, nextOccTs, timePattern = timePattern)
            }
            is DisplayEvent.Device -> {
                formatSearchResultDate(displayEvent, timePattern = timePattern)
            }
        }
    }

    // Format title with age for birthday events and optional emoji
    val resources = LocalContext.current.resources
    val displayTitle = remember(searchResult, showEventEmojis) {
        when (displayEvent) {
            is DisplayEvent.Room -> formatEventTitle(displayEvent.event, searchResult.displayTs, showEventEmojis, resources)
            is DisplayEvent.Device -> EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis)
        }
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
                if (!displayEvent.location.isNullOrEmpty()) {
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
 * Search content - displays search results with date filter chips.
 * Chips outside LazyColumn to avoid crash (no horizontalScroll needed).
 * Simplified to 4 essential chips: All, Week, Month, Date picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchContent(
    results: ImmutableList<SearchResult>,
    currentFilter: DateFilter,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    onResultClick: (Event, Long?) -> Unit,
    onDeviceEventClick: (DisplayEvent.Device) -> Unit = {},
    onFilterSelect: (DateFilter) -> Unit,
    onCustomDateClick: () -> Unit
) {
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
                label = { Text(stringResource(R.string.view_all)) }
            )
            FilterChip(
                selected = currentFilter == DateFilter.ThisWeek,
                onClick = { onFilterSelect(DateFilter.ThisWeek) },
                label = { Text(stringResource(R.string.view_week)) }
            )
            FilterChip(
                selected = currentFilter == DateFilter.ThisMonth,
                onClick = { onFilterSelect(DateFilter.ThisMonth) },
                label = { Text(stringResource(R.string.view_month)) }
            )
            // Date picker chip - shows selected date or calendar icon
            val isCustom = currentFilter is DateFilter.SingleDay || currentFilter is DateFilter.CustomRange
            val dateLabel = stringResource(R.string.filter_date)
            FilterChip(
                selected = isCustom,
                onClick = onCustomDateClick,
                label = { Text(if (isCustom) currentFilter.displayName else dateLabel) }
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
                Text(stringResource(R.string.empty_no_events_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    key = { result ->
                        when (val de = result.displayEvent) {
                            is DisplayEvent.Room -> "room_${de.event.id}"
                            is DisplayEvent.Device -> "device_${de.instance.instanceId}"
                        }
                    },
                    contentType = { "search_result" }
                ) { result ->
                    val displayEvent = result.displayEvent
                    // Determine if event is recurring (Room exceptions count as recurring)
                    val isRecurring = when (displayEvent) {
                        is DisplayEvent.Room -> displayEvent.event.isRecurring || displayEvent.event.isException
                        is DisplayEvent.Device -> displayEvent.hasRrule
                    }
                    val isPast = !isRecurring &&
                        DateTimeUtils.isEventPast(displayEvent.endTs, displayEvent.endDay, displayEvent.isAllDay)
                    SearchResultCard(
                        searchResult = result,
                        isPast = isPast,
                        showEventEmojis = showEventEmojis,
                        timePattern = timePattern,
                        modifier = Modifier.animateItem(),
                        onClick = {
                            when (displayEvent) {
                                is DisplayEvent.Room -> onResultClick(displayEvent.event, result.displayTs)
                                is DisplayEvent.Device -> onDeviceEventClick(displayEvent)
                            }
                        }
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
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    onMonthClick: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit
) {
    val upcomingLabel = stringResource(R.string.label_upcoming_events)
    val displayText = remember(viewMode, pagerPosition, firstDayOfWeek, upcomingLabel) {
        when (viewMode) {
            ViewMode.AGENDA -> upcomingLabel
            ViewMode.THREE_DAYS -> org.onekash.kashcal.ui.components.weekview.WeekViewUtils.formatThreeDayHeader(pagerPosition)
            ViewMode.WEEK -> {
                val weekStart = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.weekPageToStartDate(pagerPosition, firstDayOfWeek)
                org.onekash.kashcal.ui.components.weekview.WeekViewUtils.formatMonthYearWithWeek(weekStart, firstDayOfWeek)
            }
            ViewMode.MONTH, ViewMode.MONTH_FULL, ViewMode.YEAR -> ""
        }
    }

    when (viewMode) {
        ViewMode.THREE_DAYS, ViewMode.WEEK -> {
            val previousLabel = if (viewMode == ViewMode.WEEK) stringResource(R.string.cd_previous_week) else stringResource(R.string.cd_previous_3_days)
            val nextLabel = if (viewMode == ViewMode.WEEK) stringResource(R.string.cd_next_week) else stringResource(R.string.cd_next_3_days)
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousPage) {
                    Icon(Icons.Default.ChevronLeft, previousLabel)
                }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onMonthClick)
                )
                IconButton(onClick = onNextPage) {
                    Icon(Icons.Default.ChevronRight, nextLabel)
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
        ViewMode.MONTH, ViewMode.MONTH_FULL, ViewMode.YEAR -> {} // Month uses MonthNavHeader, Year has own header
    }
}

/**
 * Display item for agenda - represents one day of an event.
 * For multi-day events, one event becomes multiple display items.
 */
private data class AgendaDisplayItem(
    val displayEvent: DisplayEvent,
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
    events: ImmutableList<DisplayEvent>,
    listState: LazyListState = rememberLazyListState(),
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    refreshKey: Int = 0,
    onEventClick: (DisplayEvent) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat(DateTimeUtils.localizedPattern("EEEEMMMMd"), Locale.getDefault()) }

    // Calculate today's day code for filtering - refreshes on resume via refreshKey
    val todayDayCode = remember(refreshKey) {
        val cal = JavaCalendar.getInstance()
        cal.get(JavaCalendar.YEAR) * 10000 +
            (cal.get(JavaCalendar.MONTH) + 1) * 100 +
            cal.get(JavaCalendar.DAY_OF_MONTH)
    }

    // Expand multi-day events into separate display items per day
    val expandedItems = remember(events, todayDayCode) {
        events.flatMap { displayEvent ->
            val isMultiDay = displayEvent.endDay > displayEvent.startDay
            if (isMultiDay) {
                // Create entry for each day the event spans
                val items = mutableListOf<AgendaDisplayItem>()
                var currentDay = displayEvent.startDay
                var dayNum = 1
                val total = Occurrence.calculateDaysBetween(displayEvent.startDay, displayEvent.endDay) + 1
                while (currentDay <= displayEvent.endDay) {
                    items.add(AgendaDisplayItem(displayEvent, currentDay, dayNum, total))
                    currentDay = Occurrence.incrementDayCode(currentDay)
                    dayNum++
                }
                items
            } else {
                listOf(AgendaDisplayItem(displayEvent, displayEvent.startDay, 1, 1))
            }
        }.filter { item ->
            // Only show items from today onwards
            item.displayDay >= todayDayCode
        }.distinctBy { item ->
            // Deduplicate: use identity-based key (startTs + title + displayDay)
            "${item.displayEvent.title}-${item.displayEvent.startTs}-${item.displayDay}"
        }.sortedWith(compareBy(
            { it.displayDay },  // Primary: by date
            { it.displayEvent.startTs }  // Secondary: by original start time
        ))
    }

    if (expandedItems.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.empty_no_upcoming_events), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                item(key = "header_$displayDay", contentType = "header") {
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
                    key = { item ->
                        when (val de = item.displayEvent) {
                            is DisplayEvent.Room -> "room_${de.event.id}_${de.occurrence.startTs}_${item.displayDay}"
                            is DisplayEvent.Device -> "device_${de.instance.instanceId}_${item.displayDay}"
                        }
                    },
                    contentType = { "agenda_card" }
                ) { item ->
                    val eventColor = Color(item.displayEvent.calendarColor)
                    val isPast = item.displayDay < todayDayCode
                    Column(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                        AgendaCard(
                            item = item,
                            eventColor = eventColor,
                            isPast = isPast,
                            showEventEmojis = showEventEmojis,
                            timePattern = timePattern,
                            onClick = { onEventClick(item.displayEvent) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card for displaying an agenda event.
 * Shows time using DisplayEvent common properties.
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
    val displayEvent = item.displayEvent
    val dateString = formatAgendaCardDate(displayEvent, item.dayNumber, item.totalDays, timePattern)

    // Format title with age for birthday events and optional emoji
    val resources = LocalContext.current.resources
    val displayTitle = remember(displayEvent, showEventEmojis) {
        formatDisplayEventTitle(displayEvent, showEventEmojis, resources)
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
                if (!displayEvent.location.isNullOrEmpty()) {
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
 * Format time display for agenda card.
 * Uses DisplayEvent common properties for correct recurring event display.
 * Shows "Day X of Y" for multi-day events.
 */
private fun formatAgendaCardDate(
    displayEvent: DisplayEvent,
    dayNumber: Int,
    totalDays: Int,
    timePattern: String = "h:mm a"
): String {
    val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())
    val recurringIndicator = if (displayEvent.hasRrule) " \uD83D\uDD01" else ""

    return when {
        totalDays > 1 -> {
            // Multi-day event - show "Day X of Y" with context
            val dayIndicator = "Day $dayNumber of $totalDays"
            when {
                displayEvent.isAllDay -> "$dayIndicator (All day)$recurringIndicator"
                dayNumber == 1 -> {
                    val startTime = timeFormat.format(Date(displayEvent.startTs))
                    "$dayIndicator \u2022 Starts $startTime$recurringIndicator"
                }
                dayNumber == totalDays -> {
                    val endTime = timeFormat.format(Date(displayEvent.endTs))
                    "$dayIndicator \u2022 Ends $endTime$recurringIndicator"
                }
                else -> "$dayIndicator (All day)$recurringIndicator"
            }
        }
        displayEvent.isAllDay -> "All day$recurringIndicator"
        else -> {
            val startTime = timeFormat.format(Date(displayEvent.startTs))
            val endTime = timeFormat.format(Date(displayEvent.endTs))
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

internal fun formatEventTimeDisplay(
    event: Event,
    selectedDateMillis: Long,
    resources: android.content.res.Resources,
    zoneId: ZoneId = ZoneId.systemDefault(),
    timePattern: String = "h:mm a"
): String {
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())

    val startDate = DateTimeUtils.eventTsToLocalDate(event.startTs, event.isAllDay, zoneId)
    val endDate = DateTimeUtils.eventTsToLocalDate(event.endTs, event.isAllDay, zoneId)
    val selectedDate = DateTimeUtils.eventTsToLocalDate(selectedDateMillis, isAllDay = false, zoneId)

    val isMultiDay = endDate.isAfter(startDate)

    if (!isMultiDay) {
        val recurringIndicator = if (event.isRecurring || event.isException) " \uD83D\uDD01" else ""
        return if (event.isAllDay) resources.getString(R.string.label_all_day) + recurringIndicator
        else {
            val startTime = Instant.ofEpochMilli(event.startTs).atZone(zoneId).format(timeFormatter)
            val endTime = Instant.ofEpochMilli(event.endTs).atZone(zoneId).format(timeFormatter)
            "$startTime - $endTime$recurringIndicator"
        }
    }

    val totalDays = DateTimeUtils.calculateTotalDays(event.startTs, event.endTs, event.isAllDay, zoneId)
    val currentDay = calculateCurrentDayForEvent(event.startTs, selectedDateMillis, event.isAllDay, zoneId)
        .coerceIn(1, totalDays)
    val recurringIndicator = if (event.isRecurring || event.isException) " \uD83D\uDD01" else ""

    return when {
        currentDay == 1 && !event.isAllDay -> {
            val startTime = Instant.ofEpochMilli(event.startTs).atZone(zoneId).format(timeFormatter)
            resources.getString(R.string.day_starts, totalDays, startTime) + recurringIndicator
        }
        currentDay == totalDays && !event.isAllDay -> {
            val endTime = Instant.ofEpochMilli(event.endTs).atZone(zoneId).format(timeFormatter)
            resources.getString(R.string.day_ends, currentDay, totalDays, endTime) + recurringIndicator
        }
        else -> resources.getString(R.string.day_x_of_y, currentDay, totalDays) + recurringIndicator
    }
}

private fun formatAgendaCardDate(
    displayEvent: DisplayEvent,
    dayNumber: Int,
    totalDays: Int,
    resources: android.content.res.Resources,
    timePattern: String = "h:mm a"
): String {
    val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())
    val recurringIndicator = if (displayEvent.hasRrule) " \uD83D\uDD01" else ""

    return when {
        totalDays > 1 -> {
            when {
                displayEvent.isAllDay -> resources.getString(R.string.day_x_of_y_all_day, dayNumber, totalDays) + recurringIndicator
                dayNumber == 1 -> {
                    val startTime = timeFormat.format(Date(displayEvent.startTs))
                    resources.getString(R.string.day_starts, totalDays, startTime) + recurringIndicator
                }
                dayNumber == totalDays -> {
                    val endTime = timeFormat.format(Date(displayEvent.endTs))
                    resources.getString(R.string.day_ends, dayNumber, totalDays, endTime) + recurringIndicator
                }
                else -> resources.getString(R.string.day_x_of_y_all_day, dayNumber, totalDays) + recurringIndicator
            }
        }
        displayEvent.isAllDay -> resources.getString(R.string.label_all_day) + recurringIndicator
        else -> {
            val startTime = timeFormat.format(Date(displayEvent.startTs))
            val endTime = timeFormat.format(Date(displayEvent.endTs))
            "$startTime - $endTime$recurringIndicator"
        }
    }
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
    val dateFormatter = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMMd"), Locale.getDefault())
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
 * Format search result date for a device calendar event.
 * Uses DisplayEvent common properties (no Event-specific fields needed).
 */
private fun formatSearchResultDate(
    displayEvent: DisplayEvent,
    zoneId: ZoneId = ZoneId.systemDefault(),
    timePattern: String = "h:mm a"
): String {
    val dateFormatter = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMMd"), Locale.getDefault())
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())

    val startDate = DateTimeUtils.eventTsToLocalDate(displayEvent.startTs, displayEvent.isAllDay, zoneId)
    val endDate = DateTimeUtils.eventTsToLocalDate(displayEvent.endTs, displayEvent.isAllDay, zoneId)
    val isMultiDay = endDate.isAfter(startDate)

    return buildString {
        append(startDate.format(dateFormatter))
        if (isMultiDay) {
            append(" \u2192 ${endDate.format(dateFormatter)}")
        } else if (!displayEvent.isAllDay) {
            val startTime = Instant.ofEpochMilli(displayEvent.startTs).atZone(zoneId).format(timeFormatter)
            append(" \u00B7 $startTime")
        }
        if (displayEvent.hasRrule) append(" \uD83D\uDD01")
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
    val dateFormatter = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMMd"), Locale.getDefault())
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
                    stringResource(R.string.label_select_date)
                } else {
                    stringResource(R.string.hint_tap_date_range)
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
                Text(stringResource(R.string.action_cancel))
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
        if (currentWeekStartMs != 0L) currentWeekStartMs else System.currentTimeMillis()
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
                text = stringResource(R.string.label_go_to_date),
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
                Text(stringResource(R.string.action_cancel))
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
