package org.onekash.kashcal.ui.screens

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.domain.model.LocalCalendarSummary
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionState
import org.onekash.kashcal.ui.screens.settings.AccountFeedsSheetState
import org.onekash.kashcal.ui.screens.settings.IcsSubscriptionUiModel
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.ui.viewmodels.AccountSettingsViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Mounts the production [SettingsRoute], which collects the view-model flows and binds each row
 * to a `viewModel::method`, over a mockk of [AccountSettingsViewModel], and checks for a few rows
 * that:
 *  - a flow value set on the VM reaches the visible row (the widget limit), and
 *  - driving the row calls its VM setter with the picked value while the listed sibling setters
 *    stay silent (widget limit, show week numbers, detailed widget rows, sync frequency), and
 *  - a permission control on the route launches its request (Device calendars Allow asks for
 *    read and write calendar access, read back from the host activity's requested permissions;
 *    Robolectric never answers, so the result handling is not exercised here).
 *
 * Because the route's binding block runs here, a swap inside it (wiring the widget-limit row to
 * `setShowWeekNumbers`) is caught. The data-bearing flow getters are stubbed explicitly so a wrong
 * return can't pass silently; only the Unit setters use `relaxUnitFun` (they launch a coroutine),
 * which keeps `verify` able to see the call. There is no `confirmVerified(vm)`: the route reads
 * over 40 flows during composition, so a whole-mock confirm would have to list every getter and
 * would break on each new flow, without catching a swap the `exactly = 0` checks miss.
 *
 * Runs under Robolectric; run the class in isolation because of the repo's multi-class
 * native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class AccountSettingsScreenViewModelWiringTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = org.robolectric.RuntimeEnvironment.getApplication()

    private var originalLocale: Locale? = null

    /** The activity hosting the route, read for the permissions its launchers request. */
    private lateinit var hostActivity: ComponentActivity

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
    }

    private val localCalendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = LocalCalendarInitializer.LOCAL_CALENDAR_URL,
        displayName = "Local",
        color = 0xFF9E9E9E.toInt(),
        isVisible = true,
    )

    /**
     * Builds a VM mockk with every flow getter the route reads stubbed explicitly and Unit setters
     * relaxed. [AccountSettingsViewModel.widgetMaxEventsPerDay] and
     * [AccountSettingsViewModel.showWeekNumbers] come from the arguments; the rest carry fixed
     * values so the screen renders normally.
     */
    private fun mockVm(widget: Int, weekNumbers: Boolean): AccountSettingsViewModel {
        val vm = mockk<AccountSettingsViewModel>(relaxUnitFun = true)
        // Theme flows (cold Flow, seeded with initialValue in the route).
        every { vm.themeMode } returns flowOf(ThemeMode.SYSTEM)
        every { vm.colorSource } returns flowOf(ColorSource.DYNAMIC)
        every { vm.accentSeed } returns flowOf(0)
        // UI-state flows.
        every { vm.uiState } returns MutableStateFlow(
            AccountSettingsUiState(iCloudState = ICloudConnectionState.NotConnected())
        )
        every { vm.calendars } returns MutableStateFlow(listOf(localCalendar))
        every { vm.calendarGroups } returns MutableStateFlow(emptyList<CalendarGroup>())
        every { vm.defaultCalendar } returns MutableStateFlow<DefaultCalendar?>(null)
        every { vm.offlineCalendars } returns MutableStateFlow(emptyList<LocalCalendarSummary>())
        every { vm.offlineDefaultCalendarId } returns MutableStateFlow<Long?>(null)
        every { vm.writableDeviceCalendarGroups } returns MutableStateFlow(emptyList<CalendarGroup>())
        every { vm.syncIntervalMs } returns MutableStateFlow(24 * 60 * 60 * 1000L)
        every { vm.subscriptions } returns MutableStateFlow(emptyList<IcsSubscriptionUiModel>())
        every { vm.subscriptionSyncing } returns MutableStateFlow(false)
        every { vm.hasFeedAccounts } returns MutableStateFlow(false)
        every { vm.accountFeedsSheet } returns MutableStateFlow<AccountFeedsSheetState?>(null)
        every { vm.defaultReminderTimed } returns MutableStateFlow(15)
        every { vm.defaultReminderAllDay } returns MutableStateFlow(900)
        every { vm.defaultEventDuration } returns MutableStateFlow(30)
        every { vm.showEventEmojis } returns MutableStateFlow(true)
        every { vm.quickAddEnabled } returns MutableStateFlow(false)
        every { vm.titleSuggestionsEnabled } returns MutableStateFlow(true)
        every { vm.changeNotificationsOn } returns MutableStateFlow(false)
        every { vm.timeFormat } returns MutableStateFlow(KashCalDataStore.TIME_FORMAT_SYSTEM)
        every { vm.firstDayOfWeek } returns MutableStateFlow(java.util.Calendar.SUNDAY)
        every { vm.showWeekNumbers } returns MutableStateFlow(weekNumbers)
        every { vm.showMultiDayTimedInAllDayStrip } returns MutableStateFlow(true)
        every { vm.widgetMaxEventsPerDay } returns MutableStateFlow(widget)
        every { vm.widgetDetailedRows } returns MutableStateFlow(false)
        every { vm.syncLookbackDays } returns MutableStateFlow(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS)
        every { vm.isSearchActive } returns MutableStateFlow(false)
        every { vm.searchQuery } returns MutableStateFlow("")
        every { vm.contactBirthdaysEnabled } returns MutableStateFlow(false)
        every { vm.contactBirthdaysColor } returns MutableStateFlow(0)
        every { vm.contactBirthdaysReminder } returns MutableStateFlow(0)
        every { vm.hasContactsPermission } returns MutableStateFlow(false)
        every { vm.hasContactsSyncPermission } returns MutableStateFlow(false)
        every { vm.contactSyncPermissionNeeded } returns MutableStateFlow(false)
        every { vm.birthdayCount } returns MutableStateFlow(0)
        every { vm.contactAnniversariesEnabled } returns MutableStateFlow(false)
        every { vm.contactAnniversariesColor } returns MutableStateFlow(0)
        every { vm.contactAnniversariesReminder } returns MutableStateFlow(0)
        every { vm.anniversaryCount } returns MutableStateFlow(0)
        every { vm.deviceCalendarsEnabled } returns MutableStateFlow(false)
        every { vm.hasReadCalendarPermission } returns MutableStateFlow(false)
        every { vm.hasWriteCalendarPermission } returns MutableStateFlow(false)
        every { vm.deviceCalendars } returns MutableStateFlow(emptyList<DeviceCalendar>())
        every { vm.enabledDeviceCalendarIds } returns MutableStateFlow(emptySet<Long>())
        every { vm.showDeclinedEvents } returns MutableStateFlow(false)
        every { vm.deviceCalendarRemindersEnabled } returns MutableStateFlow(true)
        every { vm.hiddenDeviceCalendarIds } returns MutableStateFlow(emptySet<Long>())
        every { vm.mutedDeviceCalendarIds } returns MutableStateFlow(emptySet<Long>())
        every { vm.calendarNotificationsBlockedBy } returns MutableStateFlow(null)
        every { vm.backupRestoreState } returns MutableStateFlow(BackupRestoreUiState.Idle)
        // The route collects the permission state at the top for the ICS subscription dialog;
        // the hint flow is collected only inside the CalDAV sheet (not shown here).
        every { vm.localNetworkPermissionState } returns
            MutableStateFlow(LocalNetworkPermissionState.NotRequested)
        every { vm.localNetworkHintActive } returns MutableStateFlow(false)
        return vm
    }

    private fun setRoute(
        vm: AccountSettingsViewModel,
        onExportDebugReport: () -> Unit = {},
        openDeviceCalendarsInitially: Boolean = false,
        onFinish: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            hostActivity = LocalContext.current as ComponentActivity
            SettingsRoute(
                viewModel = vm,
                initialThemeMode = ThemeMode.SYSTEM,
                initialColorSource = ColorSource.DYNAMIC,
                initialAccentSeed = 0,
                syncSessionStore = mockk<SyncSessionStore>(relaxed = true),
                // The five I/O lambdas have no defaults; the rows under test never invoke
                // them, so throwaway stubs suffice.
                readIcsContent = { Result.failure(UnsupportedOperationException()) },
                importIcsToRoom = { _, _ -> error("not used") },
                previewIcsInRoom = { _, _ -> error("not used") },
                writeBackup = { _, _ -> },
                readBackup = { error("not used") },
                onExportDebugReport = onExportDebugReport,
                openDeviceCalendarsInitially = openDeviceCalendarsInitially,
                onFinish = onFinish,
            )
        }
    }

    @Test
    fun `Settings launched on Device calendars opens there and its Back finishes the activity`() {
        val vm = mockVm(widget = 8, weekNumbers = false)
        var finishes = 0
        setRoute(vm, openDeviceCalendarsInitially = true, onFinish = { finishes++ })

        composeTestRule.onNodeWithText(
            context.getString(R.string.device_calendars_intro)
        ).assertExists()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.cd_back))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, finishes)
    }

    @Test
    fun `Allow on Device calendars requests read and write calendar access through the route's launcher`() {
        val vm = mockVm(widget = 8, weekNumbers = false)
        setRoute(vm, openDeviceCalendarsInitially = true)

        composeTestRule.onNodeWithText(context.getString(R.string.action_allow)).performClick()
        composeTestRule.waitForIdle()

        val requested = Shadows.shadowOf(hostActivity).lastRequestedPermission
        assertNotNull("Allow should request a permission", requested)
        assertEquals(
            setOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            requested!!.requestedPermissions.toSet(),
        )
        verify(exactly = 0) { vm.onToggleDeviceCalendars(any()) }
    }

    @Test
    fun `Settings opened normally returns to its list from Device calendars`() {
        val vm = mockVm(widget = 8, weekNumbers = false)
        var finishes = 0
        setRoute(vm, onFinish = { finishes++ })

        composeTestRule.onNodeWithText(context.getString(R.string.settings_device_calendars))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.cd_back))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, finishes)
    }

    @Test
    fun `share report reaches the route's export lambda once`() {
        val vm = mockVm(widget = 8, weekNumbers = false)
        var exports = 0
        setRoute(vm, onExportDebugReport = { exports++ })

        composeTestRule.onNodeWithText("Export debug report").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Share report").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, exports)
        verify(exactly = 0) { vm.forceFullSync() }
    }

    @Test
    fun `force full sync confirm drives the VM once`() {
        val vm = mockVm(widget = 8, weekNumbers = false)
        setRoute(vm)

        composeTestRule.onNodeWithText("Force full sync").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Sync Now").performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { vm.forceFullSync() }
    }

    @Test
    fun `flow value set on the VM reaches the visible widget-limit row`() {
        val vm = mockVm(widget = 8, weekNumbers = false)
        setRoute(vm)
        // The row renders the VM's flow value, not a hard-coded default.
        composeTestRule.onNodeWithText("8 per day").assertExists()
    }

    @Test
    fun `widget-limit row drives exactly its VM setter and no sibling`() {
        val vm = mockVm(widget = 8, weekNumbers = false)
        setRoute(vm)

        composeTestRule.onNodeWithText("Widget event limit").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("10 per day").performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { vm.setWidgetMaxEventsPerDay(10) }
        // No sibling setter fired.
        verify(exactly = 0) { vm.setShowWeekNumbers(any()) }
        verify(exactly = 0) { vm.onDefaultEventDurationChange(any()) }
        verify(exactly = 0) { vm.onDefaultReminderTimedChange(any()) }
        verify(exactly = 0) { vm.onDefaultReminderAllDayChange(any()) }
        verify(exactly = 0) { vm.onSyncLookbackChange(any()) }
        verify(exactly = 0) { vm.setFirstDayOfWeek(any()) }
        verify(exactly = 0) { vm.setQuickAddEnabled(any()) }
    }

    @Test
    fun `show-week-numbers row drives exactly its VM setter and no sibling`() {
        val vm = mockVm(widget = 8, weekNumbers = false)
        setRoute(vm)

        composeTestRule.onNodeWithText("Show week numbers").performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { vm.setShowWeekNumbers(true) }
        // No sibling setter fired.
        verify(exactly = 0) { vm.setWidgetMaxEventsPerDay(any()) }
        verify(exactly = 0) { vm.setShowEventEmojis(any()) }
        verify(exactly = 0) { vm.setWidgetDetailedRows(any()) }
        verify(exactly = 0) { vm.setQuickAddEnabled(any()) }
        verify(exactly = 0) { vm.setTitleSuggestionsEnabled(any()) }
        verify(exactly = 0) { vm.onToggleShowDeclinedEvents(any()) }
    }

    @Test
    fun `detailed-widget-rows row drives exactly its VM setter and no sibling`() {
        val vm = mockVm(widget = 8, weekNumbers = false)
        setRoute(vm)

        composeTestRule.onNodeWithText("Detailed widget rows").performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { vm.setWidgetDetailedRows(true) }
        // No same-typed (Boolean) sibling toggle fired.
        verify(exactly = 0) { vm.setShowWeekNumbers(any()) }
        verify(exactly = 0) { vm.setShowEventEmojis(any()) }
        verify(exactly = 0) { vm.setQuickAddEnabled(any()) }
        verify(exactly = 0) { vm.setTitleSuggestionsEnabled(any()) }
        verify(exactly = 0) { vm.onToggleShowDeclinedEvents(any()) }
        // Nor the (Int) widget-limit neighbour.
        verify(exactly = 0) { vm.setWidgetMaxEventsPerDay(any()) }
    }

    @Test
    fun `sync-frequency row drives exactly its VM setter and no sibling`() {
        val vm = mockVm(widget = 8, weekNumbers = false)
        setRoute(vm)

        composeTestRule.onNodeWithText("Sync frequency").performClick()
        composeTestRule.waitForIdle()
        // Seed is 24 hours; pick a distinct option ("1 hour" == 3_600_000 ms).
        composeTestRule.onNodeWithText("1 hour").performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { vm.onSyncIntervalChange(60 * 60 * 1000L) }
        // No sibling value setter fired; the (Int) lookback neighbour is the likeliest mis-wire.
        verify(exactly = 0) { vm.onSyncLookbackChange(any()) }
        verify(exactly = 0) { vm.setWidgetMaxEventsPerDay(any()) }
        verify(exactly = 0) { vm.onDefaultEventDurationChange(any()) }
        verify(exactly = 0) { vm.setFirstDayOfWeek(any()) }
    }

    @Test
    fun `calendar notifications switches drive the Room and device mute setters apart`() {
        val vm = mockVm(widget = 8, weekNumbers = false)
        val work = Calendar(
            id = 20L, accountId = 2L, caldavUrl = "https://caldav.example.test/work/", displayName = "Work",
            color = 0xFF4CAF50.toInt(),
        )
        val calendars = listOf(localCalendar, work)
        every { vm.calendars } returns MutableStateFlow(calendars)
        every { vm.calendarGroups } returns MutableStateFlow(
            CalendarGroup.fromCalendarsAndAccounts(
                calendars,
                listOf(
                    org.onekash.kashcal.data.db.entity.Account(
                        id = 1L, provider = org.onekash.kashcal.domain.model.AccountProvider.LOCAL, email = "local"
                    ),
                    org.onekash.kashcal.data.db.entity.Account(
                        id = 2L, provider = org.onekash.kashcal.domain.model.AccountProvider.CALDAV,
                        email = "me@example.test", displayName = "Nextcloud"
                    ),
                ),
                localLabel = "Offline",
                icsLabel = "Calendar Feeds",
            )
        )
        every { vm.deviceCalendarsEnabled } returns MutableStateFlow(true)
        every { vm.hasReadCalendarPermission } returns MutableStateFlow(true)
        every { vm.deviceCalendars } returns MutableStateFlow(
            listOf(
                DeviceCalendar(
                    id = 100L, displayName = "Phone", color = 0, accountName = "", accountType = "LOCAL",
                    visible = true, accessLevel = 700,
                )
            )
        )
        every { vm.enabledDeviceCalendarIds } returns MutableStateFlow(setOf(100L))
        setRoute(vm)

        composeTestRule.onNodeWithText("Calendar notifications").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Work").performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { vm.onToggleCalendarMuted(20L, true) }
        verify(exactly = 0) { vm.onToggleDeviceCalendarMuted(any(), any()) }

        composeTestRule.onNodeWithText("Phone").performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { vm.onToggleDeviceCalendarMuted(100L, true) }
        verify(exactly = 1) { vm.onToggleCalendarMuted(any(), any()) }
    }
}
