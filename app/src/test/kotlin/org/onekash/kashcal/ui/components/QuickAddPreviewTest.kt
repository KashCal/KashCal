package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.quickadd.QuickAddResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * Robolectric guards for the Quick Add preview's inline note line: it renders
 * the note verbatim when present and shows nothing when absent, including for a
 * note-only (blank-title) parse where the note is the only content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class QuickAddPreviewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(result: QuickAddResult) {
        composeTestRule.setContent {
            MaterialTheme {
                QuickAddPreview(result = result)
            }
        }
    }

    private fun textVisible(text: String): Boolean =
        composeTestRule.onAllNodes(hasText(text, substring = true))
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun notePresent_rendersNoteText() {
        render(
            QuickAddResult(
                title = "Lunch with Sam",
                startDate = LocalDate.of(2026, 4, 14),
                startTime = LocalTime.of(13, 0),
                note = "bring the signed contract"
            )
        )
        composeTestRule.waitForIdle()

        assertTrue("Note text must render when a note is present", textVisible("bring the signed contract"))
    }

    @Test
    fun noteAbsent_rendersNoNoteLine() {
        render(
            QuickAddResult(
                title = "Coffee",
                startDate = LocalDate.of(2026, 4, 14),
                startTime = LocalTime.of(15, 0),
                note = null
            )
        )
        composeTestRule.waitForIdle()

        assertTrue("Title should still render", textVisible("Coffee"))
        assertFalse("No note text when note is null", textVisible("bring the signed contract"))
    }

    @Test
    fun noteOnlyBlankTitle_stillRendersNote() {
        // Preview must show even when the note is the only content (blank title).
        render(
            QuickAddResult(
                title = "",
                startDate = LocalDate.of(2026, 4, 14),
                startTime = null,
                note = "just a note"
            )
        )
        composeTestRule.waitForIdle()

        assertTrue("Note-only input must still render its note", textVisible("just a note"))
    }
}
