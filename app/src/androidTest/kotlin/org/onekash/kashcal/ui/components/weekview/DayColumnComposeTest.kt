package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Instrumentation tests for DayColumn blank-space tap handler.
 *
 * Locks in: tapping blank space fires onEmptyTap immediately (no 300ms
 * double-tap wait caused by the previous no-op `onDoubleTap = { }`).
 *
 * Requires connectedDebugAndroidTest (device/emulator).
 */
@RunWith(AndroidJUnit4::class)
class DayColumnComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tap_on_empty_space_fires_onEmptyTap() {
        val tapped = mutableStateOf(false)
        composeTestRule.setContent {
            MaterialTheme {
                DayColumn(
                    date = LocalDate.of(2026, 5, 3),
                    events = emptyList(),
                    onEventClick = {},
                    onOverflowClick = {},
                    onEmptyTap = { _, _, _ -> tapped.value = true },
                    modifier = Modifier.size(120.dp, 400.dp).testTag("dayColumn")
                )
            }
        }

        composeTestRule.onNodeWithTag("dayColumn").performClick()
        composeTestRule.waitForIdle()

        assertTrue("tap on blank space should fire onEmptyTap", tapped.value)
    }
}
