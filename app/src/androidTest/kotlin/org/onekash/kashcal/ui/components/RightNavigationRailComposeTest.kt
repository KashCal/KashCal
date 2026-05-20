package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * Rendering tests for [RightNavigationRail]. Verifies the canonical
 * Material 3 [androidx.compose.material3.NavigationRailItem] integration:
 * Invites count is rendered as a corner [androidx.compose.material3.Badge]
 * inside a [androidx.compose.material3.BadgedBox], badge text is
 * controlled by [formatBadgeCount] (so AppBar and rail can never
 * disagree), and the Insights item picks up the selected state from
 * [ViewMode.INSIGHTS].
 *
 * Lives in androidTest/ because Compose UI testing's
 * `androidx.ui.test.junit4` is androidTestImplementation only.
 */
@RunWith(AndroidJUnit4::class)
class RightNavigationRailComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val invitesLabel = context.getString(R.string.menu_invites)
    private val jumpToDateLabel = context.getString(R.string.jump_to_date)
    private val insightsLabel = context.getString(R.string.view_insights)
    private val settingsLabel = context.getString(R.string.settings_title)

    private fun ComposeContentTestRule.renderRail(
        viewMode: ViewMode = ViewMode.MONTH,
        pendingInvitesCount: Int = 0,
        onInvitesClick: () -> Unit = {},
        onJumpToDateClick: () -> Unit = {},
        onInsightsClick: () -> Unit = {},
        onSettingsClick: () -> Unit = {}
    ) {
        setContent {
            MaterialTheme {
                RightNavigationRail(
                    currentViewMode = viewMode,
                    pendingInvitesCount = pendingInvitesCount,
                    onInvitesClick = onInvitesClick,
                    onJumpToDateClick = onJumpToDateClick,
                    onInsightsClick = onInsightsClick,
                    onSettingsClick = onSettingsClick
                )
            }
        }
    }

    @Test
    fun mailIcon_displaysCount_whenCountPositive() {
        rule.renderRail(pendingInvitesCount = 5)
        rule.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun mailIcon_displays99Plus_whenCountAbove99() {
        rule.renderRail(pendingInvitesCount = 150)
        rule.onNodeWithText("99+").assertIsDisplayed()
    }

    @Test
    fun mailIcon_noBadge_whenCountZero() {
        rule.renderRail(pendingInvitesCount = 0)
        rule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun insightsItem_isSelected_whenViewModeIsINSIGHTS() {
        rule.renderRail(viewMode = ViewMode.INSIGHTS)
        rule.onNodeWithText(insightsLabel).assertIsSelected()
    }

    @Test
    fun insightsItem_notSelected_whenViewModeIsMonth() {
        rule.renderRail(viewMode = ViewMode.MONTH)
        rule.onNodeWithText(insightsLabel).assertIsNotSelected()
    }

    @Test
    fun tappingInvites_invokesOnInvitesClick() {
        var invoked = false
        rule.renderRail(onInvitesClick = { invoked = true })
        rule.onNodeWithText(invitesLabel).performClick()
        assert(invoked) { "onInvitesClick was not invoked when Invites item tapped" }
    }

    @Test
    fun tappingInsights_invokesOnInsightsClick() {
        var invoked = false
        rule.renderRail(onInsightsClick = { invoked = true })
        rule.onNodeWithText(insightsLabel).performClick()
        assert(invoked) { "onInsightsClick was not invoked when Insights item tapped" }
    }

    @Test
    fun tappingSettings_invokesOnSettingsClick() {
        var invoked = false
        rule.renderRail(onSettingsClick = { invoked = true })
        rule.onNodeWithText(settingsLabel).performClick()
        assert(invoked) { "onSettingsClick was not invoked when Settings item tapped" }
    }

    @Test
    fun tappingJumpToDate_invokesOnJumpToDateClick() {
        var invoked = false
        rule.renderRail(onJumpToDateClick = { invoked = true })
        rule.onNodeWithText(jumpToDateLabel).performClick()
        assert(invoked) { "onJumpToDateClick was not invoked when Jump to date item tapped" }
    }

    @Test
    fun jumpToDateItem_appearsBetweenInvitesAndInsights() {
        rule.renderRail()
        val invitesTop = rule.onNodeWithText(invitesLabel).getBoundsInRoot().top
        val jumpTop = rule.onNodeWithText(jumpToDateLabel).getBoundsInRoot().top
        val insightsTop = rule.onNodeWithText(insightsLabel).getBoundsInRoot().top
        assertTrue(
            "Jump to date ($jumpTop) must be below Invites ($invitesTop)",
            jumpTop > invitesTop
        )
        assertTrue(
            "Jump to date ($jumpTop) must be above Insights ($insightsTop)",
            jumpTop < insightsTop
        )
    }

    @Test
    fun jumpToDateItem_notSelected_whenViewModeIsINSIGHTS() {
        rule.renderRail(viewMode = ViewMode.INSIGHTS)
        rule.onNodeWithText(jumpToDateLabel).assertIsNotSelected()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun firstRailItem_topEdge_isBelowTopAppBarBottomEdge() {
        val barTitle = "Bar"
        val barCd = "bar-icon"
        rule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    CenterAlignedTopAppBar(
                        title = { Text(barTitle) },
                        navigationIcon = {
                            IconButton(onClick = {}) {
                                Icon(
                                    Icons.Default.MailOutline,
                                    contentDescription = barCd,
                                )
                            }
                        }
                    )
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        RightNavigationRail(
                            currentViewMode = ViewMode.MONTH,
                            pendingInvitesCount = 0,
                            onInvitesClick = {},
                            onJumpToDateClick = {},
                            onInsightsClick = {},
                            onSettingsClick = {},
                        )
                    }
                }
            }
        }
        val barBounds = rule.onNodeWithContentDescription(barCd).getBoundsInRoot()
        val firstItemBounds = rule.onNodeWithText(invitesLabel).getBoundsInRoot()
        assertTrue(
            "First rail item top (${firstItemBounds.top}) must be at or below " +
                "top app bar bottom (${barBounds.bottom})",
            firstItemBounds.top >= barBounds.bottom
        )
        assertTrue(
            "First rail item top (${firstItemBounds.top}) must be >= 64.dp " +
                "(default top app bar height)",
            firstItemBounds.top >= 64.dp
        )
    }
}
