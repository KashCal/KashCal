package org.onekash.kashcal.ui.components

import android.content.Context
import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.whatsnew.ALL_RELEASE_NOTES
import org.onekash.kashcal.domain.whatsnew.ReleaseNote
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Tests [WhatsNewBanner] through the sheet a user sees: paging with Next, Back, swipe and system
 * back, which control each page shows ([WhatsNewPagerControls]), that every exit calls onDismiss,
 * the page dots' spoken position, and the "Find it in" path.
 *
 * System back is sent to the sheet's own window, a ComponentDialog, the way the platform delivers
 * it. Runs under Robolectric; run the class alone given the repo's multi-class native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h9999dp-mdpi")
class WhatsNewBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val featureNotes = ALL_RELEASE_NOTES.filter { it.versionCode == 772 }
    private val supporterNote = ALL_RELEASE_NOTES.single { it.versionCode == 732 }
    private val notes803 = ALL_RELEASE_NOTES.filter { it.versionCode == 803 }
    private var dismissCount = 0

    private fun render(
        releases: List<ReleaseNote> = featureNotes,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                MaterialTheme {
                    WhatsNewBanner(
                        releases = releases,
                        userInitials = "OK",
                        timePattern = "h:mm a",
                        onDismiss = { dismissCount++ },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    /** Matches [label] on the shown page, not on the invisible copies that size the pager. */
    private fun shown(label: String) = hasText(label) and !hasAnyAncestor(hasTestTag(WHATS_NEW_SIZER_TAG))

    private fun next() = composeTestRule.onNodeWithText("Next").performClick().also { composeTestRule.waitForIdle() }

    private fun systemBack() {
        composeTestRule.runOnIdle {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `first page shows Skip and Next but no Back`() {
        render()
        composeTestRule.onNodeWithText(text(R.string.whats_new_v772_icon_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Skip").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").assertDoesNotExist()
    }

    @Test
    fun `Next moves to the second page, which shows Back`() {
        render()
        next()
        composeTestRule.onNodeWithText(text(R.string.whats_new_v772_changes_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").assertIsDisplayed()
        composeTestRule.onNodeWithText("Skip").assertIsDisplayed()
    }

    @Test
    fun `Back returns to the first page`() {
        render()
        next()
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(text(R.string.whats_new_v772_icon_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").assertDoesNotExist()
    }

    @Test
    fun `last page shows Done and no Skip, and Done dismisses once`() {
        render()
        next()
        next()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").assertDoesNotExist()
        composeTestRule.onNodeWithText("Skip").assertDoesNotExist()
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, dismissCount)
    }

    @Test
    fun `Skip dismisses once`() {
        render()
        composeTestRule.onNodeWithText("Skip").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, dismissCount)
    }

    @Test
    fun `a single note shows only Done`() {
        render(releases = listOf(supporterNote))
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        composeTestRule.onNodeWithText("Skip").assertDoesNotExist()
        composeTestRule.onNodeWithText("Back").assertDoesNotExist()
        composeTestRule.onNodeWithText("Next").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Page 1 of 1").assertDoesNotExist()
    }

    @Test
    fun `page dots read the position and follow the page`() {
        render()
        composeTestRule.onNodeWithContentDescription("Page 1 of 3").assertExists()
        next()
        composeTestRule.onNodeWithContentDescription("Page 2 of 3").assertExists()
    }

    @Test
    fun `swiping left moves to the next page`() {
        render()
        composeTestRule.onNodeWithTag(WHATS_NEW_PAGER_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Page 2 of 3").assertExists()
    }

    @Test
    fun `system back on a later page goes to the previous page without dismissing`() {
        render()
        next()
        systemBack()
        composeTestRule.onNodeWithContentDescription("Page 1 of 3").assertExists()
        assertEquals(0, dismissCount)
    }

    @Test
    fun `system back on the first page dismisses`() {
        render()
        systemBack()
        assertEquals(1, dismissCount)
    }

    @Test
    fun `feature page shows the menu path to its option`() {
        render()
        composeTestRule.onNode(shown("Find it in"), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNode(shown("Make it yours"), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNode(shown("App Icon"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the path reads as one sentence to TalkBack`() {
        render()
        composeTestRule.onNode(hasText("Find it in") and hasText("App Icon")).assertExists()
    }

    @Test
    fun `the longest path wraps instead of clipping at large text`() {
        render(fontScale = 2f)
        next()
        next()
        // The title and the last chip share the setting's name, so a label can match twice.
        assertLabelsInsideSheet(
            listOf(R.string.hub_accounts_and_settings, R.string.settings_section_sync, R.string.settings_change_notifications),
        )
    }

    @Test
    fun `the 803 paths stay inside the sheet at large text`() {
        assertEquals(3, notes803.size)
        render(releases = notes803, fontScale = 2f)
        notes803.forEachIndexed { page, note ->
            if (page > 0) next()
            assertLabelsInsideSheet(note.findInRes)
        }
    }

    /** Asserts each of [ids] is shown on the current page, with width, inside the sheet. */
    private fun assertLabelsInsideSheet(ids: List<Int>) {
        // mdpi, so one px is one dp.
        val sheetRight = composeTestRule.onNodeWithTag(WHATS_NEW_PAGER_TAG).getBoundsInRoot().right.value
        ids.forEach { id ->
            val nodes = composeTestRule.onAllNodes(shown(text(id)), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("no node for ${text(id)}", nodes.isNotEmpty())
            nodes.forEach { node ->
                assertTrue("${text(id)} collapsed", node.boundsInRoot.width > 0f)
                assertTrue("${text(id)} runs past the sheet", node.boundsInRoot.right <= sheetRight + 1f)
            }
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h400dp-mdpi")
    fun `Done stays reachable on a short screen at large text`() {
        render(releases = listOf(supporterNote), fontScale = 2f)
        composeTestRule.onNodeWithText("Done").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, dismissCount)
    }

    @Test
    fun `older note keeps its link button and has no path`() {
        render(releases = listOf(supporterNote))
        composeTestRule.onNodeWithText(text(R.string.whats_new_v732_action_label)).assertIsDisplayed()
        composeTestRule.onNode(shown("Find it in"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `pages share one height while paging`() {
        render(releases = listOf(supporterNote) + featureNotes)
        val first = composeTestRule.onNodeWithTag(WHATS_NEW_PAGER_TAG).getBoundsInRoot()
        next()
        val second = composeTestRule.onNodeWithTag(WHATS_NEW_PAGER_TAG).getBoundsInRoot()
        assertTrue(first.bottom - first.top > 0.dp)
        assertEquals(first.bottom - first.top, second.bottom - second.top)
    }

    @Test
    fun `Next keeps its place when Back appears`() {
        render()
        val onFirst = composeTestRule.onNodeWithText("Next").getBoundsInRoot()
        next()
        val onSecond = composeTestRule.onNodeWithText("Next").getBoundsInRoot()
        assertEquals(onFirst, onSecond)
    }

    @Test
    fun `sizing copies hold nothing a user can tap`() {
        render(releases = listOf(supporterNote) + featureNotes)
        val tappable = composeTestRule.onAllNodes(
            hasAnyAncestor(hasTestTag(WHATS_NEW_SIZER_TAG)) and hasClickAction(),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("sizing copies expose ${tappable.size} tappable nodes", tappable.isEmpty())
    }

    @Test
    fun `system back during the page animation returns to the page the user left`() {
        render()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithText("Next").performClick()
        // A few frames in, before the scroll passes halfway.
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeByFrame()
        (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        assertEquals(0, dismissCount)
        composeTestRule.onNodeWithContentDescription("Page 1 of 3").assertExists()
    }

    @Test
    fun `next sits left of back in right-to-left layouts`() {
        render(layoutDirection = LayoutDirection.Rtl)
        next()
        val nextLeft = composeTestRule.onNodeWithText("Next").getBoundsInRoot().left
        val backLeft = composeTestRule.onNodeWithText("Back").getBoundsInRoot().left
        assertTrue(nextLeft < backLeft)
    }
}
