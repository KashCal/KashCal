package org.onekash.kashcal.ui.screens

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult
import org.onekash.kashcal.ui.components.CalendarDrawer
import org.onekash.kashcal.ui.components.DayEventsSheet
import org.onekash.kashcal.ui.components.EventCard
import org.onekash.kashcal.ui.components.InvitationInboxSheet
import org.onekash.kashcal.ui.components.hub.AccountAvatar
import org.onekash.kashcal.ui.components.hub.AccountHubScreen
import org.onekash.kashcal.ui.components.formatBadgeCount
import org.onekash.kashcal.ui.components.overflowContentDescription
import org.onekash.kashcal.ui.components.SyncBanner
import org.onekash.kashcal.ui.components.TopBarLogoButton
import org.onekash.kashcal.ui.components.AgendaDayHeader
import org.onekash.kashcal.ui.components.AgendaDisplayItem
import org.onekash.kashcal.ui.components.AgendaListModel
import org.onekash.kashcal.ui.components.AgendaTitleMonth
import org.onekash.kashcal.ui.components.AgendaWeekBar
import org.onekash.kashcal.ui.components.AgendaWeekBarLogic
import org.onekash.kashcal.ui.components.buildAgendaListModel
import org.onekash.kashcal.ui.components.resolveScrollTargetIndex
import org.onekash.kashcal.ui.components.TopBarTitleAction
import org.onekash.kashcal.ui.components.TopBarTitleFormatter
import org.onekash.kashcal.ui.components.YearOverlay
import org.onekash.kashcal.ui.components.calculateCurrentDayForEvent
import org.onekash.kashcal.ui.components.cardFillAlpha
import org.onekash.kashcal.ui.components.declinedCardAlpha
import org.onekash.kashcal.ui.components.declinedTitleDecoration
import org.onekash.kashcal.ui.components.eventStateDescription
import org.onekash.kashcal.ui.components.formatDisplayEventTitle
import org.onekash.kashcal.ui.components.formatEventTitle
import org.onekash.kashcal.ui.components.pickers.InlineDatePickerContent
import org.onekash.kashcal.ui.components.weekview.WeekViewContent
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.permission.AppPermissionKind
import org.onekash.kashcal.ui.permission.AppPermissionsScreen
import org.onekash.kashcal.ui.model.MonthGrid
import org.onekash.kashcal.ui.screens.insights.InsightsScreen
import org.onekash.kashcal.ui.screens.insights.InsightsViewModel
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.ui.util.MonthPagerUtils
import org.onekash.kashcal.ui.util.rememberDayPagerSyncCoordinator
import org.onekash.kashcal.ui.viewmodels.DateFilter
import org.onekash.kashcal.ui.viewmodels.EditScope
import org.onekash.kashcal.ui.viewmodels.AgendaUiState
import org.onekash.kashcal.ui.viewmodels.HomeUiState
import org.onekash.kashcal.ui.viewmodels.ViewMode
import org.onekash.kashcal.ui.viewmodels.WeekEventsUiState
import org.onekash.kashcal.util.DateTimeUtils
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
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
    weekEvents: WeekEventsUiState = WeekEventsUiState.EMPTY,
    agendaEvents: AgendaUiState = AgendaUiState.EMPTY,
    monthEvents: ImmutableMap<Int, ImmutableList<DisplayEvent>> = persistentMapOf(),
    isOnline: Boolean = true,
    // Navigation callbacks
    onDateSelected: (Long) -> Unit,
    onGoToToday: () -> Unit,
    onSetViewingMonth: (Int, Int) -> Unit,
    onClearNavigateToToday: () -> Unit,
    onClearNavigateToTodayInstant: () -> Unit = {},
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
    // App lock (owned by the host activity's BiometricPrompt; surfaced in the hub)
    appLockEnabled: Boolean = false,
    onToggleAppLock: (Boolean) -> Unit = {},
    // Deep-link to the system settings page for a given permission kind (the
    // app-permissions screen's granted-row tap and permanently-denied escape hatch)
    onOpenPermissionSettings: (AppPermissionKind) -> Unit = {},
    // Tag management (launched on top of the hub, like Settings)
    onTagsClick: () -> Unit = {},
    onShareAvailabilityClick: () -> Unit = {},
    // Invitation inbox surface (count + open + dismiss + RSVP)
    pendingInvitesCount: Int = 0,
    pendingInvitations: List<org.onekash.kashcal.domain.reader.PendingInvitation> = emptyList(),
    onOpenInvitationInbox: () -> Unit = {},
    onDismissInvitationInbox: () -> Unit = {},
    onRsvpFromInbox: (Long, org.onekash.kashcal.ui.components.attendees.AttendeeStatus) -> Unit = { _, _ -> },
    // Drawer
    drawerState: DrawerState? = null,
    onDrawerToggleCalendar: (Long) -> Unit = {},
    onDrawerToggleDeviceCalendarVisibility: (Long) -> Unit = {},
    // Info callbacks
    onInfoClick: () -> Unit = {},
    // Avatar hub: persist edited initials
    onInitialsChange: (String) -> Unit = {},
    // View picker callback
    onViewSelect: (ViewMode) -> Unit = {},
    // Year overlay callbacks
    onMonthHeaderClick: () -> Unit = {},
    // Agenda week-bar collapse/expand toggle (persisted)
    onAgendaWeekBarToggle: () -> Unit = {},
    // Day view week-strip collapse/expand toggle (persisted)
    onDayWeekBarToggle: () -> Unit = {},
    onYearOverlayDismiss: () -> Unit = {},
    onMonthSelected: (Int, Int) -> Unit = { _, _ -> },
    // Week view callbacks (infinite day pager)
    onDayPagerPageChanged: (Int) -> Unit = {},
    onWeekDatePickerRequest: () -> Unit = {},
    onWeekDayHeaderClick: (LocalDate) -> Unit = {},
    onWeekDatePickerDismiss: () -> Unit = {},
    onWeekDateSelected: (Long) -> Unit = {},
    onWeekScrollPositionChange: (Int) -> Unit = {},
    onWeekScrollMinutesChange: (Int) -> Unit = {},
    onWeekHourHeightChange: (Float) -> Unit = {},
    // All-day strip collapse/expand toggle for the time-grid views (persisted)
    onAllDayRowsToggle: () -> Unit = {},
    onClearPendingWeekPagerPosition: () -> Unit = {},
    onReschedule: (DisplayEvent, LocalDate, Int) -> Unit = { _, _, _ -> },
    onConfirmReschedule: (EditScope) -> Unit = {},
    onCancelPendingReschedule: () -> Unit = {},
    onConfirmFormSave: (EditScope) -> Unit = {},
    onCancelPendingFormSave: () -> Unit = {},
    onConfirmDelete: (EditScope) -> Unit = {},
    onCancelPendingDelete: () -> Unit = {},
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
    dayAttendees: Map<Long, List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel>> = emptyMap(),
    onSetVisibleEventIds: (List<Long>) -> Unit = {},
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

    // Wall-clock snapshot for past-event dimming in the day-events pager.
    // Re-read on resume so an event that ended while the app was backgrounded
    // dims as soon as the user returns, without needing a sync write or swipe.
    val (nowMs, todayDayCode) = remember(refreshKey) {
        val now = System.currentTimeMillis()
        now to DateTimeUtils.eventTsToDayCode(now, isAllDay = false)
    }

    // Agenda list scroll state is hoisted here (above the top bar) so the top-bar
    // title can reflect the month of the topmost visible agenda item as the user
    // scrolls. Falls back to today's month when the list is empty/unparseable.
    val agendaListState = rememberLazyListState()
    val agendaTitleMonth by remember(refreshKey) {
        // Fallback date captured per refreshKey (same resume semantics as `today`),
        // so an empty agenda's title stays consistent with the rest of the bar.
        val fallbackDate = LocalDate.now()
        derivedStateOf {
            val firstKey = agendaListState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
            AgendaTitleMonth.monthYearFromItemKey(firstKey, fallbackDate)
        }
    }

    // Agenda week-bar state. The selected day (null = none in the shown week) is
    // sticky and only changes on tap. While a tap-driven scroll animates, the bar
    // holds the tapped week (agendaBarSuppressed + agendaBarHeldAnchor) so it
    // doesn't flicker through intermediate weeks; otherwise it tracks the topmost
    // visible list item.
    var agendaSelectedDayCode by rememberSaveable(refreshKey) { mutableStateOf<Int?>(null) }
    var agendaBarSuppressed by remember { mutableStateOf(false) }
    var agendaBarHeldAnchor by remember { mutableStateOf<LocalDate?>(null) }
    // Bumped per tap so a rapid second tap's scroll animation doesn't get its
    // suppression cleared by the first tap's cancelled coroutine — only the
    // latest tap's coroutine clears the flag.
    var agendaTapGeneration by remember { mutableIntStateOf(0) }
    // The agenda LazyColumn's top contentPadding lets the item just above the
    // first fully-visible one peek into it, so skip peekers when picking the
    // anchor (else tapping a week's first day snaps the bar to the prior week).
    // Sourced from AGENDA_CONTENT_PADDING so it tracks the list's actual inset.
    val agendaContentPaddingTopPx = with(LocalDensity.current) { AGENDA_CONTENT_PADDING.roundToPx() }
    val agendaWeekDates by remember(refreshKey, uiState.firstDayOfWeek) {
        val fallbackDate = LocalDate.now()
        derivedStateOf {
            val visible = agendaListState.layoutInfo.visibleItemsInfo.map {
                AgendaWeekBarLogic.VisibleItem(it.key as? String, it.offset, it.size)
            }
            val topKey = AgendaWeekBarLogic.topmostAnchorKey(visible, agendaContentPaddingTopPx)
            val anchor = AgendaWeekBarLogic.resolveAnchorDate(
                topKey = topKey,
                suppressed = agendaBarSuppressed,
                heldAnchor = agendaBarHeldAnchor,
                fallback = fallbackDate
            )
            AgendaWeekBarLogic.weekDates(anchor, uiState.firstDayOfWeek)
        }
    }

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

    // Declared before the snackbar effect below because that effect routes to the
    // hub's own snackbar host while the hub overlay is up (see hubSnackbarHostState).
    var showHub by rememberSaveable { mutableStateOf(false) }

    // Snackbar state
    val snackbarHostState = remember { SnackbarHostState() }
    // A second host mounted on the account-hub overlay. The hub is an opaque
    // Surface above the Scaffold, so a snackbar shown on the Scaffold's host
    // would render behind it (invisible). When the hub is up — e.g. the app-lock
    // toggle confirming — route the message to this host instead.
    val hubSnackbarHostState = remember { SnackbarHostState() }
    val viewActionLabel = stringResource(R.string.action_view)

    // Handle snackbar messages
    LaunchedEffect(uiState.pendingSnackbarMessage) {
        uiState.pendingSnackbarMessage?.let { message ->
            val host = if (showHub) hubSnackbarHostState else snackbarHostState
            val result = host.showSnackbar(
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

    // Handle Today button navigation (user-initiated: animate the scroll)
    LaunchedEffect(uiState.pendingNavigateToToday) {
        if (uiState.pendingNavigateToToday) {
            pagerState.animateScrollToPage(initialPage)
            onClearNavigateToToday()
        }
    }

    // Handle cold-start land (programmatic: jump instantly so the pager settles
    // in one frame with no in-flight animation for concurrent writes to fight)
    LaunchedEffect(uiState.pendingNavigateToTodayInstant) {
        if (uiState.pendingNavigateToTodayInstant) {
            pagerState.scrollToPage(initialPage)
            onClearNavigateToTodayInstant()
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
    var showJumpToDatePicker by rememberSaveable { mutableStateOf(false) }
    // App-permissions full-screen destination, opened over the hub (like Manage
    // tags). It carries its own back-arrow + BackHandler; this flag drives its
    // opaque overlay below.
    var showAppPermissions by rememberSaveable { mutableStateOf(false) }
    // Tracks the hub overlay through its enter/exit slide, not just the target flag.
    // Coverage-sensitive behaviour (drawer edge-swipe suppression below) must stay
    // active while the Surface is still animating out, so it keys off this state —
    // which only reports "gone" once the exit slide finishes — rather than showHub,
    // which flips to false the instant back is pressed.
    val hubTransition = remember { MutableTransitionState(false) }
    hubTransition.targetState = showHub
    val hubFullyHidden = hubTransition.isIdle && !hubTransition.currentState

    // Captures view mode + date right before "Jump to date" navigates so a
    // back press restores both. Cleared once consumed (or set to null when no
    // jump is pending).
    var preJumpViewMode by rememberSaveable { mutableStateOf<ViewMode?>(null) }
    var preJumpDate by rememberSaveable { mutableStateOf(0L) }

    ModalNavigationDrawer(
        drawerState = drawerState ?: rememberDrawerState(DrawerValue.Closed),
        // Suppress the edge-swipe while the full-screen hub overlay is up — and
        // while it's still sliding out — so a swipe can't slide the calendar drawer
        // over it. hubFullyHidden stays false until the exit animation completes.
        gesturesEnabled = hubFullyHidden,
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
                onToggleDeviceCalendarVisibility = onDrawerToggleDeviceCalendarVisibility,
                onSettingsClick = {
                    drawerScope.launch { drawerState?.close() }
                    onSettingsClick()
                }
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
                today = remember(refreshKey) { LocalDate.now() },
                agendaTitleMonth = agendaTitleMonth,
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
                onOverflowClick = { showHub = true },
                pendingInvitesCount = pendingInvitesCount,
                onTitleClick = {
                    when (TopBarTitleAction.forViewMode(uiState.viewMode)) {
                        TopBarTitleAction.TOGGLE_AGENDA_WEEK_BAR -> onAgendaWeekBarToggle()
                        TopBarTitleAction.TOGGLE_DAY_WEEK_BAR -> onDayWeekBarToggle()
                        TopBarTitleAction.OPEN_DATE_PICKER -> onWeekDatePickerRequest()
                        TopBarTitleAction.MONTH_HEADER -> onMonthHeaderClick()
                    }
                },
                onViewSelect = onViewSelect
            )
        },
        floatingActionButton = {
            // Insights is a read-only analytics view; event creation is off-context there.
            if (uiState.viewMode != ViewMode.INSIGHTS) {
                FloatingActionButton(
                    onClick = onCreateEvent,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_create_event))
                }
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
                        uiState.viewMode == ViewMode.INSIGHTS -> {
                            BackHandler { onViewSelect(uiState.previousNonInsightsMode) }

                            val insightsVm: InsightsViewModel = hiltViewModel()
                            LaunchedEffect(Unit) {
                                insightsVm.resetToThisWeek()
                            }
                            if (drawerState != null) {
                                LaunchedEffect(Unit) {
                                    var wasOpen = false
                                    snapshotFlow { drawerState.isClosed }
                                        .collect { closed ->
                                            if (wasOpen && closed) insightsVm.recompute()
                                            wasOpen = !closed
                                        }
                                }
                            }
                            InsightsScreen(viewModel = insightsVm)
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
                        uiState.viewMode == ViewMode.AGENDA || uiState.viewMode.isTimeGrid -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Handle scroll to top when Today button is pressed in agenda view
                                LaunchedEffect(uiState.pendingScrollAgendaToTop) {
                                    if (uiState.pendingScrollAgendaToTop) {
                                        agendaListState.animateScrollToItem(0)
                                        onClearScrollAgendaToTop()
                                    }
                                }

                                when (uiState.viewMode) {
                                    ViewMode.AGENDA -> {
                                        // Pinned week bar above the list, collapsible via the top-bar
                                        // title chevron (state persisted). Tapping a date selects it
                                        // and scrolls the list to that day's header; the bar tracks
                                        // the scrolled week otherwise.
                                        if (uiState.agendaWeekBarExpanded) {
                                            AgendaWeekBar(
                                                weekDates = agendaWeekDates,
                                                selectedDayCode = agendaSelectedDayCode,
                                                todayDayCode = todayDayCode,
                                                onDayClick = { tappedDayCode ->
                                                    agendaSelectedDayCode = tappedDayCode
                                                    agendaBarHeldAnchor = DayPagerUtils.dayCodeToLocalDate(tappedDayCode)
                                                    agendaBarSuppressed = true
                                                    val generation = ++agendaTapGeneration
                                                    val model = buildAgendaListModel(agendaEvents.events, todayDayCode)
                                                    val target = resolveScrollTargetIndex(tappedDayCode, model)
                                                    coroutineScope.launch {
                                                        try {
                                                            if (target >= 0) agendaListState.animateScrollToItem(target)
                                                        } finally {
                                                            // Only the latest tap clears suppression, so a
                                                            // rapid double-tap can't unsuppress mid-animation.
                                                            if (generation == agendaTapGeneration) {
                                                                agendaBarSuppressed = false
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        if (agendaEvents.isLoading) {
                                            val loadingLabel = stringResource(R.string.cd_loading_events)
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    // The spinner has no text; give it a spoken label and
                                                    // announce it politely so TalkBack says "Loading events".
                                                    .semantics {
                                                        liveRegion = LiveRegionMode.Polite
                                                        contentDescription = loadingLabel
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator()
                                            }
                                        } else {
                                            val agendaModel = remember(agendaEvents.events, todayDayCode) {
                                                buildAgendaListModel(agendaEvents.events, todayDayCode)
                                            }
                                            AgendaContent(
                                                model = agendaModel,
                                                todayDayCode = todayDayCode,
                                                listState = agendaListState,
                                                showEventEmojis = uiState.showEventEmojis,
                                                timePattern = timePattern,
                                                onEventClick = { displayEvent ->
                                                    when (displayEvent) {
                                                        is DisplayEvent.Room -> onEventClick(displayEvent.event, displayEvent.occurrence.startTs)
                                                        is DisplayEvent.Device -> onDeviceEventClick(displayEvent)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    ViewMode.DAY, ViewMode.THREE_DAYS, ViewMode.WEEK -> {
                                        // Day view gets a pinned week strip above the grid,
                                        // toggled by the title chevron (state persisted). Tapping a
                                        // date drives the day pager to it. The strip reads the same
                                        // weekViewPagerPosition the title does, so the two stay in
                                        // lockstep. Only render once the position is a settled
                                        // day-scale page: the default (0) and a stale week-scale
                                        // page from a WEEK->DAY switch would otherwise flash an
                                        // absurd date (see isSettledDayPage).
                                        if (uiState.viewMode == ViewMode.DAY &&
                                            uiState.dayWeekBarExpanded &&
                                            WeekViewUtils.isSettledDayPage(uiState.weekViewPagerPosition)
                                        ) {
                                            // Memoize keyed on the two inputs so the strip's date
                                            // arithmetic and 7-day list aren't rebuilt on unrelated
                                            // recompositions (event loads, scroll). Plain remember —
                                            // no layout dependency, unlike the agenda strip's anchor.
                                            val shownDate = remember(uiState.weekViewPagerPosition) {
                                                WeekViewUtils.pageToDate(uiState.weekViewPagerPosition)
                                            }
                                            val shownDayCode = remember(shownDate) {
                                                DayPagerUtils.localDateToDayCode(shownDate)
                                            }
                                            val dayWeekDates = remember(shownDate, uiState.firstDayOfWeek) {
                                                AgendaWeekBarLogic.weekDates(shownDate, uiState.firstDayOfWeek)
                                            }
                                            AgendaWeekBar(
                                                weekDates = dayWeekDates,
                                                selectedDayCode = shownDayCode,
                                                todayDayCode = todayDayCode,
                                                onDayClick = { tappedDayCode ->
                                                    onWeekDateSelected(DayPagerUtils.dayCodeToMs(tappedDayCode))
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        WeekViewContent(
                                            timedEvents = weekEvents.timedEvents,
                                            allDayEvents = weekEvents.allDayEvents,
                                            isLoading = weekEvents.isLoading,
                                            error = weekEvents.error,
                                            scrollPosition = uiState.weekViewScrollPosition,
                                            savedScrollMinutes = uiState.weekViewSavedScrollMinutes,
                                            hourHeight = uiState.weekViewHourHeight,
                                            onHourHeightChange = onWeekHourHeightChange,
                                            showEventEmojis = uiState.showEventEmojis,
                                            timePattern = timePattern,
                                            visibleDays = uiState.viewMode.visibleDays ?: 3,
                                            firstDayOfWeek = uiState.firstDayOfWeek,
                                            allDayRowsExpanded = uiState.allDayRowsExpanded,
                                            onAllDayRowsToggle = onAllDayRowsToggle,
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
                                            onScrollMinutesChange = onWeekScrollMinutesChange,
                                            onPageChanged = onDayPagerPageChanged,
                                            pendingNavigateToPage = uiState.pendingWeekViewPagerPosition,
                                            onNavigationConsumed = onClearPendingWeekPagerPosition,
                                            onReschedule = onReschedule,
                                            onDayHeaderClick = { date ->
                                                // Snapshot the current view + date before
                                                // drilling into DAY, reusing the same
                                                // mechanism as "Jump to date" so a back
                                                // press restores the week/3-day view and
                                                // date we came from. The drill-in itself
                                                // stays a transient switch (does not change
                                                // the startup default).
                                                //
                                                // The week/3-day grid tracks its position in
                                                // weekViewPagerPosition, not selectedDate (which
                                                // only month-view taps write), so derive the
                                                // shown date from the pager page — mode-aware,
                                                // since WEEK uses week pages and 3-day uses day
                                                // pages. Restoring selectedDate would jump back
                                                // to a stale week or, at cold start, to today.
                                                preJumpViewMode = uiState.viewMode
                                                val shownDate = if (uiState.viewMode == ViewMode.WEEK) {
                                                    WeekViewUtils.weekPageToStartDate(
                                                        uiState.weekViewPagerPosition,
                                                        uiState.firstDayOfWeek
                                                    )
                                                } else {
                                                    WeekViewUtils.pageToDate(uiState.weekViewPagerPosition)
                                                }
                                                preJumpDate = WeekViewUtils.dateToEpochMs(shownDate)
                                                onWeekDayHeaderClick(date)
                                            },
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
                                    DayOfWeekHeaders(
                                        firstDayOfWeek = uiState.firstDayOfWeek,
                                        showWeekNumbers = uiState.showWeekNumbers,
                                    )

                                    FullHeightMonthGrid(
                                        year = pageYear,
                                        month = pageMonth,
                                        selectedDate = uiState.selectedDate,
                                        monthEventsMap = monthEvents,
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
                                    DayOfWeekHeaders(
                                        firstDayOfWeek = uiState.firstDayOfWeek,
                                        showWeekNumbers = uiState.showWeekNumbers,
                                    )
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
                                    // Day events sheet (right) — rises off the grid via tone + radius
                                    Surface(
                                        modifier = Modifier.weight(0.55f).fillMaxHeight(),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape = RoundedCornerShape(
                                            topStart = 24.dp,
                                            bottomStart = 24.dp,
                                            topEnd = 0.dp,
                                            bottomEnd = 0.dp,
                                        ),
                                    ) {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            DayEventsPager(
                                                uiState = uiState,
                                                dayPagerState = dayPagerState,
                                                dayPagerTodayMs = dayPagerTodayMs,
                                                monthPagerState = pagerState,
                                                monthPagerInitialPage = initialPage,
                                                todayYear = todayYear,
                                                todayMonth = todayMonth,
                                                timePattern = timePattern,
                                                nowMs = nowMs,
                                                todayDayCode = todayDayCode,
                                                onEventClick = onEventClick,
                                                onDeviceEventClick = onDeviceEventClick,
                                                onDateSelected = onDateSelected,
                                                onLoadEventsForRange = onLoadEventsForDayPagerRange,
                                                shouldRefreshCache = shouldRefreshDayPagerCache,
                                                attendeesByEventId = dayAttendees,
                                                onSetVisibleEventIds = onSetVisibleEventIds
                                            )
                                        }
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
                                    // Day events sheet — rises off the grid via tone + radius
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape = RoundedCornerShape(
                                            topStart = 24.dp,
                                            topEnd = 24.dp,
                                            bottomStart = 0.dp,
                                            bottomEnd = 0.dp,
                                        ),
                                    ) {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            DayEventsPager(
                                                uiState = uiState,
                                                dayPagerState = dayPagerState,
                                                dayPagerTodayMs = dayPagerTodayMs,
                                                monthPagerState = pagerState,
                                                monthPagerInitialPage = initialPage,
                                                todayYear = todayYear,
                                                todayMonth = todayMonth,
                                                timePattern = timePattern,
                                                nowMs = nowMs,
                                                todayDayCode = todayDayCode,
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
            currentWeekStartMs = System.currentTimeMillis(),
            onDateSelected = onWeekDateSelected,
            onDismiss = onWeekDatePickerDismiss,
            firstDayOfWeek = uiState.firstDayOfWeek
        )
    }

    // "Jump to date" — uses the same month-grid sheet as the week view so a
    // tap navigates immediately. Switches to DAY before navigating so the
    // pager page is computed in day-mode, not week-mode.
    if (showJumpToDatePicker) {
        WeekViewDatePickerSheet(
            currentWeekStartMs = uiState.selectedDate.takeIf { it != 0L } ?: System.currentTimeMillis(),
            onDateSelected = { dateMs ->
                preJumpViewMode = uiState.viewMode
                preJumpDate = uiState.selectedDate
                onViewSelect(ViewMode.DAY)
                onWeekDateSelected(dateMs)
                showJumpToDatePicker = false
                // Picking a date navigates the calendar, so the hub (still mounted
                // behind the picker) must close to reveal the result.
                showHub = false
            },
            onDismiss = { showJumpToDatePicker = false },
            firstDayOfWeek = uiState.firstDayOfWeek
        )
    }

    // Back press after a Jump-to-date navigation restores the view + date the
    // user was on before the jump. Single-shot — clears the snapshot so a
    // second back press falls through to normal back behavior.
    val pending = preJumpViewMode
    BackHandler(enabled = pending != null && !showJumpToDatePicker && !showHub) {
        onViewSelect(pending!!)
        if (preJumpDate != 0L) onWeekDateSelected(preJumpDate)
        preJumpViewMode = null
        preJumpDate = 0L
    }

    // Full-screen destination (like the Insights view) rendered as an opaque
    // overlay above the Scaffold, so it covers the calendar's own top bar and
    // FAB. A boolean flag is invisible to the top-bar `when` and the FAB's
    // viewMode gate, so the overlay — not those branches — owns coverage.
    // Surface (not a bare Box) is load-bearing here: its pointer-input barrier
    // stops taps from reaching the FAB behind it, and gesturesEnabled=hubFullyHidden
    // above suppresses the drawer edge-swipe. Keep both if this is refactored.
    // AnimatedVisibility slides the hub in from the trailing edge (the avatar sits
    // on the trailing corner, so a trailing-edge push reads as drilling in) and
    // reverses on back; the Surface stays mounted through the exit slide so the FAB
    // stays covered. visibleState (not visible) drives it so hubFullyHidden can
    // observe the exit completing. The offset is negated in RTL so "trailing edge"
    // stays the leading corner of the incoming screen regardless of layout direction.
    val hubSlideSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    AnimatedVisibility(
        visibleState = hubTransition,
        enter = slideInHorizontally { width -> hubSlideSign * width } + fadeIn(),
        exit = slideOutHorizontally { width -> hubSlideSign * width } + fadeOut(),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AccountHubScreen(
                pendingInvitesCount = pendingInvitesCount,
                userInitials = uiState.userInitials,
                onInitialsChange = onInitialsChange,
                // Destinations that open a sheet/Activity ON TOP of the hub keep it
                // mounted — the destination covers it, so there's no bare-calendar
                // flash, and dismissing the destination returns to the hub. Only
                // Jump-to-date changes the calendar itself (closed when a date is
                // actually picked, below).
                onInvitesClick = onOpenInvitationInbox,
                onJumpToDateClick = { showJumpToDatePicker = true },
                onShareAvailabilityClick = onShareAvailabilityClick,
                onTagsClick = onTagsClick,
                onSettingsClick = onSettingsClick,
                onAboutClick = onInfoClick,
                onBack = { showHub = false },
                appLockEnabled = appLockEnabled,
                onToggleAppLock = onToggleAppLock,
                onAppPermissionsClick = { showAppPermissions = true },
                // App-lock enable/enroll/unsupported confirmations fire while the
                // hub is up; host them here so they're not hidden behind the overlay.
                snackbarHost = { SnackbarHost(hostState = hubSnackbarHostState) },
            )
        }
    }

    // App permissions: full-screen destination rendered as an opaque overlay
    // above the hub (which stays mounted beneath), mirroring how the hub covers
    // the calendar. Its own back arrow + BackHandler dismiss it back to the hub.
    AnimatedVisibility(
        visible = showAppPermissions,
        enter = slideInHorizontally { width -> hubSlideSign * width } + fadeIn(),
        exit = slideOutHorizontally { width -> hubSlideSign * width } + fadeOut(),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppPermissionsScreen(
                onBack = { showAppPermissions = false },
                onOpenPermissionSettings = onOpenPermissionSettings,
            )
        }
    }

    // Invitation inbox bottom sheet
    if (uiState.isInvitationInboxOpen) {
        InvitationInboxSheet(
            invitations = pendingInvitations,
            timePattern = timePattern,
            onRsvp = onRsvpFromInbox,
            onDismiss = onDismissInvitationInbox
        )
    }

    // Day events bottom sheet (month view)
    if (uiState.showDayDetailSheet) {
        val dayCode = DayPagerUtils.msToDayCode(uiState.dayDetailDate)
        val dayEvents = monthEvents[dayCode] ?: persistentListOf()
        DayEventsSheet(
            dateMs = uiState.dayDetailDate,
            events = dayEvents,
            showEventEmojis = uiState.showEventEmojis,
            timePattern = timePattern,
            onEventClick = onEventClick,
            onDeviceEventClick = onDeviceEventClick,
            onCreateEvent = onCreateEventWithDateTime,
            onDismiss = onDismissDayDetail,
            attendeesByEventId = dayAttendees
        )
    }

    // Recurring scope sheets — drag-to-reschedule, form-save, and
    // delete all share the same component. The option set differs
    // per flow; computed by helpers in the ViewModel layer.
    val scopeResources = LocalResources.current

    uiState.pendingDragReschedule?.let { pending ->
        val isDevice = pending.displayEvent is DisplayEvent.Device
        val (masterStartTs, occurrenceTs, isAllDay) = when (val ev = pending.displayEvent) {
            is DisplayEvent.Room -> Triple(ev.event.startTs, ev.occurrence.startTs, ev.event.isAllDay)
            is DisplayEvent.Device -> Triple(ev.instance.eventStartTs, ev.startTs, ev.instance.isAllDay)
        }
        val options = org.onekash.kashcal.ui.viewmodels.computeDragScopeOptions(
            masterStartTs = masterStartTs,
            targetOccurrenceTs = occurrenceTs,
            isAllDay = isAllDay,
            isDevice = isDevice,
            resources = scopeResources,
        )
        org.onekash.kashcal.ui.components.RecurringScopeSheet(
            title = stringResource(R.string.dialog_move_recurring_title),
            options = options,
            onSelect = onConfirmReschedule,
            onCancel = onCancelPendingReschedule,
        )
    }

    uiState.pendingFormSave?.let { pending ->
        val context = remember(pending) {
            org.onekash.kashcal.ui.viewmodels.ScopeContext(
                masterStartTs = pending.masterStartTs,
                occurrenceTs = pending.occurrenceTs,
                isDetachedException = pending.isDetachedException,
                isAllDay = pending.loadedIsAllDay,
            )
        }
        val options = remember(context, pending.originalRrule, pending.formState.rrule) {
            org.onekash.kashcal.ui.viewmodels.computeEditScopeOptions(
                context = context,
                originalRrule = pending.originalRrule,
                currentRrule = pending.formState.rrule,
                resources = scopeResources,
            )
        }
        org.onekash.kashcal.ui.components.RecurringScopeSheet(
            title = stringResource(R.string.dialog_edit_recurring_title),
            options = options,
            onSelect = onConfirmFormSave,
            onCancel = onCancelPendingFormSave,
        )
    }

    uiState.pendingDelete?.let { pending ->
        val context = remember(pending) {
            org.onekash.kashcal.ui.viewmodels.ScopeContext(
                masterStartTs = pending.masterStartTs,
                occurrenceTs = pending.occurrenceTs,
                isDetachedException = pending.isDetachedException,
                isAllDay = pending.isAllDay,
            )
        }
        val options = remember(context) {
            org.onekash.kashcal.ui.viewmodels.computeDeleteScopeOptions(
                context = context,
                resources = scopeResources,
            )
        }
        org.onekash.kashcal.ui.components.RecurringScopeSheet(
            title = stringResource(R.string.dialog_delete_recurring_title),
            options = options,
            onSelect = onConfirmDelete,
            onCancel = onCancelPendingDelete,
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
    today: LocalDate,
    agendaTitleMonth: Pair<Int, Int>,
    drawerState: DrawerState?,
    pendingInvitesCount: Int,
    onSearchClick: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onMenuClick: () -> Unit,
    onGoToToday: () -> Unit,
    onOverflowClick: () -> Unit,
    onTitleClick: () -> Unit,
    onViewSelect: (ViewMode) -> Unit
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
        uiState.viewMode == ViewMode.INSIGHTS -> {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.insights_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    )
                },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onViewSelect(uiState.previousNonInsightsMode) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TopBarLogoButton(
                            onClick = onGoToToday,
                            today = today,
                        )
                    }
                },
                actions = {
                    AvatarTrigger(
                        pendingInvitesCount = pendingInvitesCount,
                        userInitials = uiState.userInitials,
                        onClick = onOverflowClick
                    )
                }
            )
        }
        else -> {
            val weekPrefix = stringResource(R.string.label_week)
            val weekSuffixTemplate = stringResource(R.string.calendar_header_week_suffix, "%1\$s", "%2\$s")
            val yearLabel = stringResource(R.string.view_year)
            // Agenda's title tracks the topmost visible day's month; all other views
            // use their own viewing month. The formatter reads viewingYear/viewingMonth,
            // so substitute the scroll-derived month only for AGENDA.
            val isAgenda = uiState.viewMode == ViewMode.AGENDA
            val titleText = TopBarTitleFormatter.format(
                viewMode = uiState.viewMode,
                viewingYear = if (isAgenda) agendaTitleMonth.first else uiState.viewingYear,
                viewingMonth = if (isAgenda) agendaTitleMonth.second else uiState.viewingMonth,
                weekViewPagerPosition = uiState.weekViewPagerPosition,
                firstDayOfWeek = uiState.firstDayOfWeek,
                weekPrefix = weekPrefix,
                weekSuffixTemplate = weekSuffixTemplate,
                yearLabel = yearLabel,
                today = today,
            )
            // AGENDA and DAY both show a collapsible week bar, toggled by the title
            // chevron. The rest of the views (including the other time-grid views,
            // which open a date picker instead) use a plain clickable title.
            val showWeekBarChevron = uiState.viewMode == ViewMode.AGENDA || uiState.viewMode == ViewMode.DAY
            val weekBarExpanded = if (uiState.viewMode == ViewMode.DAY) {
                uiState.dayWeekBarExpanded
            } else {
                uiState.agendaWeekBarExpanded
            }
            val titleFontSize = if (uiState.viewMode == ViewMode.WEEK) 18.sp else 20.sp
            CenterAlignedTopAppBar(
                title = {
                    if (showWeekBarChevron) {
                        // Chevron points up when the week bar is expanded (tap to
                        // collapse), down when collapsed (tap to expand).
                        val weekBarChevronRotation by animateFloatAsState(
                            targetValue = if (weekBarExpanded) 180f else 0f,
                            animationSpec = tween(300),
                            label = "weekBarChevronRotation"
                        )
                        // onClickLabel describes the toggle action to TalkBack while
                        // the title text ("July 2026") stays the node's spoken content.
                        val toggleLabel = if (weekBarExpanded) {
                            stringResource(R.string.cd_collapse_week_bar)
                        } else {
                            stringResource(R.string.cd_expand_week_bar)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(onClickLabel = toggleLabel, onClick = onTitleClick)
                        ) {
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = titleFontSize),
                            )
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(start = 2.dp)
                                    .size(24.dp)
                                    .graphicsLayer { rotationZ = weekBarChevronRotation }
                            )
                        }
                    } else {
                        // Year has no title action; other date-driven views open a picker.
                        val titleModifier = if (uiState.viewMode != ViewMode.YEAR) {
                            Modifier.clickable(onClick = onTitleClick)
                        } else {
                            Modifier
                        }
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = titleFontSize),
                            modifier = titleModifier,
                        )
                    }
                },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onMenuClick) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = if (isDrawerOpen) stringResource(R.string.cd_close_drawer) else stringResource(R.string.cd_open_drawer),
                                modifier = Modifier
                                    .size(26.dp)
                                    .graphicsLayer { rotationZ = menuRotation }
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TopBarLogoButton(
                            onClick = onGoToToday,
                            today = today,
                        )
                    }
                },
                actions = {
                    AnimatedVisibility(visible = !isOnline && uiState.isConfigured) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = stringResource(R.string.cd_offline),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                // Announce the transition to offline to TalkBack.
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.cd_search),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    AvatarTrigger(
                        pendingInvitesCount = pendingInvitesCount,
                        userInitials = uiState.userInitials,
                        onClick = onOverflowClick
                    )
                }
            )
        }
    }
}

/**
 * Top-bar trigger that opens the account hub. Renders the user's initials
 * avatar (or a neutral glyph when unset) with a numeric badge when
 * [pendingInvitesCount] > 0; tapping invokes [onClick]. The accessibility label
 * still resolves through [overflowContentDescription] so the announcement and
 * badge never disagree on the count, and TalkBack keeps the familiar "More
 * menu" affordance even though the glyph is now an avatar.
 */
@Composable
private fun AvatarTrigger(
    pendingInvitesCount: Int,
    userInitials: String,
    onClick: () -> Unit
) {
    val baseLabel = stringResource(R.string.menu_more)
    val withInvitesLabel = pluralStringResource(
        R.plurals.menu_more_with_invites,
        pendingInvitesCount,
        pendingInvitesCount
    )
    val triggerDescription = overflowContentDescription(
        count = pendingInvitesCount,
        baseLabel = baseLabel,
        withInvitesLabel = withInvitesLabel
    )

    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = triggerDescription }
    ) {
        BadgedBox(
            badge = {
                val badgeText = formatBadgeCount(pendingInvitesCount)
                if (badgeText != null) {
                    Badge { Text(badgeText) }
                }
            }
        ) {
            // Tuned against the 26.dp sibling glyphs (Menu/Search): the disc is
            // slightly larger but its inner content (glyph/monogram) is inset, so
            // it reads at about the same visual weight rather than oversized.
            AccountAvatar(initials = userInitials, size = 30.dp, fontSize = 13.sp)
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
        val locale = LocalLocale.current.platformLocale
        daysOfWeek.forEach { day ->
            val isWeekend = day == java.time.DayOfWeek.SUNDAY || day == java.time.DayOfWeek.SATURDAY
            Text(
                text = day.getDisplayName(java.time.format.TextStyle.SHORT, locale),
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
                                    // The today fill can wash out against the surface for pale accent
                                    // seeds; a hairline outline keeps the cell visible on any theme
                                    // (selected uses a strong fill and needs no border).
                                    .then(
                                        if (isToday && !isSelected) {
                                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                        } else {
                                            Modifier
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
    nowMs: Long,
    todayDayCode: Int,
    onEventClick: (Event, Long?) -> Unit,
    onDeviceEventClick: (DisplayEvent.Device) -> Unit = {},
    onDateSelected: (Long) -> Unit,
    onLoadEventsForRange: (Long) -> Unit,
    shouldRefreshCache: (Long) -> Boolean,
    attendeesByEventId: Map<Long, List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel>> = emptyMap(),
    onSetVisibleEventIds: (List<Long>) -> Unit = {}
) {
    val todayMs = dayPagerTodayMs

    val coroutineScope = rememberCoroutineScope()

    // Breaks the settle↔selectedDate feedback loop (issue #267): only a settle
    // that concluded a real user swipe may push back up to selectedDate. A
    // programmatic scroll (grid tap, Today, cold-start) emits no drag, so its
    // settle is suppressed and rapid taps can't oscillate.
    val syncCoordinator = rememberDayPagerSyncCoordinator(dayPagerState.interactionSource)

    // SYNC 1: Day pager settled → Update selectedDate + navigate month if boundary crossed
    LaunchedEffect(dayPagerState.settledPage) {
        val newDateMs = DayPagerUtils.pageToDateMs(dayPagerState.settledPage, todayMs)

        // Consume the user-drag intent on every settle (no carry-over to a later
        // programmatic settle), then update selectedDate only for user-driven
        // settles; suppressing the echo of a programmatic scroll is what stops
        // rapid taps from oscillating (#267).
        val isUserSettle = syncCoordinator.shouldPropagateSettle()
        if (newDateMs != uiState.selectedDate && isUserSettle) {
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

    // Push visible event IDs upward only for the settled page. The pager
    // composes neighbour pages eagerly (beyondViewportPageCount), so doing
    // this per-page would race — last neighbour wins.
    val settledDayCode = remember(dayPagerState.settledPage, todayMs) {
        DayPagerUtils.msToDayCode(DayPagerUtils.pageToDateMs(dayPagerState.settledPage, todayMs))
    }
    val visibleEventIds = remember(uiState.dayEventsCache, settledDayCode) {
        (uiState.dayEventsCache[settledDayCode] ?: persistentListOf())
            .mapNotNull { (it as? DisplayEvent.Room)?.event?.id }
    }
    LaunchedEffect(visibleEventIds) {
        onSetVisibleEventIds(visibleEventIds)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = stringResource(R.string.cd_empty_pick_a_day),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.empty_pick_a_day),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
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
                nowMs = nowMs,
                todayDayCode = todayDayCode,
                onEventClick = onEventClick,
                onDeviceEventClick = onDeviceEventClick,
                attendeesByEventId = attendeesByEventId
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
    nowMs: Long,
    todayDayCode: Int,
    onEventClick: (Event, Long?) -> Unit,
    onDeviceEventClick: (DisplayEvent.Device) -> Unit = {},
    attendeesByEventId: Map<Long, List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel>> = emptyMap()
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
            // Rotate the playful empty-day line per calendar day so it varies
            // between empty days but is stable across recomposition (no random).
            // Keyed on epoch-day for clean day-to-day cycling with no month/year
            // boundary repeats.
            val emptyDayPhrases = remember {
                intArrayOf(
                    R.string.empty_no_events_day_1,
                    R.string.empty_no_events_day_2,
                    R.string.empty_no_events_day_3,
                    R.string.empty_no_events_day_4,
                    R.string.empty_no_events_day_5,
                )
            }
            val epochDay = Instant.ofEpochMilli(dateMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toEpochDay()
            val phraseRes = emptyDayPhrases[Math.floorMod(epochDay, emptyDayPhrases.size.toLong()).toInt()]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.SelfImprovement,
                    contentDescription = stringResource(R.string.cd_empty_no_events_day),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(phraseRes),
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
                    val isPast = DateTimeUtils.isEventPast(
                        endTs = displayEvent.endTs,
                        endDay = displayEvent.endDay,
                        isAllDay = displayEvent.isAllDay,
                        nowMs = nowMs,
                        todayDayCode = todayDayCode,
                    )

                    val attendeeModels = (displayEvent as? DisplayEvent.Room)
                        ?.let { attendeesByEventId[it.event.id] }
                        .orEmpty()

                    EventCard(
                        displayEvent = displayEvent,
                        isPast = isPast,
                        selectedDate = dateMs,
                        showEventEmojis = showEventEmojis,
                        timePattern = timePattern,
                        attendees = attendeeModels,
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
    val stripeColor = Color(displayEvent.calendarColor)
    val fillColor = Color(displayEvent.eventColor ?: displayEvent.calendarColor)
    val fillAlpha = displayEvent.cardFillAlpha()

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
    val resources = LocalResources.current
    val displayTitle = remember(searchResult, showEventEmojis) {
        when (displayEvent) {
            is DisplayEvent.Room -> formatEventTitle(displayEvent.event, searchResult.displayTs, showEventEmojis, resources)
            is DisplayEvent.Device -> EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis)
        }
    }

    val searchStateLabel = eventStateDescription(isPast, displayEvent.isDeclinedByMe, displayEvent.isCancelled)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isPast) 0.5f else 1f)
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
 * Uniform padding around the agenda list. The top inset is also the peek
 * threshold the week bar uses to skip a previous item bleeding into the padding
 * (see the anchor derivation in [HomeScreen]) — keep them sourced from here so
 * the two can't drift.
 */
private val AGENDA_CONTENT_PADDING = 16.dp

/**
 * Agenda content - shows upcoming 90 days of occurrences.
 * Each recurring event instance is shown separately.
 * Multi-day events appear on each day they span with "Day X of Y" indicator.
 * Groups occurrences by date with date headers; today/tomorrow headers carry a
 * relative word ("Today"/"Tomorrow") beside the full date.
 *
 * The expansion / dedup / grouping is precomputed into [model] by the caller so
 * the same grouping (and its header-index map) drives both this list and the
 * week bar's tap-to-scroll. [todayDayCode] is the single today reference shared
 * with the header formatter and week bar.
 */
@Composable
private fun AgendaContent(
    model: AgendaListModel,
    todayDayCode: Int,
    listState: LazyListState = rememberLazyListState(),
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    onEventClick: (DisplayEvent) -> Unit
) {
    val todayLabel = stringResource(R.string.label_today)
    val tomorrowLabel = stringResource(R.string.label_tomorrow)
    val relativeWithDateTemplate = stringResource(R.string.agenda_header_relative_with_date)

    if (model.groups.isEmpty()) {
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
            contentPadding = PaddingValues(AGENDA_CONTENT_PADDING),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            model.groups.forEach { group ->
                val displayDay = group.dayCode
                item(key = "header_$displayDay", contentType = "header") {
                    val parts = remember(displayDay, todayDayCode, todayLabel, tomorrowLabel) {
                        AgendaDayHeader.format(displayDay, todayDayCode, todayLabel, tomorrowLabel)
                    }
                    if (parts.relativeLabel != null) {
                        // Two-tone: accent the relative word, mute the rest. The
                        // join + accent-range logic is reorder-safe (see
                        // AgendaDayHeader.joinedHeader) so date-first locales work.
                        val header = remember(parts, relativeWithDateTemplate) {
                            AgendaDayHeader.joinedHeader(parts, relativeWithDateTemplate)
                        }
                        val accentColor = MaterialTheme.colorScheme.primary
                        val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
                        Text(
                            text = buildAnnotatedString {
                                append(header.text)
                                addStyle(SpanStyle(color = mutedColor), 0, header.text.length)
                                if (header.hasAccent) {
                                    addStyle(SpanStyle(color = accentColor), header.accentStart, header.accentEnd)
                                }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Text(
                            parts.dateText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                items(
                    items = group.items,
                    key = { item ->
                        when (val de = item.displayEvent) {
                            is DisplayEvent.Room -> "room_${de.event.id}_${de.occurrence.startTs}_${item.displayDay}"
                            is DisplayEvent.Device -> "device_${de.instance.instanceId}_${item.displayDay}"
                        }
                    },
                    contentType = { "agenda_card" }
                ) { item ->
                    val isPast = item.displayDay < todayDayCode
                    Column(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                        AgendaCard(
                            item = item,
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
    isPast: Boolean,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mm a",
    onClick: () -> Unit
) {
    val displayEvent = item.displayEvent
    val stripeColor = Color(displayEvent.calendarColor)
    val fillColor = Color(displayEvent.eventColor ?: displayEvent.calendarColor)
    val fillAlpha = displayEvent.cardFillAlpha()

    val resources = LocalResources.current
    val dateString = formatAgendaCardDate(displayEvent, item.dayNumber, item.totalDays, resources, timePattern)

    // Format title with age for birthday events and optional emoji
    val displayTitle = remember(displayEvent, showEventEmojis) {
        formatDisplayEventTitle(displayEvent, showEventEmojis, resources)
    }

    val agendaStateLabel = eventStateDescription(isPast, displayEvent.isDeclinedByMe, displayEvent.isCancelled)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(declinedCardAlpha(isPast, displayEvent.isDeclinedByMe, displayEvent.isCancelled))
            .then(if (agendaStateLabel != null) Modifier.semantics { stateDescription = agendaStateLabel } else Modifier)
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
                    displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textDecoration = declinedTitleDecoration(displayEvent.isDeclinedByMe, displayEvent.isCancelled)
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

    val isMultiDay = DateTimeUtils.spansMultipleDays(event.startTs, event.endTs, event.isAllDay, zoneId)

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

    val isMultiDay = DateTimeUtils.spansMultipleDays(event.startTs, event.endTs, event.isAllDay, zoneId)

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

    val startDate = DateTimeUtils.eventTsToLocalDate(event.startTs, event.isAllDay, zoneId)
    val displayEndDate = DateTimeUtils.eventTsToLocalDate(event.endTs, event.isAllDay, zoneId)
    val isMultiDay = DateTimeUtils.spansMultipleDays(event.startTs, event.endTs, event.isAllDay, zoneId)
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
    val isMultiDay = DateTimeUtils.spansMultipleDays(displayEvent.startTs, displayEvent.endTs, displayEvent.isAllDay, zoneId)

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
