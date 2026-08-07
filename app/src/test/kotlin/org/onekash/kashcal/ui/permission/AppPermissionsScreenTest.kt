package org.onekash.kashcal.ui.permission

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Compose tests for the app-permissions screen body.
 *
 * The regressable surface is the row rendering + trailing wiring: a granted
 * permission shows the quiet "Allowed" state and taps route to that permission's
 * system settings; a not-granted permission shows the accent "Allow" action that
 * fires the request. The outer screen (launchers, resume re-resolve) owns
 * Activity-bound state, so the body is hoisted to take a fixed row list plus
 * callbacks — no Hilt graph or Activity, mirroring the hub's makeItYours slot.
 * Runs under Robolectric; run the class in isolation given the repo's
 * multi-class native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class AppPermissionsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(kind: AppPermissionKind, trailing: PermissionTrailing) = AppPermissionRow(
        kind = kind,
        nameRes = when (kind) {
            AppPermissionKind.NOTIFICATIONS -> R.string.permission_name_notifications
            AppPermissionKind.CONTACTS -> R.string.permission_name_contacts
            AppPermissionKind.CALENDARS -> R.string.permission_name_calendars
            AppPermissionKind.LOCAL_NETWORK -> R.string.permission_name_local_network
        },
        whyRes = when (kind) {
            AppPermissionKind.NOTIFICATIONS -> R.string.permission_why_notifications
            AppPermissionKind.CONTACTS -> R.string.permission_why_contacts
            AppPermissionKind.CALENDARS -> R.string.permission_why_calendars
            AppPermissionKind.LOCAL_NETWORK -> R.string.permission_why_local_network
        },
        trailing = trailing,
    )

    private class Callbacks {
        val allowed = mutableListOf<AppPermissionKind>()
        val openedSettings = mutableListOf<AppPermissionKind>()
    }

    private fun render(
        rows: List<AppPermissionRow>,
        callbacks: Callbacks = Callbacks(),
    ) {
        Locale.setDefault(Locale.US)
        composeTestRule.setContent {
            MaterialTheme {
                AppPermissionsScreenContent(
                    rows = rows,
                    onAllow = { callbacks.allowed += it },
                    onOpenPermissionSettings = { callbacks.openedSettings += it },
                )
            }
        }
    }

    @Test
    fun `each row renders its name`() {
        render(
            listOf(
                row(AppPermissionKind.NOTIFICATIONS, PermissionTrailing.ALLOW),
                row(AppPermissionKind.CONTACTS, PermissionTrailing.ALLOWED),
                row(AppPermissionKind.CALENDARS, PermissionTrailing.ALLOW),
                row(AppPermissionKind.LOCAL_NETWORK, PermissionTrailing.ALLOWED),
            ),
        )
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
        composeTestRule.onNodeWithText("Contacts").assertIsDisplayed()
        composeTestRule.onNodeWithText("Calendars").assertIsDisplayed()
        composeTestRule.onNodeWithText("Local network").assertIsDisplayed()
    }

    @Test
    fun `a not-granted row shows the Allow action and fires the request on tap`() {
        val cb = Callbacks()
        render(listOf(row(AppPermissionKind.CONTACTS, PermissionTrailing.ALLOW)), cb)

        composeTestRule.onNodeWithText("Allow").performClick()

        assertEquals(listOf(AppPermissionKind.CONTACTS), cb.allowed)
        assertEquals(emptyList<AppPermissionKind>(), cb.openedSettings)
    }

    @Test
    fun `a granted row shows Allowed and routes a tap to that permission's settings`() {
        val cb = Callbacks()
        render(listOf(row(AppPermissionKind.CONTACTS, PermissionTrailing.ALLOWED)), cb)

        composeTestRule.onNodeWithText("Allowed").assertIsDisplayed()
        // No Allow action on a granted row.
        composeTestRule.onAllNodesWithText("Allow").assertCountEquals(0)

        composeTestRule.onNodeWithText("Allowed").performClick()
        assertEquals(listOf(AppPermissionKind.CONTACTS), cb.openedSettings)
        assertEquals(emptyList<AppPermissionKind>(), cb.allowed)
    }

    @Test
    fun `only the tapped row's Allow fires`() {
        val cb = Callbacks()
        render(
            listOf(
                row(AppPermissionKind.NOTIFICATIONS, PermissionTrailing.ALLOW),
                row(AppPermissionKind.CONTACTS, PermissionTrailing.ALLOW),
            ),
            cb,
        )
        // Two Allow actions; tap the first (Notifications is ordered first).
        composeTestRule.onAllNodesWithText("Allow")[0].performClick()
        assertEquals(listOf(AppPermissionKind.NOTIFICATIONS), cb.allowed)
    }

    @Test
    fun `a granted row routes to its own kind's settings, not a neighbour's`() {
        val cb = Callbacks()
        render(
            listOf(
                row(AppPermissionKind.NOTIFICATIONS, PermissionTrailing.ALLOWED),
                row(AppPermissionKind.CALENDARS, PermissionTrailing.ALLOWED),
            ),
            cb,
        )
        // Two "Allowed" rows; tap the second (Calendars).
        composeTestRule.onAllNodesWithText("Allowed")[1].performClick()
        assertEquals(listOf(AppPermissionKind.CALENDARS), cb.openedSettings)
    }
}
