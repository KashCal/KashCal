package org.onekash.kashcal.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.search.MatchField
import org.onekash.kashcal.domain.search.MatchReason
import org.onekash.kashcal.domain.search.SearchHit
import org.onekash.kashcal.domain.search.SearchOutcome
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.util.LocalDisplayZone
import org.onekash.kashcal.ui.viewmodels.SearchResultsState
import org.onekash.kashcal.ui.viewmodels.SearchTab
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.collections.immutable.toPersistentList
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Pins what a search result card and the Past list show: the matched words highlighted in the
 * title and the reason line, "Next:" and "Last:" on series dates with how far they are, the age
 * a birthday reaches on its Last date, and year headers only when Past spans years.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "en-w360dp-h2000dp-xxhdpi")
class SearchResultCardTest {

    @get:Rule
    val rule = createComposeRule()

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.now(zone)
    private val calendar = Calendar(id = 1, accountId = 1, caldavUrl = "local://a", displayName = "Work", color = 0)

    private fun ms(date: LocalDate, hour: Int = 9) = date.atStartOfDay(zone).toInstant().toEpochMilli() + hour * 3_600_000L

    private fun hit(
        title: String,
        start: Long,
        rrule: String? = null,
        reason: MatchReason? = null,
        caldavUrl: String? = null,
        description: String? = null,
        allDay: Boolean = false,
    ): SearchHit {
        val end = start + 3_600_000L
        val event = Event(
            id = start, uid = "u$start", calendarId = 1, title = title, startTs = start, endTs = end, dtstamp = 0,
            rrule = rrule, caldavUrl = caldavUrl, description = description, isAllDay = allDay,
        )
        val day = { t: Long -> java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate().let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth } }
        val occurrence = Occurrence(eventId = event.id, calendarId = 1, startTs = start, endTs = end, startDay = day(start), endDay = day(end))
        return SearchHit(DisplayEvent.Room(event, occurrence, calendar, zone = zone), start, rrule != null, false, reason)
    }

    private fun show(query: String, outcome: SearchOutcome, tab: SearchTab = SearchTab.UPCOMING) {
        rule.setContent {
            CompositionLocalProvider(LocalDisplayZone provides zone) {
                KashCalTheme {
                    SearchContent(
                        query = query,
                        state = SearchResultsState.Loaded(outcome),
                        tab = tab,
                        dateFilter = null,
                        showDateMenu = false,
                        includeHidden = false,
                        onResultClick = { _, _ -> },
                        onTabSelect = {},
                        onDateChipClick = {},
                        onDateMenuDismiss = {},
                        onDateFilterSelect = {},
                        onPickDates = {},
                    )
                }
            }
        }
    }

    private fun annotated(tag: String, index: Int = 0): AnnotatedString =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true)[index].fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()

    private fun highlights(text: AnnotatedString) = text.spanStyles.map { text.text.substring(it.start, it.end) }

    @Test
    fun `the matched words are highlighted in the title and the reason line`() {
        val reason = MatchReason(MatchField.GUEST, "Dr. Amara Lee")
        show("check lee", SearchOutcome(upcoming = listOf(hit("Checkup", ms(today.plusDays(3)), reason = reason)).toPersistentList()))

        assertEquals(listOf("Check"), highlights(annotated("search_card_title")))
        val line = annotated("search_card_reason")
        assertEquals("Guest: Dr. Amara Lee", line.text)
        assertEquals(listOf("Lee"), highlights(line))
    }

    @Test
    fun `each reason names its field`() {
        val hits = listOf(
            hit("A", ms(today.plusDays(1)), reason = MatchReason(MatchField.LOCATION, "Riverside Clinic")),
            hit("B", ms(today.plusDays(2)), reason = MatchReason(MatchField.NOTES, "…bring the card…")),
        )
        show("x", SearchOutcome(upcoming = hits.toPersistentList()))

        assertEquals("Location: Riverside Clinic", annotated("search_card_reason", 0).text)
        assertEquals("Notes: …bring the card…", annotated("search_card_reason", 1).text)
    }

    @Test
    fun `a series reads Next under Upcoming with how far it is`() {
        show("standup", SearchOutcome(upcoming = listOf(hit("Standup", ms(today.plusDays(21)), rrule = "FREQ=WEEKLY")).toPersistentList()))

        val line = annotated("search_card_date").text
        assertTrue(line, line.startsWith("Next: ") && line.endsWith(" · in 3 weeks"))
    }

    @Test
    fun `a series reads Last under Past with how far it is`() {
        val last = hit("Standup", ms(today.minusMonths(2)), rrule = "FREQ=WEEKLY")
        show("standup", SearchOutcome(past = listOf(last).toPersistentList()), tab = SearchTab.PAST)

        val line = annotated("search_card_date").text
        assertTrue(line, line.startsWith("Last: ") && line.endsWith(" · 2 months ago"))
    }

    @Test
    fun `a one-off reads its date and how far it is, with no prefix`() {
        show("dentist", SearchOutcome(upcoming = listOf(hit("Dentist", ms(today.plusDays(1)))).toPersistentList()))

        val line = annotated("search_card_date").text
        assertTrue(line, line.endsWith(" · tomorrow") && !line.startsWith("Next"))
    }

    @Test
    fun `a birthday under Past shows the age it reached on its Last date`() {
        val lastBirthday = LocalDate.of(today.year - 1, 3, 4)
        val birthday = hit(
            "Alice", ms(lastBirthday, 0), rrule = "FREQ=YEARLY", caldavUrl = "contact_birthday:abc",
            description = "birthYear:${lastBirthday.year - 30}", allDay = true,
        )
        show("alice", SearchOutcome(past = listOf(birthday).toPersistentList()), tab = SearchTab.PAST)

        assertTrue(annotated("search_card_title").text, annotated("search_card_title").text.contains("30"))
    }

    @Test
    fun `Past shows a year header before each year when it spans years`() {
        val hits = listOf(
            hit("A", ms(LocalDate.of(today.year - 1, 6, 1))),
            hit("B", ms(LocalDate.of(today.year - 1, 2, 1))),
            hit("C", ms(LocalDate.of(today.year - 3, 9, 1))),
        )
        show("x", SearchOutcome(past = hits.toPersistentList()), tab = SearchTab.PAST)

        val headers = rule.onAllNodesWithTag("search_year_header", useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].single().text }
        assertEquals(listOf((today.year - 1).toString(), (today.year - 3).toString()), headers)
    }

    @Test
    fun `Past shows no year header when every match is in one year`() {
        val hits = listOf(hit("A", ms(LocalDate.of(today.year - 1, 6, 1))), hit("B", ms(LocalDate.of(today.year - 1, 2, 1))))
        show("x", SearchOutcome(past = hits.toPersistentList()), tab = SearchTab.PAST)

        assertTrue(rule.onAllNodesWithTag("search_year_header", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `year headers follow the display zone`() {
        val newYearsEve = hit("Party", LocalDate.of(2020, 12, 31).atTime(23, 30).atZone(zone).toInstant().toEpochMilli())
        val earlier = hit("Earlier", ms(LocalDate.of(2020, 6, 1)))

        assertEquals(emptyMap<Int, Int>(), pastYearHeaders(listOf(newYearsEve, earlier), zone))
        assertEquals(mapOf(0 to 2021, 1 to 2020), pastYearHeaders(listOf(newYearsEve, earlier), ZoneId.of("Asia/Tokyo")))
    }

    private val savedLocale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)
}
