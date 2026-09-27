package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.DisplayEvent
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tests [EventBlock] gestures and the all-day label of [EventBlock] and [CompactEventBlock].
 *
 * Gestures:
 * - A tap fires onClick on a draggable block without starting a drag, and on a non-draggable
 *   one. The 300ms double-tap wait a registered `onDoubleTap` would add isn't asserted.
 * - A horizontal swipe consumed by a parent scrollable fires neither onClick nor a drag
 *   (#199: a swipe on an editable event must not open its detail).
 * - A long-press on a draggable block fires onDragStart and not onClick.
 *
 * All-day rendering:
 * - An all-day event shows the localized "All day" label, not a time range. A midnight-UTC
 *   all-day event formatted as a range reads like "7:00pm – 6:59pm" in CST, as the "+N more"
 *   overflow sheet showed. Covered for CompactEventBlock (default, 12h and 24h patterns, no
 *   "AM" or "PM" under 12h) and for EventBlock.
 *
 * Runs under connectedDebugAndroidTest on a device or emulator; unit tests don't cover it.
 */
@RunWith(AndroidJUnit4::class)
class EventBlockComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val tag = "eventBlock"

    /**
     * Returns the "All day" label in the device's locale, so the assertions hold on any locale
     * (on fr-FR it is "Toute la journée").
     */
    private val allDayLabel: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.label_all_day)

    /**
     * Builds a Room display event on a writable calendar. The defaults give a 1-hour timed event
     * starting now; [allDayDisplayEvent] passes [startTs], [durationMs] and [isAllDay] for the
     * all-day fixture.
     */
    private fun displayEvent(
        id: Long = 1L,
        title: String = "Test Event",
        startTs: Long = System.currentTimeMillis(),
        durationMs: Long = 3_600_000L,
        isAllDay: Boolean = false,
    ): DisplayEvent {
        val endTs = startTs + durationMs
        val event = Event(
            id = id,
            uid = "test-uid-$id",
            calendarId = 1L,
            title = title,
            startTs = startTs,
            endTs = endTs,
            isAllDay = isAllDay,
            timezone = "UTC",
            syncStatus = SyncStatus.SYNCED,
            createdAt = startTs,
            updatedAt = startTs,
            dtstamp = startTs
        )
        val occ = Occurrence(
            eventId = id,
            calendarId = 1L,
            startTs = startTs,
            endTs = endTs,
            startDay = 20260503,
            endDay = 20260503,
            isCancelled = false,
            exceptionEventId = null
        )
        val cal = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://example.invalid/cal/",
            displayName = "Test",
            color = 0xFF2196F3.toInt(),
            isReadOnly = false
        )
        return DisplayEvent.Room(event = event, occurrence = occ, calendar = cal)
    }

    /**
     * Builds an all-day event stored the usual way: a midnight-UTC start and a 24-hour span.
     * Formatted as a time range in a non-UTC zone it reads like "7pm – 6:59pm" (a "+N more"
     * overflow sheet in CST showed this); the label must read "All day".
     */
    private fun allDayDisplayEvent(): DisplayEvent {
        val midnightUtc = LocalDate.of(2025, 6, 3)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        return displayEvent(
            id = 2L,
            title = "Birthday",
            startTs = midnightUtc,
            durationMs = 86_400_000L,
            isAllDay = true,
        )
    }

    @Test
    fun tap_on_editable_event_fires_onClick() {
        val clicked = mutableStateOf(false)
        val dragStarted = mutableStateOf(false)
        composeTestRule.setContent {
            MaterialTheme {
                EventBlock(
                    displayEvent = displayEvent(),
                    height = 80.dp,
                    onClick = { clicked.value = true },
                    isDraggable = true,
                    onDragStart = { dragStarted.value = true },
                    modifier = Modifier.size(120.dp, 80.dp).testTag(tag)
                )
            }
        }

        composeTestRule.onNodeWithTag(tag).performClick()
        composeTestRule.waitForIdle()

        assertTrue("tap should fire onClick on editable event", clicked.value)
        assertFalse("tap should not start drag", dragStarted.value)
    }

    @Test
    fun long_press_on_editable_event_fires_onDragStart_not_onClick() {
        val clicked = mutableStateOf(false)
        val dragStarted = mutableStateOf(false)
        composeTestRule.setContent {
            MaterialTheme {
                EventBlock(
                    displayEvent = displayEvent(),
                    height = 80.dp,
                    onClick = { clicked.value = true },
                    isDraggable = true,
                    onDragStart = { dragStarted.value = true },
                    modifier = Modifier.size(120.dp, 80.dp).testTag(tag)
                )
            }
        }

        composeTestRule.onNodeWithTag(tag).performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        assertTrue("long-press should fire onDragStart", dragStarted.value)
        assertFalse("long-press should not fire onClick", clicked.value)
    }

    @Test
    fun swipe_on_editable_event_does_not_fire_onClick() {
        // A scrollable parent consumes the swipe, as the HorizontalPager around the time grid
        // does in the app.
        val clicked = mutableStateOf(false)
        val dragStarted = mutableStateOf(false)
        composeTestRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollable(
                            state = rememberScrollableState { delta -> delta },
                            orientation = Orientation.Horizontal
                        )
                ) {
                    EventBlock(
                        displayEvent = displayEvent(),
                        height = 80.dp,
                        onClick = { clicked.value = true },
                        isDraggable = true,
                        onDragStart = { dragStarted.value = true },
                        modifier = Modifier.size(120.dp, 80.dp).testTag(tag)
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(tag).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertFalse("swipe should not fire onClick (GitHub #199)", clicked.value)
        assertFalse("swipe should not start drag", dragStarted.value)
    }

    @Test
    fun tap_on_readonly_event_fires_onClick() {
        val clicked = mutableStateOf(false)
        composeTestRule.setContent {
            MaterialTheme {
                EventBlock(
                    displayEvent = displayEvent(),
                    height = 80.dp,
                    onClick = { clicked.value = true },
                    isDraggable = false,
                    onDragStart = null,
                    modifier = Modifier.size(120.dp, 80.dp).testTag(tag)
                )
            }
        }

        composeTestRule.onNodeWithTag(tag).performClick()
        composeTestRule.waitForIdle()

        assertTrue("tap should fire onClick on read-only event", clicked.value)
    }

    // ============== All-day rendering ==============

    @Test
    fun compact_event_block_renders_all_day_label_for_all_day_event() {
        composeTestRule.setContent {
            MaterialTheme {
                CompactEventBlock(
                    displayEvent = allDayDisplayEvent(),
                    onClick = {},
                    modifier = Modifier.testTag(tag)
                )
            }
        }

        // The locale-resolved label, so this passes on any device locale.
        composeTestRule.onNodeWithText(allDayLabel, substring = true).assertIsDisplayed()
    }

    @Test
    fun compact_event_block_does_not_render_12h_time_for_all_day_event() {
        composeTestRule.setContent {
            MaterialTheme {
                CompactEventBlock(
                    displayEvent = allDayDisplayEvent(),
                    onClick = {},
                    timePattern = "h:mma",
                    modifier = Modifier.testTag(tag)
                )
            }
        }

        // A start formatted with "h:mma" renders "AM" or "PM"; neither belongs on an all-day
        // label.
        composeTestRule.onAllNodesWithText("PM", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("AM", substring = true).assertCountEquals(0)
    }

    @Test
    fun compact_event_block_renders_all_day_label_under_24h_pattern() {
        composeTestRule.setContent {
            MaterialTheme {
                CompactEventBlock(
                    displayEvent = allDayDisplayEvent(),
                    onClick = {},
                    timePattern = "HH:mm",
                    modifier = Modifier.testTag(tag)
                )
            }
        }

        // Under "HH:mm" a time range would read like "19:00 – 18:59"; the localized all-day
        // label must show instead.
        composeTestRule.onNodeWithText(allDayLabel, substring = true).assertIsDisplayed()
    }

    @Test
    fun event_block_renders_all_day_label_for_all_day_event() {
        composeTestRule.setContent {
            MaterialTheme {
                EventBlock(
                    displayEvent = allDayDisplayEvent(),
                    height = 80.dp,
                    onClick = {},
                    isDraggable = false,
                    onDragStart = null,
                    modifier = Modifier.size(120.dp, 80.dp).testTag(tag)
                )
            }
        }

        composeTestRule.onNodeWithText(allDayLabel, substring = true).assertIsDisplayed()
    }
}
