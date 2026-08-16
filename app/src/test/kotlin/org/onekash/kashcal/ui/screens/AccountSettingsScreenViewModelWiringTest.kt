package org.onekash.kashcal.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionState
import org.onekash.kashcal.ui.screens.settings.IcsSubscriptionUiModel
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.ui.viewmodels.AccountSettingsViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * A VM-boundary wiring guard for the settings screen: mounts the REAL production
 * [SettingsRoute] — the composable that owns the view-model collection and the
 * flow→param→`viewModel::method` binding block — against the REAL
 * [AccountSettingsViewModel] TYPE (a mockk, not a hand-rolled fake) and proves, for
 * representative rows, that
 *  (a) a flow value set on the VM reaches the visible row, and
 *  (b) driving the row calls the EXACT VM setter with the right value while every
 *      same-typed sibling setter stays silent.
 *
 * The data-bearing flow getters are stubbed EXPLICITLY (never relaxed) so a wrong
 * return can't pass silently; only the Unit-returning setters use `relaxUnitFun`,
 * which is safe (they return Unit and just launch a coroutine) and keeps `verify`
 * able to see the call. This matches the repo's test-double guidance: relaxed for
 * side-effect Unit collaborators, explicit stubs for anything data-bearing.
 *
 * Unlike the earlier iteration, this test mounts [SettingsRoute] itself, so the
 * production binding block IS executed here — a same-typed swap introduced inside the
 * route (e.g. wiring the widget-limit row to `setShowWeekNumbers`) is now caught. We
 * deliberately do not `confirmVerified(vm)`: the route reads ~44 flows during
 * composition, so a whole-mock confirm would force enumerating every getter and would
 * break on any new flow — brittleness that catches no swap the exhaustive same-typed
 * `exactly = 0` assertions below don't already catch.
 *
 * Runs under Robolectric; run the class in isolation given the repo's multi-class
 * native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class AccountSettingsScreenViewModelWiringTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var originalLocale: Locale? = null

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
     * A mockk of the real VM type with EVERY flow getter stubbed explicitly (never
     * relaxed) and Unit setters relaxed. The two flows under test —
     * [AccountSettingsViewModel.widgetMaxEventsPerDay] and
     * [AccountSettingsViewModel.showWeekNumbers] — are parameterized; the rest carry
     * production defaults so the screen renders normally.
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
        every { vm.writableDeviceCalendarGroups } returns MutableStateFlow(emptyList<CalendarGroup>())
        every { vm.syncIntervalMs } returns MutableStateFlow(24 * 60 * 60 * 1000L)
        every { vm.subscriptions } returns MutableStateFlow(emptyList<IcsSubscriptionUiModel>())
        every { vm.subscriptionSyncing } returns MutableStateFlow(false)
        every { vm.defaultReminderTimed } returns MutableStateFlow(15)
        every { vm.defaultReminderAllDay } returns MutableStateFlow(900)
        every { vm.defaultEventDuration } returns MutableStateFlow(30)
        every { vm.showEventEmojis } returns MutableStateFlow(true)
        every { vm.quickAddEnabled } returns MutableStateFlow(false)
        every { vm.titleSuggestionsEnabled } returns MutableStateFlow(true)
        every { vm.timeFormat } returns MutableStateFlow(KashCalDataStore.TIME_FORMAT_SYSTEM)
        every { vm.firstDayOfWeek } returns MutableStateFlow(java.util.Calendar.SUNDAY)
        every { vm.showWeekNumbers } returns MutableStateFlow(weekNumbers)
        every { vm.widgetMaxEventsPerDay } returns MutableStateFlow(widget)
        every { vm.widgetDetailedRows } returns MutableStateFlow(false)
        every { vm.monthWidgetEventTitles } returns MutableStateFlow(false)
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
        every { vm.backupRestoreState } returns MutableStateFlow(BackupRestoreUiState.Idle)
        // Collected only inside the CalDAV sheet (not shown here), stubbed for safety.
        every { vm.localNetworkPermissionState } returns
            MutableStateFlow(LocalNetworkPermissionState.NotRequested)
        every { vm.localNetworkHintActive } returns MutableStateFlow(false)
        return vm
    }

    private fun setRoute(vm: AccountSettingsViewModel) {
        composeTestRule.setContent {
            SettingsRoute(
                viewModel = vm,
                initialThemeMode = ThemeMode.SYSTEM,
                initialColorSource = ColorSource.DYNAMIC,
                initialAccentSeed = 0,
                syncSessionStore = mockk<SyncSessionStore>(relaxed = true),
                // The four I/O lambdas are required; these display/preference rows
                // under test never invoke them, so throwaway stubs suffice.
                readIcsContent = { Result.failure(UnsupportedOperationException()) },
                importIcsToRoom = { _, _ -> 0 },
                writeBackup = { _, _ -> },
                readBackup = { error("not used") },
            )
        }
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
        // No same-typed sibling setter fired.
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
        // No same-typed sibling setter fired.
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
        // No same-typed (Int) neighbour fired either.
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
        // No sibling value-carrying setter fired (the (Int) lookback neighbour is the
        // most likely mis-wire; the rest guard the broader value cluster).
        verify(exactly = 0) { vm.onSyncLookbackChange(any()) }
        verify(exactly = 0) { vm.setWidgetMaxEventsPerDay(any()) }
        verify(exactly = 0) { vm.onDefaultEventDurationChange(any()) }
        verify(exactly = 0) { vm.setFirstDayOfWeek(any()) }
    }
}
