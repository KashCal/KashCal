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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.DisplayEvent

/**
 * Instrumentation tests that lock in EventBlock gesture semantics:
 *
 * - Tap fires onClick immediately (no 300ms double-tap wait).
 * - Horizontal swipe that is consumed by a parent scrollable does NOT fire onClick
 *   (guards GitHub #199 — swipe-on-editable-event used to open event detail).
 * - Long-press on an editable, non-read-only EventBlock fires onDragStart and
 *   does NOT fire onClick.
 *
 * Requires connectedDebugAndroidTest (device/emulator). Not covered by unit tests.
 */
@RunWith(AndroidJUnit4::class)
class EventBlockComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val tag = "eventBlock"

    private fun testEvent(): DisplayEvent {
        val now = System.currentTimeMillis()
        val event = Event(
            id = 1L,
            uid = "test-uid",
            calendarId = 1L,
            title = "Test Event",
            startTs = now,
            endTs = now + 3_600_000L,
            isAllDay = false,
            timezone = "UTC",
            syncStatus = SyncStatus.SYNCED,
            createdAt = now,
            updatedAt = now,
            dtstamp = now
        )
        val occ = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = now,
            endTs = now + 3_600_000L,
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

    @Test
    fun tap_on_editable_event_fires_onClick() {
        val clicked = mutableStateOf(false)
        val dragStarted = mutableStateOf(false)
        composeTestRule.setContent {
            MaterialTheme {
                EventBlock(
                    displayEvent = testEvent(),
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
                    displayEvent = testEvent(),
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
        // Wrap in a scrollable parent so the swipe is consumed upstream,
        // matching the real app where the EventBlock lives inside HorizontalPager.
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
                        displayEvent = testEvent(),
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
                    displayEvent = testEvent(),
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
}
