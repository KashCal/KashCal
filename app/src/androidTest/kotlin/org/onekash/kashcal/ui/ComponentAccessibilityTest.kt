package org.onekash.kashcal.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.ui.components.EventFormContent
import org.onekash.kashcal.ui.components.pickers.ColorPickerSheetContent
import org.onekash.kashcal.ui.screens.settings.AccountsScreen
import org.onekash.kashcal.ui.screens.settings.SubscriptionsScreen

/**
 * Runs the Accessibility Test Framework (the engine behind Accessibility
 * Scanner) against individual composables outside the calendar home surface:
 * the color picker, the settings sub-screens, and the event form body. Flags
 * low contrast, small touch targets, missing labels, and traversal-order
 * problems.
 *
 * These render non-modal content directly (no [androidx.compose.material3.ModalBottomSheet]
 * wrapper) to avoid the animation-timing flakiness the rest of the compose
 * suite avoids for the same reason. The event form is covered via
 * [EventFormContent], the wrapper-free body of the event form sheet.
 */
@RunWith(AndroidJUnit4::class)
class ComponentAccessibilityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun renderAndCheck(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            MaterialTheme {
                content()
            }
        }
        composeTestRule.enableAccessibilityChecks()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun colorPicker_passesAccessibilityChecks() {
        renderAndCheck {
            ColorPickerSheetContent(
                currentColor = 0xFFFF0000.toInt(),
                onColorSelected = {},
                onCancel = {},
            )
        }
    }

    @Test
    fun accountsScreen_passesAccessibilityChecks() {
        renderAndCheck {
            AccountsScreen(
                iCloudAccount = null,
                showAddICloud = true,
                calDavAccounts = emptyList(),
                onNavigateBack = {},
                onAddICloud = {},
                onICloudSignOut = {},
                onAddCalDav = {},
                onCalDavSignOut = {},
            )
        }
    }

    @Test
    fun subscriptionsScreen_passesAccessibilityChecks() {
        renderAndCheck {
            SubscriptionsScreen(
                subscriptions = emptyList(),
                onNavigateBack = {},
                onAddSubscription = { _, _, _ -> },
                onToggleSubscription = { _, _ -> },
                onDeleteSubscription = {},
                onRefreshSubscription = {},
                onUpdateSubscription = { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun eventFormCreate_passesAccessibilityChecks() {
        renderAndCheck {
            EventFormContent(
                // The form's root Column gives its scrollable field region
                // weight(1f); without a bounded height that region collapses to
                // zero and the checks would run against an empty layout. In
                // production the sheet supplies the height via the modifier.
                modifier = Modifier.fillMaxSize(),
                onSavingChange = {},
                calendars = eventFormCalendars,
                calendarGroups = emptyList(),
                defaultCalendar = DefaultCalendar.Room(1L),
                onDismiss = {},
                onSave = { Result.success(sampleEvent) },
            )
        }
    }

    private companion object {
        val eventFormCalendars = listOf(
            Calendar(
                id = 1L,
                accountId = 1L,
                caldavUrl = "https://caldav.icloud.com/cal1",
                displayName = "Personal",
                color = 0xFF2196F3.toInt(),
            ),
        )
        val sampleEvent = org.onekash.kashcal.data.db.entity.Event(
            id = 1L,
            uid = "a11y-sample@test",
            calendarId = 1L,
            title = "Sample",
            startTs = 0L,
            endTs = 0L,
            dtstamp = 0L,
        )
    }
}
