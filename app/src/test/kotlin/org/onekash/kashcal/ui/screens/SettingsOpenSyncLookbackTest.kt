package org.onekash.kashcal.ui.screens

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.SettingsActivity
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Search's Sync settings link opens Settings straight into the Sync lookback sheet, once: the
 * request opens the sheet after the screen has loaded, is cleared when the sheet opens, and a
 * later return to the Settings root or a recreated activity does not open it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class SettingsOpenSyncLookbackTest {

    @get:Rule
    val rule = createComposeRule()

    private var savedLocale: Locale? = null

    @Before
    fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        savedLocale?.let { Locale.setDefault(it) }
    }

    private class Host {
        var onRoot by mutableStateOf(true)
        var loading by mutableStateOf(false)
        // Held here as the view model holds it.
        var requested by mutableStateOf(true)
        var opened = 0
    }

    private fun render(host: Host) {
        rule.setContent {
            KashCalTheme {
                if (host.onRoot) {
                    AccountSettingsScreen(
                        uiState = AccountSettingsUiState(iCloudState = ICloudConnectionState.NotConnected(), isLoading = host.loading),
                        syncLookbackDays = 90,
                        openSyncLookback = host.requested,
                        onSyncLookbackOpened = { host.opened++; host.requested = false },
                    )
                }
            }
        }
    }

    private val sheetHint = "How far back to sync events"

    @Test
    fun `the request opens the Sync lookback sheet and is cleared`() {
        val host = Host()
        render(host)

        rule.onNodeWithText(sheetHint).assertExists()
        assertEquals(1, host.opened)
        assertFalse(host.requested)
    }

    @Test
    fun `a request made while loading opens the sheet once loading ends`() {
        val host = Host().apply { loading = true }
        render(host)
        rule.onNodeWithText(sheetHint).assertDoesNotExist()
        assertEquals(0, host.opened)

        host.loading = false
        rule.waitForIdle()

        rule.onNodeWithText(sheetHint).assertExists()
        assertEquals(1, host.opened)
    }

    @Test
    fun `returning to the Settings root does not open the sheet again`() {
        val host = Host()
        render(host)
        rule.onNodeWithText(sheetHint).assertExists()

        host.onRoot = false
        rule.waitForIdle()
        host.onRoot = true
        rule.waitForIdle()

        rule.onNodeWithText(sheetHint).assertDoesNotExist()
        assertEquals(1, host.opened)
    }

    @Test
    fun `only a fresh start with the extra asks for the sheet`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val withExtra = Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN_SYNC_LOOKBACK, true)
        val without = Intent(context, SettingsActivity::class.java)

        assertTrue(SettingsActivity.opensSyncLookback(null, withExtra))
        assertFalse("a recreated activity", SettingsActivity.opensSyncLookback(Bundle(), withExtra))
        assertFalse(SettingsActivity.opensSyncLookback(null, without))
        assertEquals(SettingsActivity::class.java.name, withExtra.component?.className)
    }
}
