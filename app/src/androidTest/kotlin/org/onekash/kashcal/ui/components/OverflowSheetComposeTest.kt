package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * Rendering tests for [OverflowSheetContent], the testable content surface
 * hosted inside [OverflowSheet]'s ModalBottomSheet. Verifies item ordering,
 * inline Invites badge driven by [formatBadgeCount] (so the overflow
 * IconButton and the sheet row can never disagree on count), the Insights
 * row's selected state when [ViewMode.INSIGHTS], and tap routing.
 */
@RunWith(AndroidJUnit4::class)
class OverflowSheetComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val invitesLabel = context.getString(R.string.menu_invites)
    private val jumpToDateLabel = context.getString(R.string.jump_to_date)
    private val shareAvailabilityLabel = context.getString(R.string.share_availability_rail_label)
    private val insightsLabel = context.getString(R.string.view_insights)
    private val settingsLabel = context.getString(R.string.settings_title)
    private val aboutLabel = context.getString(R.string.menu_about)

    private fun ComposeContentTestRule.renderSheet(
        viewMode: ViewMode = ViewMode.MONTH,
        pendingInvitesCount: Int = 0,
        onInvitesClick: () -> Unit = {},
        onJumpToDateClick: () -> Unit = {},
        onShareAvailabilityClick: () -> Unit = {},
        onInsightsClick: () -> Unit = {},
        onSettingsClick: () -> Unit = {},
        onAboutClick: () -> Unit = {}
    ) {
        setContent {
            MaterialTheme {
                OverflowSheetContent(
                    currentViewMode = viewMode,
                    pendingInvitesCount = pendingInvitesCount,
                    onInvitesClick = onInvitesClick,
                    onJumpToDateClick = onJumpToDateClick,
                    onShareAvailabilityClick = onShareAvailabilityClick,
                    onInsightsClick = onInsightsClick,
                    onSettingsClick = onSettingsClick,
                    onAboutClick = onAboutClick
                )
            }
        }
    }

    @Test
    fun allSixLabelsAreDisplayed() {
        rule.renderSheet()
        rule.onNodeWithText(invitesLabel).assertIsDisplayed()
        rule.onNodeWithText(jumpToDateLabel).assertIsDisplayed()
        rule.onNodeWithText(shareAvailabilityLabel).assertIsDisplayed()
        rule.onNodeWithText(insightsLabel).assertIsDisplayed()
        rule.onNodeWithText(settingsLabel).assertIsDisplayed()
        rule.onNodeWithText(aboutLabel).assertIsDisplayed()
    }

    @Test
    fun itemsAppearInOrder_invitesDateShareInsightsSettingsAbout() {
        rule.renderSheet()
        val invitesTop = rule.onNodeWithText(invitesLabel).getBoundsInRoot().top
        val jumpTop = rule.onNodeWithText(jumpToDateLabel).getBoundsInRoot().top
        val shareTop = rule.onNodeWithText(shareAvailabilityLabel).getBoundsInRoot().top
        val insightsTop = rule.onNodeWithText(insightsLabel).getBoundsInRoot().top
        val settingsTop = rule.onNodeWithText(settingsLabel).getBoundsInRoot().top
        val aboutTop = rule.onNodeWithText(aboutLabel).getBoundsInRoot().top

        assertTrue("Invites ($invitesTop) above Date ($jumpTop)", invitesTop < jumpTop)
        assertTrue("Date ($jumpTop) above Share ($shareTop)", jumpTop < shareTop)
        assertTrue("Share ($shareTop) above Insights ($insightsTop)", shareTop < insightsTop)
        assertTrue("Insights ($insightsTop) above Settings ($settingsTop)", insightsTop < settingsTop)
        assertTrue("Settings ($settingsTop) above About ($aboutTop)", settingsTop < aboutTop)
    }

    @Test
    fun invitesRow_displaysBadgeCount_whenCountPositive() {
        rule.renderSheet(pendingInvitesCount = 5)
        rule.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun invitesRow_displays99Plus_whenCountAbove99() {
        rule.renderSheet(pendingInvitesCount = 150)
        rule.onNodeWithText("99+").assertIsDisplayed()
    }

    @Test
    fun invitesRow_noBadge_whenCountZero() {
        rule.renderSheet(pendingInvitesCount = 0)
        rule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun insightsRow_isSelected_whenViewModeIsINSIGHTS() {
        rule.renderSheet(viewMode = ViewMode.INSIGHTS)
        rule.onNodeWithText(insightsLabel).assertIsSelected()
    }

    @Test
    fun insightsRow_notSelected_whenViewModeIsMonth() {
        rule.renderSheet(viewMode = ViewMode.MONTH)
        rule.onNodeWithText(insightsLabel).assertIsNotSelected()
    }

    @Test
    fun otherRows_areNeverSelected_evenInINSIGHTS() {
        rule.renderSheet(viewMode = ViewMode.INSIGHTS)
        rule.onNodeWithText(invitesLabel).assertIsNotSelected()
        rule.onNodeWithText(jumpToDateLabel).assertIsNotSelected()
        rule.onNodeWithText(shareAvailabilityLabel).assertIsNotSelected()
        rule.onNodeWithText(settingsLabel).assertIsNotSelected()
        rule.onNodeWithText(aboutLabel).assertIsNotSelected()
    }

    @Test
    fun tappingInvites_invokesOnInvitesClick() {
        var invoked = false
        rule.renderSheet(onInvitesClick = { invoked = true })
        rule.onNodeWithText(invitesLabel).performClick()
        assert(invoked) { "onInvitesClick was not invoked" }
    }

    @Test
    fun tappingJumpToDate_invokesOnJumpToDateClick() {
        var invoked = false
        rule.renderSheet(onJumpToDateClick = { invoked = true })
        rule.onNodeWithText(jumpToDateLabel).performClick()
        assert(invoked) { "onJumpToDateClick was not invoked" }
    }

    @Test
    fun tappingShareAvailability_invokesOnShareAvailabilityClick() {
        var invoked = false
        rule.renderSheet(onShareAvailabilityClick = { invoked = true })
        rule.onNodeWithText(shareAvailabilityLabel).performClick()
        assert(invoked) { "onShareAvailabilityClick was not invoked" }
    }

    @Test
    fun tappingInsights_invokesOnInsightsClick() {
        var invoked = false
        rule.renderSheet(onInsightsClick = { invoked = true })
        rule.onNodeWithText(insightsLabel).performClick()
        assert(invoked) { "onInsightsClick was not invoked" }
    }

    @Test
    fun tappingSettings_invokesOnSettingsClick() {
        var invoked = false
        rule.renderSheet(onSettingsClick = { invoked = true })
        rule.onNodeWithText(settingsLabel).performClick()
        assert(invoked) { "onSettingsClick was not invoked" }
    }

    @Test
    fun tappingAbout_invokesOnAboutClick() {
        var invoked = false
        rule.renderSheet(onAboutClick = { invoked = true })
        rule.onNodeWithText(aboutLabel).performClick()
        assert(invoked) { "onAboutClick was not invoked" }
    }
}
