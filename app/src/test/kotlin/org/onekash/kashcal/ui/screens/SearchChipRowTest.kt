package org.onekash.kashcal.ui.screens

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pins the search chip row: the three chips keep one width and height at every font scale and
 * in long-word languages, counts are never cut and read "99+" past 99, a picked range shows on
 * two lines while TalkBack reads it whole, the chips form one radio group, and the order follows
 * the layout direction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class SearchChipRowTest {

    @get:Rule
    val rule = createComposeRule()

    private data class Names(val upcoming: String, val past: String, val date: String)

    /** Long translations the chips must hold; translators are asked for short words. */
    private val languages = mapOf(
        "en" to Names("Upcoming", "Past", "Date"),
        "de" to Names("Bevorstehend", "Vergangen", "Datum"),
        "ru" to Names("Предстоящие", "Прошедшие", "Дата"),
        "ml" to Names("വരാനിരിക്കുന്നവ", "കഴിഞ്ഞവ", "തീയതി"),
        "ja" to Names("今後の予定", "過去", "日付"),
    )

    private class RowState(names: Names, scale: Float, upcoming: Int?, past: Int?, dateLabel: String? = null) {
        var names by mutableStateOf(names)
        var scale by mutableStateOf(scale)
        var upcoming by mutableStateOf(upcoming)
        var past by mutableStateOf(past)
        var dateLabel by mutableStateOf(dateLabel)
    }

    private fun show(state: RowState, selected: SearchChip = SearchChip.UPCOMING, twoLines: Boolean = true) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, state.scale)) {
                KashCalTheme {
                    SearchChipRow(
                        upcomingName = state.names.upcoming,
                        pastName = state.names.past,
                        dateName = state.names.date,
                        upcomingCount = state.upcoming,
                        pastCount = state.past,
                        selected = selected,
                        dateLabel = state.dateLabel,
                        dateLabelTwoLines = twoLines,
                        onChipClick = {},
                    )
                }
            }
        }
    }

    private fun bounds(chip: String): DpRect = rule.onNodeWithTag("search_chip_$chip").getBoundsInRoot()

    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun text(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

    @Test
    fun `the three chips keep one width and height at every scale and language`() {
        val state = RowState(languages.getValue("en"), 1f, 2, 14)
        show(state)
        for ((language, names) in languages) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                state.names = names
                state.scale = scale
                rule.waitForIdle()
                val all = listOf("upcoming", "past", "date").map(::bounds)
                val where = "$language at $scale"
                assertEquals(where, 1, all.map { Math.round((it.right - it.left).value) }.toSet().size)
                assertEquals(where, 1, all.map { Math.round((it.bottom - it.top).value) }.toSet().size)
                for (chip in listOf("upcoming", "past")) {
                    assertFalse("$where: $chip count cut", layout("search_chip_count_$chip").hasVisualOverflow)
                }
            }
        }
    }

    @Test
    fun `counts read a dash before results and 99+ past 99`() {
        val state = RowState(languages.getValue("en"), 1f, null, null)
        show(state)
        assertEquals("–", text("search_chip_count_upcoming"))
        assertEquals("–", text("search_chip_count_past"))

        state.upcoming = 99
        state.past = 100
        rule.waitForIdle()
        assertEquals("99", text("search_chip_count_upcoming"))
        assertEquals("99+", text("search_chip_count_past"))
        assertFalse(layout("search_chip_count_past").hasVisualOverflow)
    }

    @Test
    fun `a picked range shows on two lines and is read whole`() {
        val range = "Dec 28, 2026 – Jan 4, 2027"
        show(RowState(languages.getValue("en"), 2f, 2, 14, dateLabel = range), selected = SearchChip.DATE)

        val node = rule.onNodeWithTag("search_chip_date").fetchSemanticsNode()
        assertEquals(listOf(range), node.config[SemanticsProperties.ContentDescription])
        rule.onNodeWithText("Dec 28, 2026 –\nJan 4, 2027", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a range breaks after the locale's separator`() {
        assertEquals("Nov 3 –\nDec 15", breakSearchRange("Nov 3 – Dec 15"))
        assertEquals("Dec 28, 2026 –\nJan 4, 2027", breakSearchRange("Dec 28, 2026 – Jan 4, 2027"))
        assertEquals("11月3日～\n12月15日", breakSearchRange("11月3日～12月15日"))
        assertEquals("Nov 3", breakSearchRange("Nov 3"))
    }

    @Test
    fun `a range shows its year only outside the current year`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val zone = ZoneId.of("UTC")
        fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val year = LocalDate.now(zone).year
        val inYear = formatSearchRange(context, ms(LocalDate.of(year, 3, 3)), ms(LocalDate.of(year, 3, 16)) - 1, zone)
        val across = formatSearchRange(context, ms(LocalDate.of(year, 12, 28)), ms(LocalDate.of(year + 1, 1, 5)) - 1, zone)

        assertFalse(inYear, inYear.contains(year.toString()))
        assertTrue(across, across.contains(year.toString()) && across.contains((year + 1).toString()))
    }

    @Test
    fun `the chips are one radio group that reads name, count and place`() {
        show(RowState(languages.getValue("en"), 1f, 2, 14), selected = SearchChip.PAST)

        val expected = listOf(
            Triple("upcoming", "Upcoming, 2 events", false),
            Triple("past", "Past, 14 events", true),
            Triple("date", "Date", false),
        )
        for ((index, item) in expected.withIndex()) {
            val (chip, description, selected) = item
            val config = rule.onNodeWithTag("search_chip_$chip").fetchSemanticsNode().config
            assertEquals(chip, Role.RadioButton, config[SemanticsProperties.Role])
            assertEquals(chip, selected, config[SemanticsProperties.Selected])
            assertEquals(chip, listOf(description), config[SemanticsProperties.ContentDescription])
            val place = config[SemanticsProperties.CollectionItemInfo]
            assertEquals(chip, listOf(0, 1, index, 1), listOf(place.rowIndex, place.rowSpan, place.columnIndex, place.columnSpan))
        }
        val group = rule.onNodeWithTag("search_chip_past").fetchSemanticsNode().parent!!
        val info = group.config[SemanticsProperties.CollectionInfo]
        assertEquals(listOf(1, 3), listOf(info.rowCount, info.columnCount))
        assertTrue(group.config.contains(SemanticsProperties.SelectableGroup))
    }

    @Test
    @Config(qualifiers = "ar-w360dp-h640dp-xxhdpi")
    fun `in a right-to-left language Upcoming sits at the start edge`() {
        show(RowState(Names("القادمة", "السابقة", "التاريخ"), 1f, 2, 14))

        val upcoming = bounds("upcoming")
        val past = bounds("past")
        val date = bounds("date")
        assertTrue(upcoming.left > past.left && past.left > date.left)
    }
}
