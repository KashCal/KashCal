package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Tests the event form's title auto-focus and keyboard dismissal.
 *
 * The title field auto-focuses (raising the keyboard) only when a new blank event
 * opens, and only once the host has settled, so the user can start typing at once.
 * It stays unfocused when editing an existing event or when the event opens with a
 * title already filled in (duplicate, share, Quick Add). A user drag on the form
 * drops focus so the fields below aren't hidden behind the keyboard, unless the drag
 * starts inside a text field, where it is the user selecting or editing text.
 *
 * Keyboard visibility isn't observable under Robolectric, so these assert the
 * proxy: focus state on the title field, at the [EventFormContent] seam (the same
 * wrapper-free seam the sibling form tests render). The scroll-source decision is
 * tested directly on [isUserDrivenScroll] and [shouldDismissKeyboardOnScroll].
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
        // A short viewport so the form scrolls. The swipe starts on a row that isn't a
        // text field. Also guards the one-shot property: once focus is cleared the title
        // is still blank and this is still create mode, yet auto-focus must not re-grab
        // it (the effect fires once on load; it doesn't watch isBlank).
        render()
        titleNode().assertIsFocused()

        swipeFrom(nonFieldNode(), up = true)

        titleNode().assertIsNotFocused()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun `a drag that starts inside the title keeps it focused`() {
        // The case from #384: a new event, title auto-focused, no tap before the drag.
        render()
        titleNode().assertIsFocused()
        val before = formScrollOffset()

        swipeFrom(titleNode(), up = true)

        assertTrue("the drag must scroll the form", formScrollOffset() != before)
        titleNode().assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi", shadows = [NoopMagnifier::class])
    fun `a drag that starts inside any form text field keeps that field focused`() {
        // Edit mode with a counted series, so the Repeat picker's "After N occurrences"
        // field is enabled once the picker is expanded; the tag field opens from its
        // "New tag" affordance. Fields are discovered by their SetText action, so an
        // unmarked text field shown in this form state fails here. Tapping a field that
        // holds text shows the magnifier, hence [NoopMagnifier].
        render(
            eventId = 1L,
            onLoadEvent = {
                eventWithTitle("Standup").copy(
                    startTs = START_TS,
                    endTs = START_TS + 3_600_000L,
                    rrule = "FREQ=DAILY;COUNT=5",
                )
            },
        )
        composeTestRule.onNodeWithContentDescription("Repeat").performScrollTo().performClick()
        composeTestRule.onNodeWithText("New tag").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val fields = composeTestRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        val count = fields.fetchSemanticsNodes().size
        assertTrue("expected at least 5 form text fields, found $count", count >= 5)

        for (i in 0 until count) {
            val field = fields[i]
            field.performScrollTo().performClick()
            // Past the double-tap timeout, so the swipe's press isn't a double-tap
            // selection drag.
            composeTestRule.mainClock.advanceTimeBy(1_000)
            composeTestRule.waitForIdle()
            field.assertIsFocused()
            val before = formScrollOffset()

            swipeFrom(field, up = canScrollForward())

            assertTrue("field $i: the drag must scroll the form", formScrollOffset() != before)
            field.assertIsFocused()
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun `a swipe outside a field after a drag inside one still clears focus`() {
        render()
        swipeFrom(titleNode(), up = true)
        titleNode().assertIsFocused()
        // The drag scrolled the row off screen; bring it back without a finger, which
        // doesn't dismiss.
        nonFieldNode().performScrollTo()
        composeTestRule.waitForIdle()
        titleNode().assertIsFocused()

        swipeFrom(nonFieldNode(), up = canScrollForward())

        titleNode().assertIsNotFocused()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun `a cancelled touch on the title does not stop a later swipe from clearing focus`() {
        render()
        titleNode().performTouchInput {
            down(center)
            cancel()
        }
        composeTestRule.waitForIdle()
        titleNode().assertIsFocused()

        swipeFrom(nonFieldNode(), up = true)

        titleNode().assertIsNotFocused()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun `a swipe from the greyed-out repeat count still clears focus`() {
        // A series with no COUNT shows the count field disabled; it is not editable, so a
        // swipe from it hides the keyboard like one from any row.
        render(
            eventId = 1L,
            onLoadEvent = {
                eventWithTitle("Standup").copy(
                    startTs = START_TS,
                    endTs = START_TS + 3_600_000L,
                    rrule = "FREQ=DAILY",
                )
            },
        )
        composeTestRule.onNodeWithContentDescription("Repeat").performScrollTo().performClick()
        titleNode().performScrollTo().performClick()
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()
        titleNode().assertIsFocused()
        val count = composeTestRule.onNode(
            hasText("10") and hasAnySibling(hasText("After")),
            useUnmergedTree = true,
        )
        count.performScrollTo()
        composeTestRule.waitForIdle()
        val before = formScrollOffset()

        swipeFrom(count, up = canScrollForward())

        assertTrue("the drag must scroll the form", formScrollOffset() != before)
        titleNode().assertIsNotFocused()
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi", shadows = [NoopMagnifier::class])
    fun `a long-press selection inside the title still selects text`() {
        // Guards that the marker, which watches every press on the field, leaves the
        // field's own selection gesture working; the gesture is real, see [NoopMagnifier].
        render()
        titleNode().performTextInput("Groceries for Sunday")
        composeTestRule.waitForIdle()

        titleNode().performTouchInput { down(Offset(60f, 60f)) }
        composeTestRule.mainClock.advanceTimeBy(900)
        titleNode().performTouchInput { repeat(20) { moveBy(Offset(10f, 0f), delayMillis = 16) } }
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.waitForIdle()

        val selection = titleNode().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertFalse("the long-press must select text", selection.collapsed)
        titleNode().performTouchInput { up() }
    }

    // Only a user-driven scroll dismisses the keyboard ([isUserDrivenScroll]). A
    // programmatic scroll, such as Compose's bring-into-view when a lower field is
    // focused or its cursor moves past the viewport, must not dismiss, or it would
    // eject the field the user just tapped or is typing in. The end-to-end
    // bring-into-view path isn't reliably reproducible under Robolectric (an
    // off-screen field can't be tapped without first scrolling it in), so the
    // decision is tested here directly; the user-drag path is covered above.
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
        // Focusing a field fires a user-input-classified scroll (the animating IME
        // inset), but with no finger down it must not dismiss.
        assertFalse(dismisses(NestedScrollSource.UserInput, isFingerDown = false))
        // A real swipe (finger down + user-input scroll) still dismisses.
        assertTrue(dismisses(NestedScrollSource.UserInput, isFingerDown = true))
        // A non-user source never dismisses, even with a finger down.
        assertFalse(dismisses(NestedScrollSource.SideEffect, isFingerDown = true))
    }

    @Test
    fun `a gesture that started in a text field never dismisses the keyboard`() {
        for (source in listOf(
            NestedScrollSource.UserInput,
            NestedScrollSource.SideEffect,
            NestedScrollSource.Relocate,
        )) {
            for (fingerDown in listOf(true, false)) {
                assertFalse(
                    shouldDismissKeyboardOnScroll(source, fingerDown, startedInTextField = true)
                )
            }
        }
    }

    private fun dismisses(source: NestedScrollSource, isFingerDown: Boolean) =
        shouldDismissKeyboardOnScroll(source, isFingerDown, startedInTextField = false)

    /** Finds the timezone row: near the top of a timed event's form, with no text field. */
    private fun nonFieldNode() = composeTestRule.onNodeWithContentDescription("Time zone")

    private fun formScrollNode() =
        composeTestRule.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(TAG_TITLE_FIELD)))

    private fun scrollRange() =
        formScrollNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    private fun formScrollOffset(): Float = scrollRange().value()

    private fun canScrollForward(): Boolean = scrollRange().let { it.value() < it.maxValue() }

    /**
     * Drags from the node's centre 300px up (or down), so the gesture starts on that node
     * and travels far enough past touch slop to scroll the form.
     */
    private fun swipeFrom(node: SemanticsNodeInteraction, up: Boolean) {
        node.performTouchInput {
            val dy = if (up) -300f else 300f
            swipe(start = center, end = center + Offset(0f, dy), durationMillis = 300)
        }
        composeTestRule.waitForIdle()
    }

    private companion object {
        // 2026-10-05 09:00 UTC.
        const val START_TS = 1_791_190_800_000L
    }

    /** Replaces the platform Magnifier, which crashes under Robolectric, with a no-op. */
    @Implements(android.widget.Magnifier::class)
    class NoopMagnifier {
        @Implementation
        protected fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

        @Implementation
        protected fun show(
            sourceCenterX: Float,
            sourceCenterY: Float,
            magnifierCenterX: Float,
            magnifierCenterY: Float,
        ) = Unit

        @Implementation
        protected fun dismiss() = Unit

        @Implementation
        protected fun update() = Unit
    }
}
