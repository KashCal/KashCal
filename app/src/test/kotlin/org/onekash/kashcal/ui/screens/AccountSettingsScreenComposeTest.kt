package org.onekash.kashcal.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.activity.ComponentDialog
import org.robolectric.shadows.ShadowDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Compose tests for [AccountSettingsScreen].
 *
 * - Structure: the sentence-case section headers, the sync rows, the relabelled rows with no
 *   beta badge, and the split timed and all-day alert rows.
 * - Callback wiring: [render] records sixteen callbacks in [Rec] (six `(Int)`, the `(Long)` sync
 *   frequency, five `(Boolean)` toggles and four navigation rows). Each test drives the row the
 *   user touches, asserts its callback fired with a distinct sentinel value, and asserts its
 *   siblings stayed silent. The sibling check is what catches a swap: callbacks of one type
 *   swapped with each other still compile and pass a test that only checks the target fired.
 * - Info tooltips: tapping a toggle's info icon shows its explanation without firing the toggle.
 *
 * Runs under Robolectric; run the class in isolation because of the repo's multi-class
 * native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class AccountSettingsScreenComposeTest {

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
     * Holds one field per recorded callback. A single shared recorder would hide a cross-fire
     * (two rows recording into the same slot), the mis-wire these tests catch. Value fields stay
     * null until fired; nav rows count invocations so a double-fire shows too.
     */
    private class Rec {
        // (Int) -> Unit cluster
        var weeks: Int? = null
        var widget: Int? = null
        var duration: Int? = null
        var timed: Int? = null
        var allDay: Int? = null
        var lookback: Int? = null
        var firstDay: Int? = null

        // (Long) -> Unit cluster (visible rows)
        var syncInterval: Long? = null

        // (Boolean) -> Unit cluster
        var quickAdd: Boolean? = null
        var titleSuggestions: Boolean? = null
        var weekNumbers: Boolean? = null
        var showDeclined: Boolean? = null
        var emojis: Boolean? = null

        // () -> Unit navigation cluster (counts, to expose a double-fire)
        var navAccounts = 0
        var navSubscriptions = 0
        var navBirthdays = 0
        var navDeviceCalendars = 0
    }

    /**
     * Renders the real screen with the [Rec] callbacks wired to [rec] and the rest left at their
     * defaults. Seed values are chosen so each row's picker offers a sentinel option distinct from
     * the seed (see the per-row tests).
     */
    private fun render(rec: Rec = Rec()) {
        composeTestRule.setContent {
            KashCalTheme {
                AccountSettingsScreen(
                    uiState = AccountSettingsUiState(
                        iCloudState = ICloudConnectionState.NotConnected(),
                    ),
                    calendars = listOf(localCalendar),
                    // Each sentinel differs from its seed, so a fired callback can't be a
                    // re-selection of the current value.
                    widgetMaxEventsPerDay = 5,
                    defaultEventDuration = 30,
                    defaultReminderTimed = 15,
                    defaultReminderAllDay = 900,
                    syncLookbackDays = 365,
                    syncIntervalMs = 24 * 60 * 60 * 1000L,
                    firstDayOfWeek = java.util.Calendar.SUNDAY,
                    showWeekNumbers = false,
                    showDeclinedEvents = false,
                    quickAddEnabled = false,
                    titleSuggestionsEnabled = true,
                    showEventEmojis = true,
                    // (Int) cluster, plus the (Long) sync frequency
                    monthWidgetWeeks = 2,
                    onMonthWidgetWeeksChange = { rec.weeks = it },
                    onWidgetMaxEventsPerDayChange = { rec.widget = it },
                    onDefaultEventDurationChange = { rec.duration = it },
                    onDefaultReminderTimedChange = { rec.timed = it },
                    onDefaultReminderAllDayChange = { rec.allDay = it },
                    onSyncLookbackChange = { rec.lookback = it },
                    onSyncIntervalChange = { rec.syncInterval = it },
                    onFirstDayOfWeekChange = { rec.firstDay = it },
                    // (Boolean) cluster
                    onQuickAddEnabledChange = { rec.quickAdd = it },
                    onTitleSuggestionsEnabledChange = { rec.titleSuggestions = it },
                    onShowWeekNumbersChange = { rec.weekNumbers = it },
                    onToggleShowDeclinedEvents = { rec.showDeclined = it },
                    onShowEventEmojisChange = { rec.emojis = it },
                    // Navigation cluster
                    onNavigateToAccounts = { rec.navAccounts++ },
                    onNavigateToSubscriptions = { rec.navSubscriptions++ },
                    onNavigateToBirthdaysAnniversaries = { rec.navBirthdays++ },
                    onNavigateToDeviceCalendars = { rec.navDeviceCalendars++ },
                    versionName = "1.0.0",
                )
            }
        }
    }

    // ==================== Sibling-silence helpers ====================

    /** Asserts the named (Int) callback fired with [expected] and every sibling stayed null. */
    private fun assertOnlyInt(rec: Rec, fired: String, expected: Int) {
        val all = linkedMapOf(
            "weeks" to rec.weeks,
            "widget" to rec.widget,
            "duration" to rec.duration,
            "timed" to rec.timed,
            "allDay" to rec.allDay,
            "lookback" to rec.lookback,
            "firstDay" to rec.firstDay,
        )
        assertEquals("$fired should fire with $expected", expected, all[fired])
        all.filterKeys { it != fired }.forEach { (name, value) ->
            assertNull("sibling (Int) callback '$name' must stay silent when '$fired' is used", value)
        }
    }

    /**
     * Asserts the sync-frequency (Long) callback fired with [expected] and every recorded (Int)
     * and (Boolean) callback stayed null. Sync frequency is the only visible (Long) row, so its
     * cross-fire risk is a mis-wire to an (Int) row such as Sync lookback or to a toggle.
     */
    private fun assertOnlySyncInterval(rec: Rec, expected: Long) {
        assertEquals("syncInterval should fire with $expected", expected, rec.syncInterval)
        val ints = linkedMapOf(
            "weeks" to rec.weeks,
            "widget" to rec.widget,
            "duration" to rec.duration,
            "timed" to rec.timed,
            "allDay" to rec.allDay,
            "lookback" to rec.lookback,
            "firstDay" to rec.firstDay,
        )
        ints.forEach { (name, value) ->
            assertNull("sibling (Int) callback '$name' must stay silent when 'syncInterval' is used", value)
        }
        val bools = linkedMapOf(
            "quickAdd" to rec.quickAdd,
            "titleSuggestions" to rec.titleSuggestions,
            "weekNumbers" to rec.weekNumbers,
            "showDeclined" to rec.showDeclined,
            "emojis" to rec.emojis,
        )
        bools.forEach { (name, value) ->
            assertNull("sibling (Boolean) callback '$name' must stay silent when 'syncInterval' is used", value)
        }
    }

    /** Asserts the named (Boolean) callback fired with [expected] and every sibling stayed null. */
    private fun assertOnlyBool(rec: Rec, fired: String, expected: Boolean) {
        val all = linkedMapOf(
            "quickAdd" to rec.quickAdd,
            "titleSuggestions" to rec.titleSuggestions,
            "weekNumbers" to rec.weekNumbers,
            "showDeclined" to rec.showDeclined,
            "emojis" to rec.emojis,
        )
        assertEquals("$fired should fire with $expected", expected, all[fired])
        all.filterKeys { it != fired }.forEach { (name, value) ->
            assertNull("sibling (Boolean) callback '$name' must stay silent when '$fired' is used", value)
        }
    }

    /** Asserts the named nav callback fired once and every sibling stayed at zero. */
    private fun assertOnlyNav(rec: Rec, fired: String) {
        val all = linkedMapOf(
            "accounts" to rec.navAccounts,
            "subscriptions" to rec.navSubscriptions,
            "birthdays" to rec.navBirthdays,
            "deviceCalendars" to rec.navDeviceCalendars,
        )
        assertEquals("$fired nav should fire exactly once", 1, all[fired])
        all.filterKeys { it != fired }.forEach { (name, count) ->
            assertEquals("sibling nav '$name' must stay silent when '$fired' is used", 0, count)
        }
    }

    // ==================== Structural guards ====================

    @Test
    fun `renders the sentence-case section headers`() {
        render()
        listOf(
            "Calendars & accounts",
            "Appearance",
            "Event preferences",
            "Sync",
            "Backup & restore",
        ).forEach { header ->
            composeTestRule.onNodeWithText(header).assertExists()
        }
    }

    @Test
    fun `sync section holds the sync-frequency and lookback rows`() {
        render()
        composeTestRule.onNodeWithText("Sync frequency").assertExists()
        composeTestRule.onNodeWithText("Sync lookback").assertExists()
    }

    @Test
    fun `renders relabelled rows in sentence case with no beta badge`() {
        render()
        composeTestRule.onNodeWithText("Show week numbers").assertExists()
        composeTestRule.onNodeWithText("Smart event add").assertExists()
        composeTestRule.onNodeWithText("Beta").assertDoesNotExist()
        composeTestRule.onNodeWithText("Quick Event Add").assertDoesNotExist()
    }

    @Test
    fun `default alerts is split into a timed and an all-day row`() {
        render()
        composeTestRule.onNodeWithText("Timed event alert").assertExists()
        composeTestRule.onNodeWithText("All-day event alert").assertExists()
        // No combined row.
        composeTestRule.onNodeWithText("Default alerts").assertDoesNotExist()
    }

    // ==================== (Int) cluster: fired-with-value + siblings silent ====================

    @Test
    fun `visible calendar weeks picker fires only its callback and dismisses`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Visible calendar weeks").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("3 weeks").performClick()
        assertOnlyInt(rec, fired = "weeks", expected = 3)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("3 weeks").assertDoesNotExist()
    }

    @Test
    fun `widget event limit row fires only its callback with the picked value`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Widget event limit").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("8 per day").performClick()
        assertOnlyInt(rec, fired = "widget", expected = 8)
    }

    @Test
    fun `default event length row fires only its callback with the picked value`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Default event length").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("1 hour").performClick()
        assertOnlyInt(rec, fired = "duration", expected = 60)
    }

    @Test
    fun `timed event alert row fires only its callback with the picked value`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Timed event alert").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("4 hours before").performClick()
        assertOnlyInt(rec, fired = "timed", expected = 240)
    }

    @Test
    fun `all-day event alert row fires only its callback with the picked value`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("All-day event alert").performClick()
        composeTestRule.waitForIdle()
        // Pick a preset distinct from the 900 ("1 day before") seed.
        composeTestRule.onNodeWithText("1 week before").performClick()
        assertOnlyInt(rec, fired = "allDay", expected = 9540)
    }

    @Test
    fun `sync lookback row fires only its callback with the picked value`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Sync lookback").performClick()
        composeTestRule.waitForIdle()
        // Seed is 365 ("1 year"); pick a distinct option.
        composeTestRule.onNodeWithText("6 months").performClick()
        assertOnlyInt(rec, fired = "lookback", expected = 180)
    }

    @Test
    fun `sync frequency row fires only its callback with the picked value`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Sync frequency").performClick()
        composeTestRule.waitForIdle()
        // Seed is 24 hours; pick a distinct option ("1 hour" == 3_600_000 ms).
        composeTestRule.onNodeWithText("1 hour").performClick()
        assertOnlySyncInterval(rec, expected = 60 * 60 * 1000L)
    }

    @Test
    fun `start week on row fires only its callback with the picked value`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Start week on").performClick()
        composeTestRule.waitForIdle()
        // Seed is Sunday; pick Monday (Calendar.MONDAY == 2).
        composeTestRule.onNodeWithText("Monday").performClick()
        assertOnlyInt(rec, fired = "firstDay", expected = java.util.Calendar.MONDAY)
    }

    // ==================== (Boolean) cluster: fired + siblings silent ====================

    @Test
    fun `show week numbers toggle fires only its callback`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Show week numbers").performClick()
        assertOnlyBool(rec, fired = "weekNumbers", expected = true)
    }

    @Test
    fun `show declined toggle fires only its callback`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Show declined events").performClick()
        assertOnlyBool(rec, fired = "showDeclined", expected = true)
    }

    @Test
    fun `smart event add toggle fires only its callback`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Smart event add").performClick()
        assertOnlyBool(rec, fired = "quickAdd", expected = true)
    }

    @Test
    fun `suggest titles toggle fires only its callback`() {
        val rec = Rec()
        render(rec)
        // Seed is on, so tapping the row turns it off.
        composeTestRule.onNodeWithText("Suggest event titles").performClick()
        assertOnlyBool(rec, fired = "titleSuggestions", expected = false)
    }

    @Test
    fun `event emojis toggle fires only its callback`() {
        val rec = Rec()
        render(rec)
        // Seed is on, so tapping the row turns it off.
        composeTestRule.onNodeWithText("Event emojis").performClick()
        assertOnlyBool(rec, fired = "emojis", expected = false)
    }

    // ==================== Info tooltips reveal explanation without toggling ====================

    @Test
    fun `smart event add info tooltip reveals explanation without toggling`() {
        val rec = Rec()
        render(rec)
        // The ⓘ content description is "About <setting name>".
        composeTestRule.onNodeWithContentDescription("About Smart event add").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(
            "Create events by typing naturally. \"Lunch with Sam Friday 1pm\" fills in the title, date, and time for you.",
        ).assertExists()
        assertNull("Info tap must not fire the toggle", rec.quickAdd)
    }

    @Test
    fun `suggest titles info tooltip reveals explanation without toggling`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithContentDescription("About Suggest event titles").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(
            "As you name a new event, suggest titles you've used before, so repeat events are one tap. Ranked by how often and how recently you've used them.",
        ).assertExists()
        assertNull("Info tap must not fire the toggle", rec.titleSuggestions)
    }

    @Test
    fun `event emojis info tooltip reveals explanation without toggling`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithContentDescription("About Event emojis").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(
            "Adds an emoji to events based on the title, like 🎂 for \"Birthday\".",
        ).assertExists()
        assertNull("Info tap must not fire the toggle", rec.emojis)
    }

    // ==================== Navigation cluster: fired-once + siblings silent ====================

    @Test
    fun `calendar accounts row fires only its navigation callback`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Calendar accounts").performClick()
        assertOnlyNav(rec, fired = "accounts")
    }

    @Test
    fun `calendar feeds row fires only its navigation callback`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Calendar feeds (ICS)").performClick()
        assertOnlyNav(rec, fired = "subscriptions")
    }

    @Test
    fun `birthdays and anniversaries row fires only its navigation callback`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Birthdays & anniversaries").performClick()
        assertOnlyNav(rec, fired = "birthdays")
    }

    @Test
    fun `device calendars row fires only its navigation callback`() {
        val rec = Rec()
        render(rec)
        composeTestRule.onNodeWithText("Device calendars").performClick()
        assertOnlyNav(rec, fired = "deviceCalendars")
    }

    // ==================== Calendar notifications ====================

    private val work = Calendar(
        id = 20L, accountId = 2L, caldavUrl = "https://caldav.example.test/work/", displayName = "Work",
        color = 0xFF4CAF50.toInt(), isNotificationMuted = false,
    )
    private val phone = DeviceCalendar(
        id = 100L, displayName = "Phone", color = 0xFF607D8B.toInt(), accountName = "",
        accountType = "LOCAL", visible = true, accessLevel = 700,
    )

    private val mutedCalls = mutableListOf<Triple<String, Long, Boolean>>()

    private val deviceReminderCalls = mutableListOf<Boolean>()

    private fun renderMute(
        workMuted: Boolean = false,
        mutedDevice: Set<Long> = emptySet(),
        searchQuery: String = "",
        deviceRemindersEnabled: Boolean = true,
        devices: List<DeviceCalendar> = listOf(phone),
    ) {
        // Held here as the ViewModel holds it, so the switch follows each tap.
        var remindersEnabled by mutableStateOf(deviceRemindersEnabled)
        val calendars = listOf(localCalendar, work.copy(isNotificationMuted = workMuted))
        composeTestRule.setContent {
            KashCalTheme {
                AccountSettingsScreen(
                    uiState = AccountSettingsUiState(iCloudState = ICloudConnectionState.NotConnected()),
                    calendars = calendars,
                    calendarGroups = CalendarGroup.fromCalendarsAndAccounts(
                        calendars,
                        listOf(
                            Account(id = 1L, provider = AccountProvider.LOCAL, email = "local"),
                            Account(id = 2L, provider = AccountProvider.CALDAV, email = "me@example.test", displayName = "Nextcloud"),
                        ),
                        localLabel = "Offline",
                        icsLabel = "Calendar Feeds",
                    ),
                    deviceCalendarsEnabled = true,
                    hasReadCalendarPermission = true,
                    deviceCalendars = devices,
                    enabledDeviceCalendarIds = devices.mapTo(mutableSetOf()) { it.id },
                    mutedDeviceCalendarIds = mutedDevice,
                    deviceCalendarRemindersEnabled = remindersEnabled,
                    onToggleDeviceCalendarReminders = { enabled ->
                        deviceReminderCalls += enabled
                        remindersEnabled = enabled
                    },
                    onToggleCalendarMuted = { id, muted -> mutedCalls += Triple("room", id, muted) },
                    onToggleDeviceCalendarMuted = { id, muted -> mutedCalls += Triple("device", id, muted) },
                    isSearchActive = searchQuery.isNotEmpty(),
                    searchQuery = searchQuery,
                    versionName = "1.0.0",
                )
            }
        }
    }

    @Test
    fun `calendar notifications row reads All calendars when nothing is muted`() {
        renderMute()

        composeTestRule.onNodeWithText("Calendar notifications").assertIsDisplayed()
        composeTestRule.onNodeWithText("All calendars").assertIsDisplayed()
    }

    @Test
    fun `with device reminders off the row counts every device calendar as muted`() {
        val devices = listOf(
            phone,
            phone.copy(id = 101L, displayName = "Gym"),
            phone.copy(id = 102L, displayName = "Family"),
        )
        renderMute(deviceRemindersEnabled = false, devices = devices)

        composeTestRule.onNodeWithText("3 muted").assertExists()
        composeTestRule.onNodeWithText("All calendars").assertDoesNotExist()
    }

    @Test
    fun `the Device calendars row keeps its enabled count`() {
        renderMute()

        composeTestRule.onNodeWithText("1 enabled").assertExists()
    }

    @Test
    fun `the sheet's Device calendars switch turns device reminders off and on`() {
        renderMute()

        composeTestRule.onNodeWithText("Calendar notifications").performClick()
        val deviceSwitch = composeTestRule.onNode(
            hasContentDescription("Device calendar notifications") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        )
        deviceSwitch.performClick()
        deviceSwitch.performClick()

        assertEquals(listOf(false, true), deviceReminderCalls)
    }

    @Test
    fun `calendar notifications row counts Room and device mutes`() {
        renderMute(workMuted = true, mutedDevice = setOf(phone.id))

        composeTestRule.onNodeWithText("2 muted").assertIsDisplayed()
    }

    @Test
    fun `settings search finds calendar notifications by its label`() {
        renderMute(searchQuery = "notifications")

        composeTestRule.onNodeWithText("Calendar notifications", substring = true).assertIsDisplayed()
    }

    @Test
    fun `settings search finds calendar notifications by muted only once one is muted`() {
        renderMute(workMuted = true, searchQuery = "muted")
        composeTestRule.onNodeWithText("Calendar notifications", substring = true).assertIsDisplayed()
    }

    @Test
    fun `settings search for muted finds nothing while every calendar notifies`() {
        renderMute(searchQuery = "muted")
        assertEquals(0, composeTestRule.onAllNodesWithText("Calendar notifications", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the row opens the sheet, whose switches route Room and device calendars apart`() {
        renderMute()

        composeTestRule.onNodeWithText("Calendar notifications").performClick()
        composeTestRule.onNodeWithText("Choose which calendars can notify you.").assertIsDisplayed()

        composeTestRule.onNodeWithText("Work").performClick()
        composeTestRule.onNodeWithText("Phone").performClick()

        assertEquals(listOf(Triple("room", 20L, true), Triple("device", 100L, true)), mutedCalls)
    }

    @Test
    fun `the sheet's muted-calendar detail starts hidden again after the sheet is closed and reopened`() {
        renderMute()
        val detail = "Muted calendars still show and sync, but don't notify you."

        composeTestRule.onNodeWithText("Calendar notifications").performClick()
        composeTestRule.onNodeWithContentDescription("About muted calendars").performClick()
        composeTestRule.onNodeWithText(detail).assertIsDisplayed()

        // The sheet is its own dialog window; its back dispatcher is what a system back reaches.
        composeTestRule.runOnUiThread {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Choose which calendars can notify you.").assertDoesNotExist()
        composeTestRule.onNodeWithText("Calendar notifications").performClick()

        composeTestRule.onNodeWithText("Choose which calendars can notify you.").assertIsDisplayed()
        composeTestRule.onNodeWithText(detail).assertDoesNotExist()
    }
}
