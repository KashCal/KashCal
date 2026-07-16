package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.foundation.layout.fillMaxSize
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.ContactEmail
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the vertical ORDER of the event form's rows by rendering the real
 * [EventFormContent] (wrapper-free body) under Robolectric and comparing each
 * row's top bound. Row identity is taken from each row's icon content
 * description (the field label), which is stable across copy changes to values.
 *
 * Covers the recent layout moves: location under the title, the personal group
 * (notes + tags) above the scheduling group (attendees + free/busy), free/busy
 * as the last content row, and the tag row's move-above-notes preference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class EventFormLayoutOrderTest {

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

    private val sampleEvent = Event(
        id = 1L,
        uid = "layout-order@test",
        calendarId = 1L,
        title = "Sample",
        startTs = 0L,
        endTs = 0L,
        dtstamp = 0L,
    )

    /** Render a create-mode form. [tagsAboveNotes] flips the tag row position. */
    private fun renderForm(tagsAboveNotes: Boolean = false) {
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    // The scrollable region uses weight(1f); a bounded height is
                    // required or it collapses to zero. A very tall qualifier +
                    // fillMaxSize keeps every row laid out (not scrolled off).
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    calendars = calendars,
                    calendarGroups = emptyList(),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { Result.success(sampleEvent) },
                    // A non-null contact query + schedulable account makes the
                    // editable Attendees row render in create mode.
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    // Providing the toggle callback makes the tag row's ⋮ menu
                    // render; the boolean sets its position.
                    tagsAboveNotes = tagsAboveNotes,
                    onSetTagsAboveNotes = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Top bound (px) of the row whose icon content description is [label]. */
    private fun topOf(label: String): Float =
        composeTestRule.onNodeWithContentDescription(label, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top

    /** Top bound (px) of a node found by visible text. */
    private fun topOfText(text: String): Float =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `location renders above the date-time section`() {
        renderForm()
        // The date/time section's row carries the "All day" icon description.
        assertTrue(
            "Location must sit above the date/time row",
            topOf("Location") < topOf("All day"),
        )
    }

    @Test
    fun `location renders below the title`() {
        renderForm()
        // Empty create-mode title shows its "Event title" placeholder.
        assertTrue(
            "Location must sit below the title",
            topOfText("Event title") < topOf("Location"),
        )
    }

    @Test
    fun `personal group notes then tags sits above scheduling group attendees then free-busy`() {
        renderForm(tagsAboveNotes = false)
        val notes = topOf("Notes")
        val tags = topOf("Tags")
        val attendees = topOf("Attendees")
        val availability = topOf("Availability")

        assertTrue("Notes above Tags (default position)", notes < tags)
        assertTrue("Tags (personal group) above Attendees (scheduling group)", tags < attendees)
        assertTrue("Attendees above Free/Busy", attendees < availability)
    }

    @Test
    fun `free-busy is the last content row`() {
        renderForm(tagsAboveNotes = false)
        val availability = topOf("Availability")
        // The rows relocated this pass, plus the date/time anchor, must all
        // sit above the final Free/Busy row.
        listOf("Location", "All day", "Notes", "Tags", "Attendees")
            .forEach { label ->
                assertTrue(
                    "$label must render above the final Free/Busy row",
                    topOf(label) < availability,
                )
            }
    }

    @Test
    fun `tags above notes preference moves tags above notes but keeps it in the personal group`() {
        renderForm(tagsAboveNotes = true)
        val tags = topOf("Tags")
        val notes = topOf("Notes")
        val attendees = topOf("Attendees")

        assertTrue("Tags moves above Notes when the preference is set", tags < notes)
        assertTrue("Tags stays above the scheduling group (Attendees)", tags < attendees)
        assertTrue("Notes stays above Attendees", notes < attendees)
    }

    @Test
    fun `all core rows are present in create mode`() {
        renderForm()
        listOf("Location", "Notes", "Tags", "Attendees", "Availability")
            .forEach { composeTestRule.onNodeWithContentDescription(it, useUnmergedTree = true).assertIsDisplayed() }
    }

    @Test
    fun `device-calendar create mode hides the tag row`() {
        // Device-calendar events can't carry tags yet — the form must NOT show
        // the tag row there, else the input is invited and silently discarded
        // on save. Other core rows (Notes/Attendees) still render.
        val deviceCal = org.onekash.kashcal.data.calendar_provider.DeviceCalendar(
            id = 100L,
            displayName = "Phone",
            color = 0xFF4CAF50.toInt(),
            accountName = "local",
            accountType = "com.google",
            visible = true,
            accessLevel = 700, // >= CONTRIBUTOR (500) → isWritable
        )
        val deviceGroup = org.onekash.kashcal.ui.model.CalendarGroup(
            accountName = "Device",
            accountId = -1L,
            pickerCalendars = listOf(
                org.onekash.kashcal.ui.model.PickerCalendar.Device(deviceCal)
            ),
            isDeviceSection = true,
        )
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    calendars = calendars,
                    calendarGroups = emptyList(),
                    deviceCalendarGroups = listOf(deviceGroup),
                    defaultCalendar = DefaultCalendar.Device(100L),
                    onDismiss = {},
                    onSave = { Result.success(sampleEvent) },
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    tagsAboveNotes = false,
                    onSetTagsAboveNotes = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        // Notes still there; Tags row absent for a device calendar.
        composeTestRule.onNodeWithContentDescription("Notes", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "Tag row must not render for a device calendar",
            composeTestRule.onAllNodesWithContentDescription("Tags", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )

        // Tag *entry* must also be off: typing "#dentist" in the title on a
        // device calendar must NOT trigger the inline tag autocomplete — no
        // "Create" row appears (else committing it would strip the text and
        // drop the tag on save). The typed "#dentist" stays as literal title text.
        composeTestRule.onNodeWithText("Event title").performTextInput("Lunch #dentist")
        composeTestRule.waitForIdle()
        assertTrue(
            "Inline #tag autocomplete must not offer a Create row on a device calendar",
            composeTestRule.onAllNodesWithText("Create", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `group divider sits between the personal and scheduling groups`() {
        renderForm(tagsAboveNotes = false)
        val dividerTop = composeTestRule.onNodeWithTag(TAG_GROUP_DIVIDER, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        // Personal group (Notes/Tags) above the divider; scheduling group
        // (Attendees/Free-Busy) below it.
        assertTrue("Notes is above the group divider", topOf("Notes") < dividerTop)
        assertTrue("Tags is above the group divider", topOf("Tags") < dividerTop)
        assertTrue("Attendees is below the group divider", dividerTop < topOf("Attendees"))
        assertTrue("Free/Busy is below the group divider", dividerTop < topOf("Availability"))
    }

    @Test
    fun `create mode ends with a single divider before the save button`() {
        renderForm()
        // The sticky Save divider is present...
        composeTestRule.onNodeWithTag(TAG_SAVE_DIVIDER, useUnmergedTree = true).assertExists()
        // ...and the delete-section divider is NOT (create mode has no delete
        // button), so nothing stacks a second line before Save.
        composeTestRule.onAllNodesWithTag(TAG_DELETE_DIVIDER).fetchSemanticsNodes().let {
            assertTrue("Create mode must not render the delete-section divider", it.isEmpty())
        }
    }

    @Test
    fun `read-only hides the tag add affordance but still shows free-busy`() {
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    calendars = calendars,
                    calendarGroups = emptyList(),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { Result.success(sampleEvent) },
                    isReadOnly = true,
                    tagsAboveNotes = false,
                    onSetTagsAboveNotes = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // Tags are editor-only: no "New tag" affordance for an invitee view.
        composeTestRule.onAllNodesWithText("New tag").fetchSemanticsNodes().let {
            assertTrue("Read-only must not show the add-tag affordance", it.isEmpty())
        }
        // Free/Busy still renders (disabled chips) so availability stays visible.
        composeTestRule.onNodeWithContentDescription("Availability", useUnmergedTree = true)
            .assertIsDisplayed()
        // With blank notes + read-only the personal group is empty, so its
        // divider must NOT render (otherwise it stacks against the section
        // divider above — a double line).
        composeTestRule.onAllNodesWithTag(TAG_GROUP_DIVIDER).fetchSemanticsNodes().let {
            assertTrue("Empty personal group must not draw its divider", it.isEmpty())
        }
    }

    @Test
    fun `create-mode save carries every relocated field into the saved state`() {
        var captured: org.onekash.kashcal.ui.components.EventFormState? = null
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    calendars = calendars,
                    calendarGroups = emptyList(),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { captured = it; Result.success(sampleEvent) },
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    onSetTagsAboveNotes = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Event title").performTextInput("Standup")
        composeTestRule.onNodeWithText("Notes").performTextInput("bring laptop")
        composeTestRule.onNodeWithText("Free").performClick()
        composeTestRule.onNodeWithText("Save Event").performClick()
        composeTestRule.waitForIdle()

        val state = captured
        assertTrue("onSave must fire", state != null)
        assertTrue("title captured", state!!.title == "Standup")
        assertTrue("notes captured after its move", state.description == "bring laptop")
        assertTrue("free/busy captured at its new last-row position", state.transp == "TRANSPARENT")
    }

    @Test
    fun `typing a name that matches a suggestion commits the suggestion's casing`() {
        var captured: org.onekash.kashcal.ui.components.EventFormState? = null
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    calendars = calendars,
                    calendarGroups = emptyList(),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { captured = it; Result.success(sampleEvent) },
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    categorySuggestions = listOf("Personal"),
                    onSetTagsAboveNotes = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Event title").performTextInput("Lunch")
        // Engage the tag field and type the lowercase form of an existing tag.
        composeTestRule.onNodeWithText("New tag").performClick()
        composeTestRule.onNodeWithText("New tag").performTextInput("personal")
        composeTestRule.onNodeWithText("personal").performImeAction()
        composeTestRule.onNodeWithText("Save Event").performClick()
        composeTestRule.waitForIdle()

        val cats = captured?.categories.orEmpty()
        // First-seen casing wins: the committed tag is "Personal", not "personal".
        assertTrue("committed tag must reuse the suggestion casing, got $cats", cats.contains("Personal"))
        assertTrue("must not commit the typed lowercase variant, got $cats", !cats.contains("personal"))
    }

    @Test
    fun `a committed tag survives flipping the tag row above notes mid-edit`() {
        var above by mutableStateOf(false)
        var captured: org.onekash.kashcal.ui.components.EventFormState? = null
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    calendars = calendars,
                    calendarGroups = emptyList(),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { captured = it; Result.success(sampleEvent) },
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    tagsAboveNotes = above,
                    onSetTagsAboveNotes = { above = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Event title").performTextInput("Sync")
        // Commit a tag while the row is below notes.
        composeTestRule.onNodeWithText("New tag").performClick()
        composeTestRule.onNodeWithText("New tag").performTextInput("Work")
        composeTestRule.onNodeWithText("Create \"Work\"").performClick()
        // Flip the row above notes via its overflow menu.
        composeTestRule.onNodeWithContentDescription("Move tags", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("Move above notes").performClick()
        composeTestRule.waitForIdle()

        assertTrue("preference flipped", above)
        // The committed chip lives in hoisted state, so it survives the move.
        composeTestRule.onNodeWithContentDescription("Tag: Work", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Save Event").performClick()
        composeTestRule.waitForIdle()
        assertTrue("committed tag persists after the row move",
            captured?.categories.orEmpty().any { it.equals("Work", ignoreCase = true) })
    }
}
