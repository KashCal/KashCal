package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.ContactEmail
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.util.CalendarIntentData
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The title field should auto-focus (raising the keyboard) only when a brand-new
 * blank event opens, so the user can start typing immediately. It must stay
 * unfocused when editing an existing event or when the event opens with a title
 * already filled in (duplicate, share, Quick Add) — and a scroll of the form must
 * drop focus so the fields below aren't hidden behind the keyboard.
 *
 * Keyboard visibility itself isn't observable under Robolectric, so these assert
 * the observable proxy: focus state on the title field, at the [EventFormContent]
 * seam (the same wrapper-free seam the sibling form tests render).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class EventFormAutoFocusTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val calendars = listOf(
        Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.example.test/cal1",
            displayName = "Personal",
            color = 0xFF2196F3.toInt(),
        ),
    )

    private fun eventWithTitle(title: String) = Event(
        id = 1L,
        uid = "edit-source@test",
        calendarId = 1L,
        title = title,
        startTs = 0L,
        endTs = 0L,
        dtstamp = 0L,
    )

    /** Renders EventFormContent with the minimal params; overrides steer the case. */
    private fun render(
        eventId: Long? = null,
        onLoadEvent: (suspend (Long) -> Event?)? = null,
        duplicateFrom: Event? = null,
        calendarIntentData: CalendarIntentData? = null,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    eventId = eventId,
                    onLoadEvent = onLoadEvent,
                    duplicateFrom = duplicateFrom,
                    calendarIntentData = calendarIntentData,
                    calendars = calendars,
                    calendarGroups = emptyList(),
                    deviceCalendarGroups = emptyList(),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { Result.success(eventWithTitle("Saved")) },
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    onSetTagsAboveNotes = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun titleNode() =
        composeTestRule.onNodeWithTag(TAG_TITLE_FIELD, useUnmergedTree = true)

    @Test
    fun `brand-new blank event focuses the title on open`() {
        render()
        titleNode().assertIsFocused()
    }

    @Test
    fun `editing an existing event does not focus the title`() {
        render(eventId = 1L, onLoadEvent = { eventWithTitle("Standup") })
        titleNode().assertIsNotFocused()
    }

    @Test
    fun `editing an untitled existing event still does not focus the title`() {
        // Guards that the edit gate is independent of the blank-title check: an
        // existing event with an empty title must not pull focus even though the
        // title is blank.
        render(eventId = 1L, onLoadEvent = { eventWithTitle("") })
        titleNode().assertIsNotFocused()
    }

    @Test
    fun `duplicating an event does not focus the title`() {
        render(duplicateFrom = eventWithTitle("Lunch"))
        titleNode().assertIsNotFocused()
    }

    @Test
    fun `a new event pre-filled from an intent does not focus the title`() {
        // Share-to-app / Quick Add redirect arrive via calendarIntentData; a
        // parsed title makes this a create-mode event that opens non-blank, so
        // the keyboard must not pop over the already-named event.
        render(calendarIntentData = CalendarIntentData(title = "Lunch with Sam"))
        titleNode().assertIsNotFocused()
    }

    @Test
    fun `auto-focus waits until the host sheet has settled`() {
        // The keyboard must not rise during the sheet's open animation. The host
        // reports when the sheet reaches its resting state; until then the title
        // stays unfocused, and it focuses only once the sheet has settled.
        val settled = mutableStateOf(false)
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    calendars = calendars,
                    calendarGroups = emptyList(),
                    deviceCalendarGroups = emptyList(),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { Result.success(eventWithTitle("Saved")) },
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    onSetTagsAboveNotes = {},
                    isHostSheetSettled = settled.value,
                )
            }
        }
        composeTestRule.waitForIdle()
        titleNode().assertIsNotFocused()

        settled.value = true
        composeTestRule.waitForIdle()
        titleNode().assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun `a user drag on the form clears focus from the title`() {
        // A scrollable viewport (short height) so the form actually scrolls.
        // Also guards the one-shot property: once focus is cleared here the title
        // is still blank and this is still create mode, yet auto-focus must NOT
        // re-grab it (the effect fires once on load, it doesn't watch isBlank).
        render()
        titleNode().assertIsFocused()

        titleNode().performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        titleNode().assertIsNotFocused()
    }

    // Only a user-driven scroll dismisses the keyboard. A programmatic scroll —
    // notably Compose's bring-into-view when a lower field is focused or its
    // cursor moves past the viewport — must NOT dismiss, or it would eject the
    // field the user just tapped or is typing in. The end-to-end bring-into-view
    // path isn't reliably reproducible under Robolectric (an off-screen field
    // can't be tapped without first scrolling it in), so the discriminating
    // decision is guarded here directly; the user-drag path is covered above.
    @Test
    fun `user-driven scroll dismisses the keyboard`() {
        assertTrue(isUserDrivenScroll(NestedScrollSource.UserInput))
    }

    @Test
    fun `programmatic bring-into-view scroll does not dismiss the keyboard`() {
        // Bring-into-view may dispatch as either SideEffect or Relocate depending
        // on the responder; neither is a user scroll, so neither may dismiss.
        assertFalse(isUserDrivenScroll(NestedScrollSource.SideEffect))
        assertFalse(isUserDrivenScroll(NestedScrollSource.Relocate))
    }

    @Test
    fun `keyboard dismiss requires a pressed finger`() {
        // The bug fix: focusing a field fires a user-input-classified scroll (the
        // animating IME inset), but with no finger down it must NOT dismiss.
        assertFalse(shouldDismissKeyboardOnScroll(NestedScrollSource.UserInput, isFingerDown = false))
        // A real swipe (finger down + user-input scroll) still dismisses.
        assertTrue(shouldDismissKeyboardOnScroll(NestedScrollSource.UserInput, isFingerDown = true))
        // A non-user source never dismisses, even with a finger down.
        assertFalse(shouldDismissKeyboardOnScroll(NestedScrollSource.SideEffect, isFingerDown = true))
    }
}
