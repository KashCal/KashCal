package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.ImportedAttendee
import org.onekash.kashcal.data.ics.ImportedComponent
import org.onekash.kashcal.domain.ics.GroupPlan
import org.onekash.kashcal.domain.ics.IgnoreReason
import org.onekash.kashcal.domain.ics.ImportAction
import org.onekash.kashcal.domain.ics.StoredComponent
import org.onekash.kashcal.testutil.plainImportFile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Compose tests for how [IcsImportSheet] sits on a phone-sized screen: full height for one event
 * or a whole calendar, the preview scrolling above, and Cancel and the import button always fully
 * on screen, also when the preview arrives after the sheet opened, and the target calendar above
 * them in a short window. Run the class alone: Robolectric runs of more than one class hit a
 * native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class IcsImportSheetSizeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val savedLocale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private fun calendar(id: Long, name: String) = Calendar(
        id = id, accountId = 100L, caldavUrl = "https://dav.example.test/cal/$id/",
        displayName = name, color = 0xFF4CAF50.toInt(), isVisible = true,
    )

    private val work = calendar(2L, "Work")
    private val teams = (3L..8L).map { calendar(it, "Team $it") }

    private fun events(count: Int) = (0 until count).map { i ->
        Event(uid = "e$i", calendarId = 2L, title = "Event $i", startTs = 1_000L + i * 3_600_000L,
            endTs = 1_000L + i * 3_600_000L + 1_800_000L, timezone = "UTC", dtstamp = 1_000)
    }

    private val invitation = ImportedComponent(
        events(1)[0].copy(organizerEmail = "boss@example.test"),
        listOf(ImportedAttendee("mailto:me@example.test", null, null, "NEEDS-ACTION", null, true)),
    )

    private val stored = StoredComponent(1L, null, "boss@example.test", 0, null, "CONFIRMED", false)

    /** An invitation plan that shows both notices and a line for each of [reasons]. */
    private fun longPlan(vararg reasons: IgnoreReason) = listOf(
        GroupPlan(
            uid = "a",
            actions = listOf(ImportAction.UpdateSeries(stored, invitation)) +
                reasons.map { ImportAction.Ignored(it, invitation) },
            isInvitation = true, userIsOrganizer = false, userIsAttendee = true,
        )
    )

    private val threeSkips = arrayOf(IgnoreReason.REPLY_FILE, IgnoreReason.UNSUPPORTED_METHOD, IgnoreReason.ORGANIZER_MISMATCH)
    private val lastSkipLine = "1 skipped: it comes from someone other than the meeting's organizer"
    private val unverifiedNotice = "The sender of an invitation file can't be verified. Check it came from the organizer."
    private val answerNotice = "Answering this invitation may notify the organizer."
    private var dismissed = 0

    private val loadCalls = mutableListOf<Long>()

    private fun render(
        eventCount: Int,
        calendars: List<Calendar> = listOf(work),
        loadPreview: suspend (Long, Boolean) -> List<GroupPlan> = { _, _ -> emptyList() },
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                IcsImportSheet(
                    file = plainImportFile(events(eventCount)),
                    calendars = calendars,
                    defaultCalendarId = work.id,
                    deviceCalendarGroups = emptyList(),
                    defaultDeviceCalendarId = null,
                    offlineCalendarIds = emptySet(),
                    loadPreview = { id, isDevice -> loadCalls += id; loadPreview(id, isDevice) },
                    onDismiss = { dismissed++ },
                    onImport = { _, _ -> },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun titleTop(text: String) = composeTestRule.topFraction(composeTestRule.onNodeWithText(text))

    /**
     * Asserts Cancel and the import button show whole, the button within 48dp above the sheet's
     * bottom padding.
     */
    private fun assertButtonsFullyOnScreen() {
        composeTestRule.onNodeWithText("Cancel").assertFullyOnScreen()
        val button = composeTestRule.onNodeWithTag(ICS_IMPORT_BUTTON_TAG)
        button.assertFullyOnScreen()
        val bottom = button.fetchSemanticsNode().boundsInWindow.bottom
        val windowBottom = composeTestRule.sheetWindowHeight()
        // 32dp is the sheet's own bottom padding below the buttons.
        val allowed = with(Density(composeTestRule.density.density)) { (48.dp + 32.dp).toPx() }
        assertTrue("button bottom $bottom, window bottom $windowBottom", windowBottom - bottom <= allowed)
    }

    private fun openPicker() {
        composeTestRule.onNodeWithContentDescription("Expand").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Collapse").assertExists()
    }

    // ---- full height ----

    @Test
    fun `a one-event file opens at full height`() {
        render(eventCount = 1)
        assertTrue("title at ${titleTop("Add Event")}, expected at the top", titleTop("Add Event") < 0.2f)
        assertButtonsFullyOnScreen()
    }

    @Test
    fun `a whole-calendar file opens at full height with its buttons on screen`() {
        render(eventCount = 300)
        assertTrue("title at ${titleTop("Import 300 Events")}", titleTop("Import 300 Events") < 0.2f)
        assertButtonsFullyOnScreen()
    }

    // ---- one event, the preview arrives after the sheet opened ----

    @Test
    fun `a late preview with both notices leaves Add on screen and the sheet's size alone`() {
        val preview = CompletableDeferred<List<GroupPlan>>()
        render(eventCount = 1, loadPreview = { _, _ -> preview.await() })
        val before = composeTestRule.sheetSizes()
        assertTrue("no sheet found", before.isNotEmpty())

        preview.complete(longPlan(*threeSkips))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(unverifiedNotice).assertExists()
        composeTestRule.onNodeWithText(answerNotice).assertExists()
        composeTestRule.onNodeWithText("Add").assertExists()
        assertButtonsFullyOnScreen()
        assertEquals(before, composeTestRule.sheetSizes())
    }

    // ---- a long preview scrolls above the buttons ----

    @Test
    fun `a long preview with the picker open keeps the buttons on screen and scrolls to its last line`() {
        render(eventCount = 300, calendars = listOf(work) + teams, loadPreview = { _, _ -> longPlan(*threeSkips) })
        openPicker()
        assertButtonsFullyOnScreen()

        composeTestRule.onNodeWithText(lastSkipLine).performScrollTo().assertFullyOnScreen()
        composeTestRule.onNodeWithText(answerNotice).performScrollTo()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(answerNotice).assertFullyOnScreen()
        assertButtonsFullyOnScreen()
    }

    @Test
    fun `picking another calendar closes the picker and loads its preview`() {
        render(eventCount = 300, calendars = listOf(work) + teams, loadPreview = { _, _ -> longPlan(*threeSkips) })
        openPicker()

        composeTestRule.onNodeWithText("Team 7").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Expand").assertExists()
        assertEquals(teams.single { it.displayName == "Team 7" }.id, loadCalls.last())
        assertButtonsFullyOnScreen()
    }

    @Test
    @Config(qualifiers = "en-rUS-w640dp-h360dp-mdpi")
    fun `in landscape the buttons stay on screen and the last line is reachable`() {
        render(eventCount = 300, loadPreview = { _, _ -> longPlan(*threeSkips) })
        assertButtonsFullyOnScreen()

        composeTestRule.onNodeWithText(answerNotice).performScrollTo()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(answerNotice).assertFullyOnScreen()
        assertButtonsFullyOnScreen()
    }

    @Test
    @Config(qualifiers = "en-rUS-w640dp-h360dp-mdpi")
    fun `in landscape a whole calendar still shows its target calendar without scrolling`() {
        render(eventCount = 300)

        composeTestRule.onNodeWithText("Add to calendar").assertFullyOnScreen()
        composeTestRule.onNodeWithText("Work").assertFullyOnScreen()
        assertButtonsFullyOnScreen()
    }

    // ---- drags ----

    @Test
    fun `a drag up on the content scrolls it and keeps the sheet open`() {
        render(eventCount = 300, calendars = listOf(work) + teams, loadPreview = { _, _ -> longPlan(*threeSkips) })
        openPicker()
        val body = composeTestRule.onNodeWithTag(ICS_IMPORT_BODY_TAG)
        val before = body.scrollOffset()

        body.dragUp()
        composeTestRule.waitForIdle()

        assertTrue("scrolled from $before to ${body.scrollOffset()}", body.scrollOffset() > before)
        assertEquals(0, dismissed)
        assertTrue("title at ${titleTop("Import 300 Events")}", titleTop("Import 300 Events") < 0.2f)
    }

    @Test
    fun `a drag down from the content's top closes the sheet`() {
        render(eventCount = 1)

        composeTestRule.onNodeWithTag(ICS_IMPORT_BODY_TAG).dragDown()
        composeTestRule.waitForIdle()

        assertEquals(1, dismissed)
    }
}
