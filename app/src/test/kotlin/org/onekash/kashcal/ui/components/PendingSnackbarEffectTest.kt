package org.onekash.kashcal.ui.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for [PendingSnackbarEffect]: it shows the label it is given, reports the action
 * and the end of each message by id, shows a repeated message again under a new id, and a newer
 * id or a cleared message takes the shown snackbar down without reporting it. Run the class
 * alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h2000dp-mdpi")
class PendingSnackbarEffectTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var message by mutableStateOf<String?>(null)
    private var id by mutableLongStateOf(0L)
    private var label by mutableStateOf<String?>(null)
    private var action by mutableStateOf<(() -> Unit)?>(null)
    private val done = mutableListOf<Long>()
    private lateinit var hostState: SnackbarHostState

    private fun show(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        message = text
        label = actionLabel
        action = onAction
        id += 1
    }

    private fun setContent() {
        composeTestRule.setContent {
            hostState = remember { SnackbarHostState() }
            SnackbarHost(hostState)
            PendingSnackbarEffect(
                hostState = hostState,
                message = message,
                id = id,
                actionLabel = label,
                onAction = action,
                onDone = { done += it },
            )
        }
    }

    @Test
    fun `shows the label it is given and reports the tap then the end`() {
        var actions = 0
        setContent()
        show("Event rescheduled", "Undo") { actions++ }

        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, actions)
        assertEquals(listOf(1L), done)
    }

    @Test
    fun `a timeout reports the end without the action`() {
        var actions = 0
        setContent()
        show("Event rescheduled", "Undo") { actions++ }
        composeTestRule.onNodeWithText("Undo").assertExists()

        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()

        assertEquals(0, actions)
        assertEquals(listOf(1L), done)
    }

    @Test
    fun `the same message under a new id shows again`() {
        setContent()
        show("Event rescheduled", "Undo") {}
        composeTestRule.onNodeWithText("Event rescheduled").assertExists()
        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()
        assertEquals(listOf(1L), done)
        message = null

        show("Event rescheduled", "Undo") {}
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Event rescheduled").assertExists()
        assertEquals(listOf(1L), done)
    }

    @Test
    fun `a newer id replaces the shown snackbar without reporting the old one`() {
        setContent()
        show("First", "Undo") {}
        composeTestRule.onNodeWithText("First").assertExists()

        show("Second", "View") {}
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("First").assertDoesNotExist()
        composeTestRule.onNodeWithText("View").assertExists()
        assertEquals(emptyList<Long>(), done)
    }

    @Test
    fun `clearing the message takes the shown snackbar down without reporting it`() {
        var actions = 0
        setContent()
        show("Event rescheduled", "Undo") { actions++ }
        composeTestRule.onNodeWithText("Undo").assertExists()

        message = null
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Event rescheduled").assertDoesNotExist()
        assertNull(hostState.currentSnackbarData)
        assertEquals(0, actions)
        assertEquals(emptyList<Long>(), done)
    }

    @Test
    fun `no action means no button`() {
        setContent()
        show("Couldn't reschedule event", actionLabel = "Undo", onAction = null)

        composeTestRule.onNodeWithText("Couldn't reschedule event").assertExists()
        composeTestRule.onNodeWithText("Undo").assertDoesNotExist()
    }
}
