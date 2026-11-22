package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.session.SyncSession
import org.onekash.kashcal.sync.session.SyncType
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SyncHistorySheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val noSyncAccountText =
        "No sync account set up. Offline calendars stay on this device; " +
            "device calendars sync through their own account."
    private val noSessionsText = "No sync sessions yet."

    private val session = SyncSession(
        calendarId = 1,
        calendarName = "Work",
        syncType = SyncType.INCREMENTAL,
        triggerSource = SyncTrigger.FOREGROUND_MANUAL,
        durationMs = 100,
        hrefsReported = 0,
        eventsFetched = 0,
        eventsWritten = 0,
        eventsUpdated = 0,
        eventsDeleted = 0,
    )

    private fun render(sessions: List<SyncSession>, hasSyncAccount: Boolean) {
        composeTestRule.setContent {
            KashCalTheme {
                Column { SyncHistoryList(sessions = sessions, hasSyncAccount = hasSyncAccount) }
            }
        }
    }

    @Test
    fun `no sessions and no sync account explains why nothing syncs`() {
        render(emptyList(), hasSyncAccount = false)
        composeTestRule.onNodeWithText(noSyncAccountText).assertExists()
        composeTestRule.onNodeWithText(noSessionsText, substring = true).assertDoesNotExist()
    }

    @Test
    fun `no sessions with a sync account keeps the pull to refresh hint`() {
        render(emptyList(), hasSyncAccount = true)
        composeTestRule.onNodeWithText(noSessionsText, substring = true).assertExists()
        composeTestRule.onNodeWithText(noSyncAccountText).assertDoesNotExist()
    }

    @Test
    fun `stored sessions show with no sync account`() {
        render(listOf(session), hasSyncAccount = false)
        composeTestRule.onNodeWithText("Work", substring = true).assertExists()
        composeTestRule.onNodeWithText(noSyncAccountText).assertDoesNotExist()
    }
}
