package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * Drives the real [WeekViewContent] to check the grid's frame: the hour column sized from its
 * widest label (and, in the 7-day view, the widest week label); the week label and the all-day
 * chevron centred; the all-day strip without its caption, toggled from its
 * blank space; chip titles cut at the edge with their times whole; dashed half-hour lines from
 * 60dp an hour; each day column read by its date with a Create event click, kept under its
 * header when the date changes; the 7-day headers drawn as the week strips' letter and circle,
 * read as their full date, with the Day view's strip sitting over them; the 3-day header on one
 * line. mdpi, so 1dp is 1px, unless a test sets a denser screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class WeekViewChromeTest {

    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate.now()
    private lateinit var savedLocale: Locale
    private lateinit var savedZone: java.util.TimeZone

    @Before
    fun saveLocale() {
        savedLocale = Locale.getDefault()
        savedZone = java.util.TimeZone.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
        java.util.TimeZone.setDefault(savedZone)
    }

    private class Calls {
        val opened = mutableListOf<String>()
        var toggles = 0
        val emptyTaps = mutableListOf<Triple<LocalDate, Int, Int>>()
        val headers = mutableListOf<LocalDate>()
    }

    private fun show(
        visibleDays: Int = 7,
        allDay: List<DisplayEvent> = emptyList(),
        timed: List<DisplayEvent> = emptyList(),
        expanded: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        weekLabelPrefix: String = "W",
        probeWidth: ((Dp) -> Unit)? = null,
        hourHeight: Float = 60f,
        clock: MutableTestClock = MutableTestClock.at(today, LocalTime.NOON),
        aboveGrid: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    ): Calls {
        val calls = Calls()
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(
                    if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
                )
            ) {
                MaterialTheme {
                    Column {
                        aboveGrid?.invoke()
                        if (probeWidth != null) {
                            probeWidth(rememberTimeColumnWidth(is24Hour = false, weekLabelPrefix = if (visibleDays == 7) weekLabelPrefix else null))
                        }
                        WeekViewContent(
                            timedEvents = timed.toImmutableList(),
                            allDayEvents = allDay.toImmutableList(),
                            isLoading = false,
                            error = null,
                            scrollPosition = 0,
                            savedScrollMinutes = 0,
                            hourHeight = hourHeight,
                            visibleDays = visibleDays,
                            weekLabelPrefix = weekLabelPrefix,
                            allDayRowsExpanded = expanded,
                            onAllDayRowsToggle = { calls.toggles++ },
                            onDatePickerRequest = {},
                            onEventClick = { calls.opened += it.title },
                            onEmptyTap = { date, hour, minute -> calls.emptyTaps += Triple(date, hour, minute) },
                            onDayHeaderClick = { calls.headers += it },
                            onScrollPositionChange = {},
                            clock = clock,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        return calls
    }

    private fun texts(): List<SemanticsNode> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { it.layoutInfo.isPlaced && it.config.contains(SemanticsActions.GetTextLayoutResult) }

    private fun textOf(node: SemanticsNode) = node.config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    private fun columnWidth() = rule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL).fetchSemanticsNode().boundsInRoot.width

    private fun strip(): Rect = rule.onNodeWithTag(TEST_TAG_ALL_DAY_STRIP).fetchSemanticsNode().boundsInRoot

    /** Two all-day events today, so the collapsed strip can toggle. */
    private fun twoToday() = listOf(
        allDayDisplayEvent(id = 1, title = "Holiday", date = today),
        allDayDisplayEvent(id = 2, title = "Birthday", date = today),
    )

    @Test
    fun `the hour column is under 48dp at the default font`() {
        show()
        assertTrue("column ${columnWidth()}", columnWidth() < 48f)
    }

    @Test
    fun `at font scale 2 every hour label fits the column whole`() {
        show(fontScale = 2f)
        val column = columnWidth()
        val labels = texts().filter { Regex("""\d{1,2}[ap]""").matches(textOf(it)) }
        assertTrue("labels found", labels.size >= 10)
        for (label in labels) {
            assertEquals("'${textOf(label)}' lines", 1, layoutOf(label).lineCount)
            assertTrue("'${textOf(label)}' ${label.size.width} wide in a $column column", label.size.width <= column)
        }
    }

    @Test
    fun `a long week label prefix is never cut, at font scale 2`() {
        Locale.setDefault(Locale.GERMANY)
        show(weekLabelPrefix = "KW", fontScale = 2f)
        val layout = layoutOf(texts().single { textOf(it).startsWith("KW") })
        val line = layout.getLineRight(0) - layout.getLineLeft(0)
        assertTrue("week label cut: line $line in ${layout.size.width}", line <= layout.size.width + 0.5f)
    }

    @Test
    fun `the column keeps its width when paging between weeks`() {
        var widest: Dp? = null
        show(probeWidth = { widest = it })
        val before = columnWidth()
        // Sized for the widest label the prefix can make, not this week's: at least "W53".
        assertTrue("column $before, W53 needs at least ${widest}", before >= requireNotNull(widest).value - 0.5f)
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(before, columnWidth(), 0.01f)
    }

    @Test
    fun `the week label is centred against the day headers`() {
        show()
        val label = texts().single { Regex("""W\d+""").matches(textOf(it)) }.boundsInRoot
        val gridTop = rule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL).fetchSemanticsNode().boundsInRoot.top
        val header = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .fetchSemanticsNodes().first { it.boundsInRoot.bottom <= gridTop && it.boundsInRoot.left > label.right }.boundsInRoot
        assertEquals(header.center.y, label.center.y, 1f)
    }

    private fun assertChevronCentred(expanded: Boolean) {
        show(allDay = twoToday(), expanded = expanded)
        val chevron = rule.onNodeWithTag(TEST_TAG_ALL_DAY_CHEVRON, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val strip = strip()
        assertEquals("vertically (expanded=$expanded)", strip.center.y, chevron.center.y, 1f)
        assertEquals("horizontally (expanded=$expanded)", columnWidth() / 2, chevron.center.x, 1f)
    }

    @Test
    fun `the chevron is centred in a collapsed strip`() = assertChevronCentred(expanded = false)

    @Test
    fun `the chevron is centred in an expanded strip`() = assertChevronCentred(expanded = true)

    @Test
    fun `the strip has no caption but still says All day to screen readers`() {
        show(allDay = twoToday())
        assertTrue(texts().none { textOf(it) == "All day" })
        val described = rule.onAllNodes(SemanticsMatcher("says All day") {
            it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains("All day")
        }).fetchSemanticsNodes()
        assertEquals(1, described.size)
    }

    @Test
    fun `every tappable item in the strip says what it is`() {
        show(allDay = twoToday())
        val strip = strip()
        val unnamed = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
            .filter { strip.overlaps(it.boundsInRoot) }
            .filter {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.isEmpty() &&
                    it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.isEmpty()
            }
        assertEquals("unnamed tappable items: ${unnamed.map { it.boundsInRoot }}", 0, unnamed.size)
    }

    @Test
    fun `a strip that can't toggle still says All day to screen readers`() {
        show(allDay = listOf(allDayDisplayEvent(id = 1, title = "Holiday", date = today)))
        val described = rule.onAllNodes(SemanticsMatcher("says All day") {
            it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains("All day")
        }).fetchSemanticsNodes()
        assertEquals(1, described.size)
        assertTrue("reachable: ${described.single().boundsInRoot}", described.single().boundsInRoot.height > 0f)
    }

    @Test
    fun `a tap on blank strip space or beside the chevron toggles the strip`() {
        val calls = show(allDay = twoToday())
        val strip = strip()
        // The far end of the strip: no chip there on a day other than today, unless today is the
        // week's last day, so use whichever end is free.
        val chips = texts().filter { textOf(it) == "Holiday" || textOf(it) == "Birthday" }.map { it.boundsInRoot }
        val blankX = if (chips.all { it.right < strip.right - 20f }) strip.right - 6f else columnWidth() + 6f
        rule.onRoot().performTouchInput { click(Offset(blankX, strip.center.y)) }
        rule.onRoot().performTouchInput { click(Offset(columnWidth() - 2f, strip.top + 4f)) }
        rule.waitForIdle()
        assertEquals(2, calls.toggles)
    }

    @Test
    fun `a tap on a chip opens its event`() {
        val calls = show(allDay = listOf(allDayDisplayEvent(id = 1, title = "Holiday", date = today)))
        val chip = texts().single { textOf(it) == "Holiday" }.boundsInRoot
        rule.onRoot().performTouchInput { click(chip.center) }
        rule.waitForIdle()
        assertEquals(listOf("Holiday"), calls.opened)
        assertEquals(0, calls.toggles)
    }

    @Test
    fun `a chip title is cut at the edge with no ellipsis and read whole`() {
        val title = "Hosting the book club for the whole weekend"
        show(allDay = listOf(allDayDisplayEvent(id = 1, title = title, date = today)))
        // The view shows event emojis by default, so the drawn title may start with one.
        val node = texts().single { textOf(it).endsWith(title) }
        assertFalse(layoutOf(node).isLineEllipsized(0))
        assertTrue("no ellipsis drawn", texts().none { textOf(it).contains("…") })
        val chip = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
            .single { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.endsWith(title) } }
        assertTrue("the title is cut by the chip", chip.boundsInRoot.width < layoutOf(node).size.width)
    }

    @Test
    fun `a right-to-left chip title shows its first letter`() {
        val title = "שיעור פסנתר ארוך מאוד עם הרבה מילים נוספות"
        show(allDay = listOf(allDayDisplayEvent(id = 1, title = title, date = today)))
        val node = texts().single { textOf(it) == title }
        val first = layoutOf(node).getBoundingBox(0)
        val chip = node.boundsInRoot
        val firstLeft = node.positionInRoot.x + first.left
        val firstRight = node.positionInRoot.x + first.right
        assertTrue("first letter $firstLeft..$firstRight outside chip $chip", firstLeft >= chip.left - 0.5f && firstRight <= chip.right + 0.5f)
    }

    @Test
    fun `a timed multi-day chip keeps its end time whole`() {
        val bar = multiDayDisplayEvent(id = 7, title = "Conference in a faraway city with a long name", startDate = today, endDate = today.plusDays(1), allDay = false)
        show(visibleDays = 3, allDay = listOf(bar))
        val times = texts().filter { Regex("""\d{1,2}:\d{2}[ap]m""").containsMatchIn(textOf(it)) }
        assertTrue("times shown: ${times.map { textOf(it) }}", times.isNotEmpty())
        for (time in times) {
            // Whole: one line, no ellipsis, and the line ends inside the text's own width.
            val layout = layoutOf(time)
            assertEquals("'${textOf(time)}' lines", 1, layout.lineCount)
            assertFalse("'${textOf(time)}' ellipsized", layout.isLineEllipsized(0))
            assertTrue("'${textOf(time)}' ends at ${layout.getLineRight(0)} in ${time.size.width}", layout.getLineRight(0) <= time.size.width + 0.5f)
        }
    }

    @Test
    fun `the Day view's width helper matches its hour column`() {
        var width: Dp? = null
        show(visibleDays = 1, probeWidth = { width = it })
        assertEquals(columnWidth(), requireNotNull(width).value, 0.5f)
    }

    /**
     * Returns how many x pixels of the rows within 1px of [hours] hours below the first hour's top
     * differ from the row 12px lower, and how many were checked.
     */
    private fun linePixelsAt(hours: Float): Pair<Int, Int> {
        val hour0 = rule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL).fetchSemanticsNode().boundsInRoot
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        // An event-free stretch of the single Day column, clear of the today border.
        val xs = (hour0.right.toInt() + 12) until (pixels.width - 12)
        val row = (hour0.top + hour0.height * hours).toInt()
        val line = xs.count { x ->
            val plain = pixels[x, row + 12]
            (row - 1..row + 1).any { r ->
                val p = pixels[x, r]
                maxOf(abs(p.red - plain.red), abs(p.green - plain.green), abs(p.blue - plain.blue), abs(p.alpha - plain.alpha)) > 0.02f
            }
        }
        return line to xs.count()
    }

    private fun assertDashedHalfHour(hourHeightDp: Float) {
        show(visibleDays = 1, hourHeight = hourHeightDp)
        val (line, run) = linePixelsAt(0.5f)
        assertTrue("dashes at ${hourHeightDp}dp: $line of $run px", line > run / 5)
        assertTrue("gaps at ${hourHeightDp}dp: $line of $run px", line < run * 4 / 5)
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    fun `a dashed half-hour line shows at 60dp an hour`() = assertDashedHalfHour(60f)

    @Test
    @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    fun `a dashed half-hour line shows zoomed in`() = assertDashedHalfHour(100f)

    @Test
    @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    fun `no half-hour line shows zoomed out`() {
        show(visibleDays = 1, hourHeight = 50f)
        val (line, run) = linePixelsAt(0.5f)
        assertEquals("line px of $run", 0, line)
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    fun `the hour line stays solid`() {
        show(visibleDays = 1, hourHeight = 60f)
        val (line, run) = linePixelsAt(1f)
        assertTrue("hour line: $line of $run px", line >= run * 95 / 100)
    }

    private fun columnDescription(date: LocalDate, isToday: Boolean) =
        org.onekash.kashcal.ui.components.AgendaWeekBarLogic.cellContentDescription(date, isToday, false, "Today", "")

    /**
     * Returns the day column items read as [date]; the Week header reads the same, so it is left
     * out by height.
     */
    private fun columnNode(date: LocalDate, isToday: Boolean) =
        rule.onAllNodes(SemanticsMatcher("column of $date") {
            it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } == listOf(columnDescription(date, isToday))
        }).fetchSemanticsNodes().filter { it.boundsInRoot.height > 200f }

    @Test
    fun `each day column is read by its date, today's with Today`() {
        show(visibleDays = 3)
        assertEquals(1, columnNode(today, isToday = true).size)
        assertEquals(1, columnNode(today.plusDays(1), isToday = false).size)
    }

    @Test
    fun `the week view's columns are read by their dates`() {
        show(visibleDays = 7)
        assertEquals(1, columnNode(today, isToday = true).size)
    }

    @Test
    fun `the day view's column is read by its date`() {
        show(visibleDays = 1)
        assertEquals(1, columnNode(today, isToday = true).size)
    }

    @Test
    fun `the column the clock calls today says Today`() {
        show(visibleDays = 3, clock = MutableTestClock.at(today.plusDays(1), LocalTime.NOON))
        assertEquals(1, columnNode(today.plusDays(1), isToday = true).size)
        assertEquals(1, columnNode(today, isToday = false).size)
    }

    @Test
    fun `a day column's Create event click calls the empty-space tap with that day and the current time`() {
        val calls = show(visibleDays = 7, clock = MutableTestClock.at(today, LocalTime.of(12, 34)))
        val day = today.plusDays(1).takeIf { columnNode(it, isToday = false).isNotEmpty() } ?: today.minusDays(1)
        val column = columnNode(day, isToday = false).single()
        val action = column.config[SemanticsActions.OnClick]
        assertEquals("Create event", action.label)
        rule.runOnIdle { action.action!!() }
        assertEquals(listOf(Triple(day, 12, 34)), calls.emptyTaps)
    }

    @Test
    fun `events stay separate screen-reader items from their column`() {
        show(visibleDays = 3, timed = listOf(roomDisplayEvent(id = 1, title = "Standup", date = today, hour = 9)))
        val column = columnNode(today, isToday = true).single()
        val said = column.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
        assertFalse("the column doesn't read the event: '$said'", said.contains("Standup"))
        val event = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
            .filter { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.contains("Standup") } }
        assertEquals(1, event.size)
    }

    @Test
    fun `day columns keep their headers' dates when the date changes under the grid`() {
        // The phone's date moves on (here by a zone change, as at midnight) while the 3-day
        // grid is shown; the next minute tick must not move the columns away from their headers.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Etc/GMT+12"))
        show(visibleDays = 3, clock = MutableTestClock(java.time.Instant.now()))
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Etc/GMT-14"))
        rule.mainClock.advanceTimeBy(61_000)
        rule.waitForIdle()
        val gridLeft = rule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL).fetchSemanticsNode().boundsInRoot.right
        val dayNumber = Regex("""(\d{1,2})(, Today)?$""")
        val columns = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.left >= gridLeft - 1f && it.boundsInRoot.right <= rule.onRoot().fetchSemanticsNode().boundsInRoot.right + 1f && it.boundsInRoot.height > 200f && it.boundsInRoot.width > 20f }
            .mapNotNull { n -> dayNumber.find(n.config[SemanticsProperties.ContentDescription].single())?.groupValues?.get(1)?.let { n.boundsInRoot to it } }
        assertEquals("three columns on screen: $columns", 3, columns.size)
        val headers = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.bottom <= rule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL).fetchSemanticsNode().boundsInRoot.top }
        for ((bounds, number) in columns) {
            val header = headers.single { it.boundsInRoot.center.x in bounds.left..bounds.right }
            val said = header.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
            assertTrue("column of day $number under header $said", number in said)
        }
    }

    /**
     * Returns, for each date of the shown week, the x centres of its number in the Day view's
     * strip (drawn first, above) and in the Week header.
     */
    private fun numberCentres(): List<Pair<Float, Float>> {
        val weekStart = WeekViewUtils.weekPageToStartDate(WeekViewUtils.CENTER_WEEK_PAGE, java.util.Calendar.SUNDAY)
        return (0L..6L).map { offset ->
            val number = weekStart.plusDays(offset).dayOfMonth.toString()
            val nodes = texts().filter { textOf(it) == number }.map { it.boundsInRoot }.sortedBy { it.top }
            assertEquals("strip and header numbers for $number: $nodes", 2, nodes.size)
            nodes[0].center.x to nodes[1].center.x
        }
    }

    private fun assertDayStripOverWeekHeader(fontScale: Float = 1f, prefix: String = "W") {
        show(visibleDays = 7, fontScale = fontScale, weekLabelPrefix = prefix, aboveGrid = {
            org.onekash.kashcal.ui.components.DayViewWeekStrip(
                shownDate = today,
                firstDayOfWeek = java.util.Calendar.SUNDAY,
                todayDayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth,
                timePattern = "h:mma",
                weekLabelPrefix = prefix,
                onDayClick = {},
            )
        })
        for ((strip, header) in numberCentres()) assertEquals("strip $strip, header $header", header, strip, 1f)
    }

    @Test
    fun `the Day strip's dates sit over the Week header's`() = assertDayStripOverWeekHeader()

    @Test
    fun `the Day strip's dates sit over the Week header's at font scale 2`() = assertDayStripOverWeekHeader(fontScale = 2f)

    @Test
    fun `the Day strip's dates sit over the Week header's with a long week label`() {
        Locale.setDefault(Locale.GERMANY)
        assertDayStripOverWeekHeader(prefix = "KW")
    }

    private fun gridTop() = rule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL).fetchSemanticsNode().boundsInRoot.top

    /** Returns the header text node showing [text], above the grid. */
    private fun headerText(text: String) = texts().single { textOf(it) == text && it.boundsInRoot.bottom <= gridTop() }

    /** Returns the header item (the tappable cell) for [date]. */
    private fun headerCell(date: LocalDate) = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
        .single { it.boundsInRoot.bottom <= gridTop() && it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { d -> d.startsWith(columnDescription(date, false)) } }

    private fun isDark(c: androidx.compose.ui.graphics.Color) = c.red < 0.4f && c.green < 0.4f && c.blue < 0.4f

    @Test
    fun `the Week header shows an 11sp letter over the number`() {
        show(visibleDays = 7)
        val letter = org.onekash.kashcal.ui.components.AgendaWeekBarLogic.narrowWeekdayLetter(today.dayOfWeek)
        val node = texts().filter { textOf(it) == letter && it.boundsInRoot.bottom <= gridTop() }
            .minBy { kotlin.math.abs(it.boundsInRoot.center.x - headerText(today.dayOfMonth.toString()).boundsInRoot.center.x) }
        assertEquals(androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp), layoutOf(node).layoutInput.style.fontSize)
    }

    @Test
    fun `today's Week header number sits in a dark 34dp circle`() {
        show(visibleDays = 7)
        val number = headerText(today.dayOfMonth.toString()).boundsInRoot
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        // 15px from the centre is inside a 34dp circle; a padded pill around the number doesn't
        // reach it.
        assertTrue("left of the number", isDark(pixels[(number.center.x - 15f).toInt(), number.center.y.toInt()]))
        assertTrue("above the number", isDark(pixels[number.center.x.toInt(), (number.center.y - 15f).toInt()]))
        assertFalse("outside the circle", isDark(pixels[(number.center.x - 19f).toInt(), number.center.y.toInt()]))
    }

    @Test
    fun `a Week header cell reads as its full date and opens it from its letter`() {
        val calls = show(visibleDays = 7)
        val cell = headerCell(today)
        assertEquals(listOf(columnDescription(today, true)), cell.config[SemanticsProperties.ContentDescription])
        assertEquals("Go to date", cell.config[SemanticsActions.OnClick].label)
        val letter = cell.boundsInRoot.let { Offset(it.center.x, it.top + 8f) }
        rule.onRoot().performTouchInput { click(letter) }
        rule.waitForIdle()
        assertEquals(listOf(today), calls.headers)
    }

    @Test
    fun `at font scale 2 today's Week header circle stays inside its cell`() {
        show(visibleDays = 7, fontScale = 2f)
        val cell = headerCell(today).boundsInRoot
        val number = headerText(today.dayOfMonth.toString()).boundsInRoot
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertTrue("the circle fills its middle", isDark(pixels[number.center.x.toInt(), (number.center.y - number.height / 2 - 2f).toInt()]))
        for (x in listOf(cell.left - 2f, cell.right + 2f).filter { it >= 0f && it < pixels.width }) {
            assertFalse("dark at $x beside the cell $cell", isDark(pixels[x.toInt(), number.center.y.toInt()]))
        }
    }

    @Test
    fun `the 3-day header keeps the name and number on one line in a pill`() {
        show(visibleDays = 3)
        val number = headerText(today.dayOfMonth.toString()).boundsInRoot
        val name = texts().single { it.boundsInRoot.bottom <= gridTop() && it.boundsInRoot.right <= number.left + 1f && it.boundsInRoot.right > number.left - 30f }.boundsInRoot
        assertEquals("one line", number.center.y, name.center.y, 1f)
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertFalse("pill, not a 34dp circle", isDark(pixels[number.center.x.toInt(), (number.center.y - 15f).toInt()]))
    }
}
