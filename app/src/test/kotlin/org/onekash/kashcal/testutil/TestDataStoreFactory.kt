package org.onekash.kashcal.testutil

import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Builds mock [KashCalDataStore] instances for tests.
 *
 * The relaxed factories stub only syncPastDays; [createDefault] returns
 * Int.MAX_VALUE ("All events"), not the real default of 365 days.
 */
object TestDataStoreFactory {

    /**
     * Creates a relaxed mock with "All events" sync lookback.
     */
    fun createDefault(): KashCalDataStore {
        return mockk<KashCalDataStore>(relaxed = true) {
            every { syncPastDays } returns flowOf(Int.MAX_VALUE)
        }
    }

    /**
     * Creates a relaxed mock with a sync lookback of [days].
     */
    fun createWithSyncLookback(days: Int): KashCalDataStore {
        return mockk<KashCalDataStore>(relaxed = true) {
            every { syncPastDays } returns flowOf(days)
        }
    }

    /**
     * Creates a non-relaxed DataStore for HomeViewModel and device-write tests: every
     * member HomeViewModel reads is stubbed, with the preference's real default
     * or a value that keeps one-time UI (onboarding, What's New, the share-card
     * tooltip) out of the way, and every setter it calls is a no-op. A read
     * this list doesn't cover throws instead of silently returning a relaxed
     * default, so a test can't pass on a value nobody chose.
     */
    fun createStrictForHomeViewModel(
        defaultReminderMinutes: Int = KashCalDataStore.DEFAULT_REMINDER_MINUTES,
        defaultAllDayReminder: Int = KashCalDataStore.DEFAULT_ALL_DAY_REMINDER_MINUTES,
        deviceCalendarsEnabled: Boolean = false,
        enabledDeviceCalendarIds: Set<Long> = emptySet(),
    ): KashCalDataStore = mockk<KashCalDataStore> {
        every { timeFormat } returns MutableStateFlow(KashCalDataStore.TIME_FORMAT_SYSTEM)
        every { theme } returns MutableStateFlow(KashCalDataStore.THEME_SYSTEM)
        every { firstDayOfWeek } returns MutableStateFlow(KashCalDataStore.FIRST_DAY_SYSTEM)
        every { this@mockk.enabledDeviceCalendarIds } returns MutableStateFlow(enabledDeviceCalendarIds)
        every { this@mockk.deviceCalendarsEnabled } returns MutableStateFlow(deviceCalendarsEnabled)
        every { this@mockk.defaultReminderMinutes } returns MutableStateFlow(defaultReminderMinutes)
        every { this@mockk.defaultAllDayReminder } returns MutableStateFlow(defaultAllDayReminder)
        every { userInitials } returns MutableStateFlow("")
        every { tagsAboveNotes } returns MutableStateFlow(false)
        every { syncPastDays } returns MutableStateFlow(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS)
        every { shownShareCardTooltip } returns MutableStateFlow(true)
        every { showWeekNumbers } returns MutableStateFlow(false)
        every { showMultiDayTimedInAllDayStrip } returns MutableStateFlow(false)
        every { showEventEmojis } returns MutableStateFlow(true)
        every { quickAddEnabled } returns MutableStateFlow(false)
        every { onboardingDismissed } returns MutableStateFlow(true)
        every { hiddenDeviceCalendarIds } returns MutableStateFlow(emptySet())
        every { defaultEventDuration } returns MutableStateFlow(KashCalDataStore.DEFAULT_EVENT_DURATION_MINUTES)
        every { defaultCalendar } returns MutableStateFlow(null)
        every { defaultCalendarId } returns MutableStateFlow(null)
        every { dayWeekBarExpanded } returns MutableStateFlow(true)
        every { contactSuggestionsDeclined } returns MutableStateFlow(false)
        every { colorSource } returns MutableStateFlow(null)
        every { allDayRowsExpanded } returns MutableStateFlow(false)
        every { agendaWeekBarExpanded } returns MutableStateFlow(true)
        every { accentSeed } returns MutableStateFlow(KashCalDataStore.ACCENT_SEED_DEFAULT)
        coEvery { getWeekViewScrollMinutes() } returns KashCalDataStore.WEEK_VIEW_SCROLL_NOT_SAVED
        coEvery { getWeekViewHourHeight() } returns KashCalDataStore.DEFAULT_HOUR_HEIGHT_DP
        coEvery { getTitleSuggestionsEnabled() } returns true
        coEvery { getLastWhatsNewVersionShown() } returns Int.MAX_VALUE
        coEvery { getEnabledDeviceCalendarIds() } returns enabledDeviceCalendarIds
        coEvery { getDeviceCalendarsEnabled() } returns deviceCalendarsEnabled
        coEvery { getDefaultCalendarView() } returns KashCalDataStore.VIEW_MONTH
        coEvery { getDefaultCalendar() } returns null
        coJustRun { setLastWhatsNewVersionShown(any()) }
        coJustRun { toggleDeviceCalendarHidden(any()) }
        coJustRun { setWeekViewScrollMinutes(any()) }
        coJustRun { setWeekViewHourHeight(any()) }
        coJustRun { setUserInitials(any()) }
        coJustRun { setTagsAboveNotes(any()) }
        coJustRun { setShownShareCardTooltip(any()) }
        coJustRun { setOnboardingDismissed(any()) }
        coJustRun { setDefaultCalendarView(any()) }
        coJustRun { setDayWeekBarExpanded(any()) }
        coJustRun { setContactSuggestionsDeclined(any()) }
        coJustRun { setAllDayRowsExpanded(any()) }
        coJustRun { setAgendaWeekBarExpanded(any()) }
        coJustRun { clearAllParseFailureRetries() }
    }
}
