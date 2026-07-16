package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for tag-pill rendering on [EventCard]. Verifies chips show
 * for tagged events, overflow truncates to "+N more", and untagged events add
 * no tag content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class EventCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun roomEvent(categories: List<String>?): DisplayEvent.Room {
        val event = Event(
            uid = "u1",
            calendarId = 1,
            title = "Standup",
            startTs = 1_000,
            endTs = 4_600_000,
            timezone = "UTC",
            categories = categories,
            dtstamp = 1_000,
        )
        val occ = Occurrence(
            eventId = 1,
            calendarId = 1,
            startTs = 1_000,
            endTs = 4_600_000,
            startDay = 20260101,
            endDay = 20260101,
        )
        val cal = Calendar(
            accountId = 1,
            caldavUrl = "local://d",
            displayName = "Local",
            color = 0xFF4CAF50.toInt(),
            isVisible = true,
        )
        return DisplayEvent.Room(event = event, occurrence = occ, calendar = cal)
    }

    private fun countWithText(text: String): Int =
        composeTestRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun tagged_event_shows_chips() {
        composeTestRule.setContent {
            MaterialTheme {
                EventCard(
                    displayEvent = roomEvent(listOf("Work", "Urgent")),
                    isPast = false,
                    selectedDate = 1_000,
                    onClick = {},
                )
            }
        }
        assertEquals(1, countWithText("Work"))
        assertEquals(1, countWithText("Urgent"))
    }

    @Test
    fun blank_category_from_malformed_data_renders_no_chip() {
        // A malformed pulled value like "CATEGORIES:foo,,bar" can yield an empty
        // element; it must not render as a blank chip, and must not count toward
        // the "+N more" overflow.
        composeTestRule.setContent {
            MaterialTheme {
                EventCard(
                    displayEvent = roomEvent(listOf("Work", "", "  ")),
                    isPast = false,
                    selectedDate = 1_000,
                    onClick = {},
                )
            }
        }
        assertEquals(1, countWithText("Work"))
        assertEquals(0, countWithText("more")) // only 1 real tag → no overflow badge
    }

    @Test
    fun more_than_three_tags_truncate_to_plus_n_more() {
        composeTestRule.setContent {
            MaterialTheme {
                EventCard(
                    displayEvent = roomEvent(listOf("A", "B", "C", "D", "E")),
                    isPast = false,
                    selectedDate = 1_000,
                    onClick = {},
                )
            }
        }
        // First 3 shown, remaining 2 collapsed into a "+2 more" badge.
        assertEquals(1, countWithText("A"))
        assertEquals(1, countWithText("B"))
        assertEquals(1, countWithText("C"))
        assertEquals(0, countWithText("D"))
        assertEquals(1, countWithText("2 more"))
    }

    @Test
    fun untagged_event_shows_no_tag_content() {
        composeTestRule.setContent {
            MaterialTheme {
                EventCard(
                    displayEvent = roomEvent(null),
                    isPast = false,
                    selectedDate = 1_000,
                    onClick = {},
                )
            }
        }
        assertEquals(0, countWithText("more"))
    }
}
