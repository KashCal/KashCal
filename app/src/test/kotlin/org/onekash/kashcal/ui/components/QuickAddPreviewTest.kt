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
 * Robolectric tests for the Quick Add preview's note line: the note renders when present,
 * including for a note-only parse with a blank title, and a null note renders no note text
 * while the title still shows.
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

    @Test
    fun timedUntilSeries_namesTheLastDayInTheDeviceZone() {
        // A timed series ending Jun 15 in New York carries UNTIL as Jun 16 03:59:59 UTC; the
        // preview must name the day in the zone the event is saved in.
        val saved = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        try {
            render(
                QuickAddResult(
                    title = "class",
                    startDate = LocalDate.of(2026, 4, 20),
                    startTime = LocalTime.of(9, 0),
                    rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20260616T035959Z"
                )
            )
            composeTestRule.waitForIdle()
            assertTrue("Last day must read Jun 15", textVisible("Jun 15"))
            assertFalse("Last day must not read Jun 16", textVisible("Jun 16"))
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }
}
