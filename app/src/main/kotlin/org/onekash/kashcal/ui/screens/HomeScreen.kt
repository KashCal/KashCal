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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
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
import org.onekash.kashcal.ui.viewmodels.toScopeContext
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
 * Shows the main calendar screen: every [ViewMode], search, the calendar drawer, the account hub
 * overlay, the sync banner, pull-to-refresh and the offline indicator.
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
    // App lock, owned by the host activity's BiometricPrompt and shown in the hub
    appLockEnabled: Boolean = false,
    onToggleAppLock: (Boolean) -> Unit = {},
    // Opens the system settings page for a permission kind (the app-permissions screen's
    // granted-row tap and permanently-denied escape hatch)
    onOpenPermissionSettings: (AppPermissionKind) -> Unit = {},
    // Tag management, launched on top of the hub like Settings
    onTagsClick: () -> Unit = {},
    onShareAvailabilityClick: () -> Unit = {},
    // Invitation inbox: count, open, dismiss and RSVP
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
    // Called on each resume; the host jumps to today when the day changed while away
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
    // Month pager, about 100 years each direction
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

    // System back closes search
    BackHandler(enabled = uiState.isSearchActive) {
        onSearchClose()
    }

    // Bumped on each resume so today-dependent values (for example today highlights, past
    // dimming and the agenda week bar) recompute
    var refreshKey by remember { mutableIntStateOf(0) }

    LifecycleResumeEffect(Unit) {
        refreshKey++
        onResume()
        onPauseOrDispose { }
    }

    // Today's date, re-read on resume through refreshKey
    val todayCal = remember(refreshKey) { JavaCalendar.getInstance() }
    val todayYear = todayCal.get(JavaCalendar.YEAR)
    val todayMonth = todayCal.get(JavaCalendar.MONTH)

    // Wall-clock snapshot for past-event dimming in the day-events pager. Re-read on resume so
    // an event that ended while the app was in the background dims when the user returns,
    // without a sync write or a swipe.
    val (nowMs, todayDayCode) = remember(refreshKey) {
        val now = System.currentTimeMillis()
        now to DateTimeUtils.eventTsToDayCode(now, isAllDay = false)
    }

    // Agenda list scroll state is hoisted above the top bar so its title can show the month of
    // the topmost visible agenda item. Falls back to today's month when the list is empty or
    // the key doesn't parse.
    val agendaListState = rememberLazyListState()
    val agendaTitleMonth by remember(refreshKey) {
        // Fallback date captured per refreshKey, like the top bar's `today`, so an empty
        // agenda's title matches the rest of the bar.
        val fallbackDate = LocalDate.now()
        derivedStateOf {
            val firstKey = agendaListState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
            AgendaTitleMonth.monthYearFromItemKey(firstKey, fallbackDate)
        }
    }

    // Agenda week-bar state. The selected day (null for none) changes only on a tap and resets
    // on resume. While a tap-driven scroll animates, the bar holds the tapped week
    // (agendaBarSuppressed and agendaBarHeldAnchor) so it doesn't flicker through the weeks in
    // between; otherwise it tracks the topmost visible list item.
    var agendaSelectedDayCode by rememberSaveable(refreshKey) { mutableStateOf<Int?>(null) }
    var agendaBarSuppressed by remember { mutableStateOf(false) }
    var agendaBarHeldAnchor by remember { mutableStateOf<LocalDate?>(null) }
    // Bumped per tap; only the latest tap's coroutine clears the suppression, so the first
    // tap's cancelled coroutine can't clear it during a second tap's scroll.
    var agendaTapGeneration by remember { mutableIntStateOf(0) }
    // The agenda list's top contentPadding lets the item above the first fully visible one
    // peek into it, so the anchor skips peekers; otherwise tapping a week's first day snaps the
    // bar to the week before. Read from AGENDA_CONTENT_PADDING so it matches the list's inset.
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

    val searchFocusRequester = remember { FocusRequester() }

    // Focus the search field when search opens
    LaunchedEffect(uiState.isSearchActive) {
        if (uiState.isSearchActive) {
            try {
                searchFocusRequester.requestFocus()
            } catch (_: Exception) {
                // The field may not be attached yet
            }
        }
    }

    // Declared before the snackbar effect, which routes to the hub's own host while the hub is
    // up (hubSnackbarHostState).
    var showHub by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    // A second host mounted on the account-hub overlay. The hub is an opaque Surface above the
    // Scaffold, so a snackbar on the Scaffold's host would render hidden behind it. While the
    // hub is up (the app-lock toggle confirming, for example) messages go to this host.
    val hubSnackbarHostState = remember { SnackbarHostState() }
    val viewActionLabel = stringResource(R.string.action_view)

    // Show pending snackbar messages
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

    // Open a pending URL from an error action
    LaunchedEffect(uiState.pendingUrlToOpen) {
        uiState.pendingUrlToOpen?.let { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
            onClearPendingUrl()
        }
    }

    // Report the settled month page to the ViewModel
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

    // Today button: user-initiated, so the scroll animates
    LaunchedEffect(uiState.pendingNavigateToToday) {
        if (uiState.pendingNavigateToToday) {
            pagerState.animateScrollToPage(initialPage)
            onClearNavigateToToday()
        }
    }

    // Cold-start landing: jump at once so the pager settles in one frame, with no running
    // animation for concurrent writes to fight
    LaunchedEffect(uiState.pendingNavigateToTodayInstant) {
        if (uiState.pendingNavigateToTodayInstant) {
            pagerState.scrollToPage(initialPage)
            onClearNavigateToTodayInstant()
        }
    }

    // Month picked in the year overlay
    LaunchedEffect(uiState.pendingNavigateToMonth) {
        uiState.pendingNavigateToMonth?.let { (targetYear, targetMonth) ->
            val monthsDiff = (targetYear - todayYear) * 12 + (targetMonth - todayMonth)
            val targetPage = initialPage + monthsDiff
            pagerState.scrollToPage(targetPage)
            onClearNavigateToMonth()
        }
    }

    // Year overlay for quick month navigation, opened from the month title
    YearOverlay(
        visible = uiState.showYearOverlay,
        currentYear = uiState.viewingYear,
        currentMonth = uiState.viewingMonth,
        onMonthSelected = onMonthSelected,
        onDismiss = onYearOverlayDismiss
    )

    val drawerScope = rememberCoroutineScope()
    var showJumpToDatePicker by rememberSaveable { mutableStateOf(false) }
    // App-permissions full-screen destination, opened over the hub. It has its own back arrow
    // and BackHandler; this flag drives its opaque overlay below.
    var showAppPermissions by rememberSaveable { mutableStateOf(false) }
    // Tracks the hub overlay through its enter and exit slides. The drawer edge-swipe
    // suppression below must stay on while the Surface animates out, so it keys off this
    // state, which reports gone only once the exit slide finishes; showHub turns false the
    // moment back is pressed.
    val hubTransition = remember { MutableTransitionState(false) }
    hubTransition.targetState = showHub
    val hubFullyHidden = hubTransition.isIdle && !hubTransition.currentState

    // View mode and date captured before Jump to date or a week/3-day day-header drill-in
    // navigates, so a back press restores both. Null when nothing is pending; cleared once
    // consumed.
    var preJumpViewMode by rememberSaveable { mutableStateOf<ViewMode?>(null) }
    var preJumpDate by rememberSaveable { mutableStateOf(0L) }

    ModalNavigationDrawer(
        drawerState = drawerState ?: rememberDrawerState(DrawerValue.Closed),
        // No edge-swipe while the hub overlay is up or still sliding out, so a swipe can't pull
        // the calendar drawer over it.
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
            // Insights is a read-only analytics view, so it has no create button.
            if (uiState.viewMode != ViewMode.INSIGHTS) {
                FloatingActionButton(
                    onClick = onCreateEvent,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_create_event))
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
                        isRefreshing = uiState.showRefreshSpinner,
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
                                    // Set selectedDate before switching view: the switch to MONTH
                                    // moves the pager to selectedDate's month.
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
                                // Today in the agenda scrolls the list to the top
                                LaunchedEffect(uiState.pendingScrollAgendaToTop) {
                                    if (uiState.pendingScrollAgendaToTop) {
                                        agendaListState.animateScrollToItem(0)
                                        onClearScrollAgendaToTop()
                                    }
                                }

                                when (uiState.viewMode) {
                                    ViewMode.AGENDA -> {
                                        // Pinned week bar above the list, collapsed and expanded by
                                        // the title chevron (persisted). Tapping a date selects it
                                        // and scrolls the list to that day's header; otherwise the
                                        // bar tracks the scrolled week.
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
                                                            // Only the latest tap clears
                                                            // suppression (agendaTapGeneration).
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
                                                    // The spinner has no text; a spoken label in a
                                                    // polite live region makes TalkBack say
                                                    // "Loading events".
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
                                        // Day view has a pinned week strip above the grid, toggled
                                        // by the title chevron (persisted). Tapping a date moves
                                        // the day pager to it. The strip reads the same
                                        // weekViewPagerPosition as the title, so the two stay in
                                        // step. It renders only on a settled day-scale page: the
                                        // default (0) and a stale week-scale page after a WEEK to
                                        // DAY switch would flash a wrong date (isSettledDayPage).
                                        if (uiState.viewMode == ViewMode.DAY &&
                                            uiState.dayWeekBarExpanded &&
                                            WeekViewUtils.isSettledDayPage(uiState.weekViewPagerPosition)
                                        ) {
                                            // Keyed on its inputs so the date arithmetic and 7-day
                                            // list aren't rebuilt on unrelated recompositions
                                            // (event loads, scroll). A plain remember: unlike the
                                            // agenda bar's anchor, it doesn't depend on layout.
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
                                                // Inset past the grid's time-axis gutter so the
                                                // strip lines up with the day column below, like
                                                // the multi-day views' headers.
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = WeekViewUtils.TIME_COLUMN_WIDTH)
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
                                            weekLabelPrefix = stringResource(R.string.label_week),
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
                                                // Snapshot the view and date before drilling
                                                // into DAY, the same way Jump to date does, so
                                                // a back press restores the week or 3-day view
                                                // and date. The drill-in doesn't change the
                                                // startup view.
                                                //
                                                // The week and 3-day grids keep their position
                                                // in weekViewPagerPosition and don't write
                                                // selectedDate, so the shown date comes from
                                                // the pager page: week pages for WEEK, day
                                                // pages for 3-day. Restoring selectedDate
                                                // would jump back to a stale week or, at cold
                                                // start, to today.
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

                                // The grid fits the screen (weighted rows), so nothing in it
                                // scrolls vertically. Material3 pull-to-refresh is driven by
                                // nested scroll and sees no drag unless a descendant passes
                                // vertical deltas up. This scrollable consumes nothing, so it
                                // hands the vertical gesture to pull-to-refresh without moving
                                // the grid; the HorizontalPager takes only the horizontal axis.
                                val monthFullScrollDonor = rememberScrollableState { 0f }
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .scrollable(
                                            orientation = Orientation.Vertical,
                                            state = monthFullScrollDonor
                                        ),
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
                            // Prevents a one-frame flicker on entering MONTH: the pager
                            // re-enters composition at its saved, stale page, and the
                            // LaunchedEffect that scrolls to the right page runs after the
                            // first draw. Content stays hidden until the scroll lands. This
                            // applies only on a fresh entry (isFirstComposition resets when
                            // this branch is disposed); in-view navigation (year overlay,
                            // Today) finds isFirstComposition already false.
                            var isFirstComposition by remember { mutableStateOf(true) }
                            LaunchedEffect(uiState.pendingNavigateToMonth) {
                                if (uiState.pendingNavigateToMonth == null) {
                                    isFirstComposition = false
                                }
                            }
                            val hideForTransition = isFirstComposition && uiState.pendingNavigateToMonth != null

                            // Month pager page, shared by portrait and landscape
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

                            // Day pager state sits above the landscape/portrait branch so it
                            // survives orientation changes.
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
                                // Landscape: month grid on the left, day events on the right
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha(transitionAlpha)
                                ) {
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
                                    // Day events sheet, set off from the grid by tone and corner
                                    // radius
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
                                // Portrait: month grid on top, day events below
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
                                    // Day events sheet, set off from the grid by tone and corner
                                    // radius
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
                // The indicator renders only when isConfigured, so a device-calendar-only user
                // never sees a spinner from a replayed sync status, even though
                // canPullToRefresh already disables the gesture.
                if (uiState.isConfigured) {
                    PullToRefreshDefaults.Indicator(
                        state = pullToRefreshState,
                        isRefreshing = uiState.showRefreshSpinner,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
        }
    }

    if (uiState.showSearchDatePicker) {
        SearchDatePickerSheet(
            selectedDateMs = uiState.searchDateRangeStart,
            onDateSelected = onSearchDateSelected,
            onDismiss = onSearchHideDatePicker,
            firstDayOfWeek = uiState.firstDayOfWeek
        )
    }

    // Date picker for the 3-day and week views
    if (uiState.showWeekViewDatePicker) {
        WeekViewDatePickerSheet(
            currentWeekStartMs = System.currentTimeMillis(),
            onDateSelected = onWeekDateSelected,
            onDismiss = onWeekDatePickerDismiss,
            firstDayOfWeek = uiState.firstDayOfWeek
        )
    }

    // Jump to date uses the 3-day and week views' sheet, so one tap navigates. It switches to DAY
    // before navigating so the pager page is computed for day pages, not week pages.
    if (showJumpToDatePicker) {
        WeekViewDatePickerSheet(
            currentWeekStartMs = uiState.selectedDate.takeIf { it != 0L } ?: System.currentTimeMillis(),
            onDateSelected = { dateMs ->
                preJumpViewMode = uiState.viewMode
                preJumpDate = uiState.selectedDate
                onViewSelect(ViewMode.DAY)
                onWeekDateSelected(dateMs)
                showJumpToDatePicker = false
                // Picking a date moves the calendar, so the hub, still mounted behind the
                // picker, closes to show the result.
                showHub = false
            },
            onDismiss = { showJumpToDatePicker = false },
            firstDayOfWeek = uiState.firstDayOfWeek
        )
    }

    // Back after a Jump to date or a day-header drill-in restores the view and date from before
    // it. Single-shot: it clears the snapshot, so a second back press falls through.
    val pending = preJumpViewMode
    BackHandler(enabled = pending != null && !showJumpToDatePicker && !showHub) {
        onViewSelect(pending!!)
        if (preJumpDate != 0L) onWeekDateSelected(preJumpDate)
        preJumpViewMode = null
        preJumpDate = 0L
    }

    // The account hub: a full-screen destination drawn as an opaque overlay above the
    // Scaffold, so it covers the calendar's top bar and FAB. The top-bar `when` and the FAB's
    // viewMode gate don't see a boolean flag, so the overlay owns coverage.
    // It must stay a Surface, not a bare Box: its pointer-input barrier stops taps reaching
    // the FAB behind it, while gesturesEnabled = hubFullyHidden above stops the drawer
    // edge-swipe. Keep both in a refactor.
    // AnimatedVisibility slides the hub in from the trailing edge (the avatar sits in the
    // trailing corner, so this reads as drilling in) and reverses on back; the Surface stays
    // mounted through the exit slide, so the FAB stays covered. It's driven by visibleState so
    // hubFullyHidden can see the exit finish. The offset is negated in RTL so the hub still
    // enters from the trailing edge.
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
                // Destinations that open a sheet or Activity on top of the hub keep it
                // mounted: the destination covers it, so the bare calendar doesn't flash,
                // and dismissing the destination returns to the hub. Only Jump to date
                // changes the calendar; the hub closes when a date is picked (above).
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
                // App-lock enable, enroll and unsupported messages show while the hub is up;
                // hosted here so they aren't hidden behind the overlay.
                snackbarHost = { SnackbarHost(hostState = hubSnackbarHostState) },
            )
        }
    }

    // App permissions: an opaque full-screen overlay above the hub, which stays mounted
    // beneath, the way the hub covers the calendar. Its own back arrow and BackHandler return
    // to the hub.
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

    if (uiState.isInvitationInboxOpen) {
        InvitationInboxSheet(
            invitations = pendingInvitations,
            timePattern = timePattern,
            onRsvp = onRsvpFromInbox,
            onDismiss = onDismissInvitationInbox
        )
    }

    // Day events sheet for the full-height month view
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

    // Recurring scope sheets: drag-to-reschedule, form save and delete share one component.
    // Each flow's options come from its helper in the ViewModel layer.
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
            blockedScopes = org.onekash.kashcal.ui.viewmodels.dragScopesToGrey(pending),
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
            pending.toScopeContext()
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
            val yearLabel = stringResource(R.string.view_year)
            // The agenda title follows the topmost visible day's month; other views use their
            // viewing month. The formatter reads viewingYear and viewingMonth, so only AGENDA
            // substitutes the scroll-derived month.
            val isAgenda = uiState.viewMode == ViewMode.AGENDA
            val titleText = TopBarTitleFormatter.format(
                viewMode = uiState.viewMode,
                viewingYear = if (isAgenda) agendaTitleMonth.first else uiState.viewingYear,
                viewingMonth = if (isAgenda) agendaTitleMonth.second else uiState.viewingMonth,
                weekViewPagerPosition = uiState.weekViewPagerPosition,
                firstDayOfWeek = uiState.firstDayOfWeek,
                yearLabel = yearLabel,
                today = today,
            )
            // AGENDA and DAY show a collapsible week bar, toggled by the title chevron. The other
            // views use a plain title (see the else branch).
            val showWeekBarChevron = uiState.viewMode == ViewMode.AGENDA || uiState.viewMode == ViewMode.DAY
            val weekBarExpanded = if (uiState.viewMode == ViewMode.DAY) {
                uiState.dayWeekBarExpanded
            } else {
                uiState.agendaWeekBarExpanded
            }
            val titleFontSize = 20.sp
            CenterAlignedTopAppBar(
                title = {
                    if (showWeekBarChevron) {
                        // The chevron points up when the week bar is expanded (tap to collapse)
                        // and down when collapsed (tap to expand).
                        val weekBarChevronRotation by animateFloatAsState(
                            targetValue = if (weekBarExpanded) 180f else 0f,
                            animationSpec = tween(300),
                            label = "weekBarChevronRotation"
                        )
                        // onClickLabel describes the toggle to TalkBack, while the title text
                        // ("July 2026") stays the node's spoken content.
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
                        // YEAR has no title action; MONTH and MONTH_FULL open the year overlay,
                        // THREE_DAYS and WEEK the date picker.
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
                                // Announces going offline to TalkBack.
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
 * Shows the top-bar avatar that opens the account hub: the user's initials, or a neutral glyph
 * when unset, with a count badge when [pendingInvitesCount] > 0. Tapping calls [onClick]. The
 * accessibility label comes from [overflowContentDescription], so the announcement and badge
 * never disagree on the count, and TalkBack still announces it as "More menu".
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
            // Sized against the 26.dp Menu and Search glyphs: the disc is slightly larger, but
            // its glyph or monogram is inset, so it reads at about the same visual weight.
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
                                    // The today fill can wash out against the surface for pale
                                    // accent seeds; a hairline outline keeps the cell visible on
                                    // any theme. Selected uses a strong fill and needs no border.
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
 * Shows the day events pager below the month grid: a swipe moves to the next or previous day,
 * a user swipe updates the selected date, and crossing a month boundary moves the month pager.
 * When the selected date isn't in the viewing month it shows a "pick a day" message instead.
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

    // Breaks the settle and selectedDate feedback loop (#267): only a settle that ends a user
    // swipe may push up to selectedDate. A programmatic scroll (grid tap, Today, cold start)
    // emits no drag, so its settle is suppressed and rapid taps can't oscillate.
    val syncCoordinator = rememberDayPagerSyncCoordinator(dayPagerState.interactionSource)

    // Day pager settled: update selectedDate, and move the month pager if the month changed
    LaunchedEffect(dayPagerState.settledPage) {
        val newDateMs = DayPagerUtils.pageToDateMs(dayPagerState.settledPage, todayMs)

        // Consume the drag intent on every settle so it can't carry over to a later
        // programmatic settle, then update selectedDate only for a user settle (#267).
        val isUserSettle = syncCoordinator.shouldPropagateSettle()
        if (newDateMs != uiState.selectedDate && isUserSettle) {
            onDateSelected(newDateMs)
        }

        if (shouldRefreshCache(newDateMs)) {
            onLoadEventsForRange(newDateMs)
        }

        // Move the month pager when the day crossed into another month
        val newCal = JavaCalendar.getInstance().apply { timeInMillis = newDateMs }
        val newYear = newCal.get(JavaCalendar.YEAR)
        val newMonth = newCal.get(JavaCalendar.MONTH)

        if (newYear != uiState.viewingYear || newMonth != uiState.viewingMonth) {
            val monthsDiff = (newYear - todayYear) * 12 + (newMonth - todayMonth)
            monthPagerState.scrollToPage(monthPagerInitialPage + monthsDiff)
        }
    }

    // Report visible event IDs for the settled page only. The pager composes neighbour pages
    // ahead (beyondViewportPageCount), so reporting per page would race and the last neighbour
    // would win.
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

    // selectedDate changed (a grid tap, for example): jump the day pager, without animation so it
    // can't race the settle effect above
    LaunchedEffect(uiState.selectedDate) {
        if (uiState.selectedDate != 0L) {
            val targetPage = DayPagerUtils.dateToPage(uiState.selectedDate, todayMs)
            if (targetPage != dayPagerState.currentPage) {
                dayPagerState.scrollToPage(targetPage)
            }
        }
    }

    // Initial load when no range is cached yet
    LaunchedEffect(Unit) {
        if (uiState.cacheRangeCenter == 0L && uiState.selectedDate != 0L) {
            onLoadEventsForRange(uiState.selectedDate)
        }
    }

    // Whether the selected date is in the viewing month; if not, show the pick-a-day message
    val selectedCal = remember(uiState.selectedDate) {
        JavaCalendar.getInstance().apply { timeInMillis = uiState.selectedDate }
    }
    val isSelectedInViewingMonth = selectedCal.get(JavaCalendar.MONTH) == uiState.viewingMonth &&
        selectedCal.get(JavaCalendar.YEAR) == uiState.viewingYear

    if (!isSelectedInViewingMonth) {
        // For example after swiping the month pager without picking a day
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

/** Shows one day page of the day pager: a spinner, the empty-day message, or the event cards. */
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
            // Rotate the empty-day line per calendar day, so it varies between days but is
            // stable across recomposition. Keyed on the epoch day, so it cycles without repeats
            // at month or year boundaries.
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

    // For a Room recurring event, show "Next: date" from displayTs
    val dateString = remember(searchResult, timePattern) {
        when (displayEvent) {
            is DisplayEvent.Room -> {
                // displayTs differs from the event start for a recurring event with a later
                // occurrence
                val nextOccTs = searchResult.displayTs.takeIf { it != displayEvent.event.startTs }
                formatSearchResultDateWithOccurrence(displayEvent.event, nextOccTs, timePattern = timePattern)
            }
            is DisplayEvent.Device -> {
                formatSearchResultDate(displayEvent, timePattern = timePattern)
            }
        }
    }

    // Title with the age for birthday events and the optional emoji
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
 * Shows search results under four date filter chips: All, Week, Month and a date picker.
 * The chips sit outside the LazyColumn to avoid a crash, in a plain Row without horizontalScroll.
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
        // Four chips fit without scrolling
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
            // Date chip: shows the picked date or range, else the "Date" label
            val isCustom = currentFilter is DateFilter.SingleDay || currentFilter is DateFilter.CustomRange
            val dateLabel = stringResource(R.string.filter_date)
            FilterChip(
                selected = isCustom,
                onClick = onCustomDateClick,
                label = { Text(if (isCustom) currentFilter.displayName else dateLabel) }
            )
        }

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
                    // Room exceptions count as recurring; recurring results never dim as past
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
 * Padding around the agenda list. The top inset is also the peek threshold the week bar uses
 * to skip an item bleeding into the padding (the anchor derivation in [HomeScreen]); both read
 * it from here so they can't drift.
 */
private val AGENDA_CONTENT_PADDING = 16.dp

/**
 * Shows the agenda list: the upcoming 90 days of occurrences, grouped under date headers, one
 * card per occurrence. A multi-day event appears on each day it spans with a "Day X of Y" line.
 * Today's and tomorrow's headers add "Today" or "Tomorrow" beside the full date.
 *
 * The caller precomputes the expansion, dedup and grouping into [model], so the same grouping
 * and header-index map drive both this list and the week bar's tap-to-scroll. [todayDayCode]
 * is the one today reference shared with the header formatter and the week bar.
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
                        // Two-tone: accent the relative word, mute the rest. The join and
                        // accent range survive reordering (AgendaDayHeader.joinedHeader), so
                        // date-first locales work.
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
 * Shows one agenda card from the [DisplayEvent] common properties, with "Day X of Y" for a
 * multi-day event.
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

    // Title with the age for birthday events and the optional emoji
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
 * Formats an event's time line: the time range, "All day", or "Day X of Y" for a multi-day
 * event, with a recurring marker for a series or exception. This overload writes English text;
 * the overload taking `resources` uses string resources.
 *
 * @param selectedDateMillis the day being shown, which picks the day number of a multi-day event.
 * @param zoneId zone for the conversion; injectable for tests.
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
        // Single-day event, with the recurring marker for a series or exception
        val recurringIndicator = if (event.isRecurring || event.isException) " \uD83D\uDD01" else ""
        return if (event.isAllDay) "All day$recurringIndicator"
        else {
            val startTime = Instant.ofEpochMilli(event.startTs).atZone(zoneId).format(timeFormatter)
            val endTime = Instant.ofEpochMilli(event.endTs).atZone(zoneId).format(timeFormatter)
            "$startTime - $endTime$recurringIndicator"
        }
    }

    // Multi-day event
    val totalDays = DateTimeUtils.calculateTotalDays(event.startTs, event.endTs, event.isAllDay, zoneId)
    // selectedDateMillis is always local time, for all-day events too
    val currentDay = calculateCurrentDayForEvent(event.startTs, selectedDateMillis, event.isAllDay, zoneId)
        .coerceIn(1, totalDays)
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
): String {
    val dateFormatter = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMMd"), Locale.getDefault())
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())

    val startDate = DateTimeUtils.eventTsToLocalDate(event.startTs, event.isAllDay, zoneId)
    val displayEndDate = DateTimeUtils.eventTsToLocalDate(event.endTs, event.isAllDay, zoneId)
    val isMultiDay = DateTimeUtils.spansMultipleDays(event.startTs, event.endTs, event.isAllDay, zoneId)
    // An exception has originalEventId but no rrule
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
 * Formats a device calendar search result's date line like the [Event] overload, from the
 * [DisplayEvent] common properties.
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
 * Formats a search result's date line with the next occurrence for a series or exception with
 * [nextOccurrenceTs]: "Next: Jan 15, 2025 🔁", or "Next: Jan 15, 2025 · 9:00 AM 🔁" when timed.
 * Anything else falls back to [formatSearchResultDate].
 *
 * @param nextOccurrenceTs the next occurrence's start, or null.
 * @param zoneId zone for the date calculations; injectable for tests.
 * @param timePattern time pattern; the default "h:mm a" is 12-hour.
 */
internal fun formatSearchResultDateWithOccurrence(
    event: Event,
    nextOccurrenceTs: Long?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    timePattern: String = "h:mm a"
): String {
    val isRecurring = event.isRecurring || event.isException

    if (!isRecurring || nextOccurrenceTs == null) {
        return formatSearchResultDate(event, zoneId, timePattern)
    }

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
 * Shows the search date sheet for picking a single day or a date range, with
 * InlineDatePickerContent. Each tap goes to [onDateSelected]; the ViewModel decides:
 * - First tap: stores the range start, which comes back as [selectedDateMs]
 * - Second tap on the same day: a SingleDay filter
 * - Second tap on another day: a CustomRange filter
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

    // Remembered so the fallback to now doesn't change on every frame of the sheet animation,
    // which causes jank
    val stableSelectedDate = remember(selectedDateMs) {
        selectedDateMs ?: System.currentTimeMillis()
    }

    // Displayed month; opens on the selected date's month, or the current month
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

// ==================== Week View Date Picker ====================

/**
 * Shows the go-to-date sheet used by the 3-day and week views and by Jump to date. One tap
 * calls [onDateSelected] with the date and closes the sheet.
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

    // Remembered so the fallback to now doesn't change on every frame, which causes jank
    val stableCurrentDate = remember(currentWeekStartMs) {
        if (currentWeekStartMs != 0L) currentWeekStartMs else System.currentTimeMillis()
    }

    // Displayed month; opens on the shown date's month
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
            Text(
                text = stringResource(R.string.label_go_to_date),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

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
