package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.ContactEmail
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.ui.components.EventFormContent
import org.onekash.kashcal.util.zone.ZoneCatalog
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Tests the time zone sheet: what shows before typing, search, loading and empty states, what
 * each tap hands back, the rows' semantics, and the form's response to a pick. en-US, device in
 * America/Chicago.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class TimeZoneSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var savedLocale: Locale
    private lateinit var savedZone: TimeZone
    private val at = Instant.parse("2026-01-15T15:00:00Z")
    private val picks = mutableListOf<ZonePick>()

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        savedZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    private fun show(
        eventZone: String? = null,
        sourceZone: String? = null,
        recents: List<String> = emptyList(),
        loadEntries: suspend (Locale, Instant) -> List<ZoneCatalog.Entry> = { l, i -> ZoneCatalog.entries(l, i) },
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                TimeZoneSheet(
                    eventZone = eventZone,
                    sourceZone = sourceZone,
                    at = at,
                    recents = recents,
                    onPick = { picks += it },
                    onDismiss = {},
                    deviceZone = "America/Chicago",
                    loadEntries = loadEntries,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    /** First row, in display order, with [description] in its screen reader text. */
    private fun row(description: String) =
        composeTestRule.onAllNodes(hasContentDescription(description, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .onFirst()

    @Test
    fun `sections show before typing and the keyboard stays closed`() {
        show(eventZone = "Europe/Berlin", recents = listOf("Asia/Tokyo"))
        composeTestRule.onNodeWithText("This event").assertExists()
        composeTestRule.onNodeWithText("Device time zone").assertExists()
        composeTestRule.onNodeWithText("Recent").assertExists()
        composeTestRule.onNodeWithText("All time zones").assertExists()
        composeTestRule.onNodeWithText("Offsets on Jan 15, 2026").assertExists()
        composeTestRule.onNode(hasTestTag(TAG_TIME_ZONE_SEARCH)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
    }

    @Test
    fun `rows read city, country, zone name, abbreviation and offset and expose selection`() {
        show(eventZone = "Europe/Berlin")
        row("Berlin, Germany, Central European Time, CET, GMT+01:00").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeTestRule.onNode(hasText("This event") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }

    @Test
    fun `search filters to ranked results`() {
        show()
        composeTestRule.onNode(hasTestTag(TAG_TIME_ZONE_SEARCH)).performTextInput("kolk")
        composeTestRule.waitForIdle()
        row("Kolkata").assertExists()
        composeTestRule.onNodeWithText("Device time zone").assertDoesNotExist()
    }

    @Test
    fun `no match shows the empty state`() {
        show()
        composeTestRule.onNode(hasTestTag(TAG_TIME_ZONE_SEARCH)).performTextInput("qqqqzz")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No time zones match").assertExists()
    }

    @Test
    fun `while the list builds a loading row shows, not the empty state`() {
        show(loadEntries = { _, _ -> awaitCancellation() })
        composeTestRule.onNodeWithText("Loading time zones…").assertExists()
        composeTestRule.onNodeWithText("No time zones match").assertDoesNotExist()
    }

    @Test
    fun `picking a zone hands back its id`() {
        show()
        composeTestRule.onNode(hasTestTag(TAG_TIME_ZONE_SEARCH)).performTextInput("tokyo")
        composeTestRule.waitForIdle()
        row("Tokyo").performClick()
        assertEquals(listOf(ZonePick.Set("Asia/Tokyo")), picks)
    }

    @Test
    fun `tapping the selected Asia Kolkata row keeps the form`() {
        show(eventZone = "Asia/Kolkata")
        composeTestRule.onNode(hasTestTag(TAG_TIME_ZONE_SEARCH)).performTextInput("kolkata")
        composeTestRule.waitForIdle()
        row("Kolkata").performClick()
        assertEquals(listOf<ZonePick>(ZonePick.Keep), picks)
    }

    @Test
    fun `with This event selected, Device switches to the device zone`() {
        show(eventZone = "Europe/Berlin")
        row("Berlin").performClick()
        row("Chicago").performClick()
        assertEquals(listOf(ZonePick.Keep, ZonePick.Device), picks)
    }

    @Test
    fun `with Device selected, tapping it changes nothing`() {
        show()
        row("Chicago").performClick()
        assertEquals(listOf<ZonePick>(ZonePick.Keep), picks)
    }

    @Test
    fun `an unreadable zone is selected and kept, and Device switches`() {
        show(sourceZone = "Foo/Bar")
        row("Shown in device time").assertExists()
        row("Foo/Bar").assertIsSelected().performClick()
        row("Chicago").performClick()
        assertEquals(listOf(ZonePick.Keep, ZonePick.Device), picks)
    }

    // ---- through the form ----

    private val formPicks = mutableListOf<String>()

    private fun showForm(timezone: String) {
        val event = Event(
            id = 1L,
            uid = "tz-sheet@test",
            calendarId = 1L,
            title = "Call",
            startTs = at.toEpochMilli(),
            endTs = at.plusSeconds(3600).toEpochMilli(),
            timezone = timezone,
            dtstamp = 0L,
        )
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    eventId = 1L,
                    onLoadEvent = { event },
                    calendars = listOf(
                        Calendar(id = 1L, accountId = 1L, caldavUrl = "https://caldav.example.test/c", displayName = "Personal", color = 0),
                    ),
                    calendarGroups = emptyList(),
                    deviceCalendarGroups = emptyList(),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { Result.success(event) },
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    onSetTagsAboveNotes = {},
                    onTimeZonePicked = { formPicks += it },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Time zone").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a pick in the form sets the zone and records it`() {
        showForm("Europe/Berlin")
        composeTestRule.onNode(hasTestTag(TAG_TIME_ZONE_SEARCH)).performTextInput("tokyo")
        composeTestRule.waitForIdle()
        row("Tokyo").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("Asia/Tokyo"), formPicks)
        assertTrue(composeTestRule.onAllNodesWithText("Tokyo (GMT+09:00)").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `Device in the form uses the device zone and records nothing`() {
        showForm("Europe/Berlin")
        row("Chicago").performClick()
        composeTestRule.waitForIdle()
        assertEquals(emptyList<String>(), formPicks)
        assertTrue(composeTestRule.onAllNodesWithText("Chicago (CST)").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `offsets are dated on the event's own day`() {
        // 17:00Z on Jan 15 is 02:00 on Jan 16 in Tokyo; the phone is in Chicago.
        composeTestRule.setContent {
            MaterialTheme {
                TimeZoneSheet(
                    eventZone = "Asia/Tokyo",
                    sourceZone = null,
                    at = Instant.parse("2026-01-15T17:00:00Z"),
                    recents = emptyList(),
                    onPick = {},
                    onDismiss = {},
                    deviceZone = "America/Chicago",
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Offsets on Jan 16, 2026").assertExists()
    }

    @Test
    fun `a row's screen reader text includes its country and abbreviation`() {
        show(eventZone = "Europe/Berlin")
        row("Berlin").assert(
            SemanticsMatcher("country and abbreviation") { node ->
                val text = node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty().joinToString()
                "Germany" in text && "CET" in text
            }
        )
    }
}
