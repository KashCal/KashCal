package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Drives a real [EventBlock] at a fixed size and reads what it draws: the title wrapped over the
 * lines the block fits with no ellipsis, then the location, then the time only when its range fits
 * on one line; no vertical padding, so the title starts at the block's top; the 7-day compact
 * style; the text kept above a given text height; the emoji kept with the title's first word; one
 * screen-reader item that can open the event. mdpi, so 1dp is 1px.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class EventBlockTextTest {

    @get:Rule
    val rule = createComposeRule()

    private val date = LocalDate.of(2026, 10, 14)
    private val tag = "block"

    private fun event(title: String, hour: Int = 9, durationMinutes: Int = 60, location: String? = null, transp: String = "OPAQUE") =
        roomDisplayEvent(id = 1, title = title, date = date, hour = hour, durationMinutes = durationMinutes, location = location, transp = transp)

    private fun show(
        event: DisplayEvent,
        width: Dp,
        height: Dp,
        compact: Boolean = false,
        showEmojis: Boolean = false,
        fontScale: Float = 1f,
        textHeight: Dp = height,
        onClick: () -> Unit = {},
    ) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    Box(Modifier.padding(10.dp)) {
                        EventBlock(
                            displayEvent = event,
                            height = height,
                            showEventEmojis = showEmojis,
                            compact = compact,
                            textHeight = textHeight,
                            onClick = onClick,
                            modifier = Modifier.size(width, height).testTag(tag),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun texts(): List<SemanticsNode> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { it.layoutInfo.isPlaced && it.config.contains(SemanticsActions.GetTextLayoutResult) }

    private fun textOf(node: SemanticsNode) = node.config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun node(text: String) = texts().single { textOf(it) == text }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    private fun block() = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private val longTitle = "Trains from Philippeville to Ottignies"

    @Test
    fun `a long title wraps over the lines a tall block fits with no ellipsis`() {
        show(event(longTitle, durationMinutes = 120), width = 45.dp, height = 120.dp, compact = true)
        val layout = layoutOf(node(longTitle))
        assertTrue("wrapped over ${layout.lineCount} lines", layout.lineCount > 2)
        for (line in 0 until layout.lineCount) assertFalse("line $line ellipsized", layout.isLineEllipsized(line))
    }

    @Test
    fun `a word too wide for a line is split and other words break at spaces`() {
        show(event(longTitle, durationMinutes = 120), width = 45.dp, height = 120.dp, compact = true)
        val layout = layoutOf(node(longTitle))
        val ends = (0 until layout.lineCount).map { layout.getLineEnd(it) }
        val word = longTitle.indexOf("Philippeville")
        assertTrue("a line ends inside Philippeville: $ends", ends.any { it in (word + 1) until (word + "Philippeville".length) })
        val firstEnd = ends.first()
        assertTrue("the first line ends at a space: '${longTitle.take(firstEnd)}'", longTitle[firstEnd - 1] == ' ' || longTitle.getOrNull(firstEnd) == ' ')
    }

    @Test
    fun `a wide block shows the title, then the location, then the time`() {
        show(event("Design review", location = "Room 4B"), width = 200.dp, height = 60.dp)
        val title = node("Design review").positionInRoot.y
        val location = node("Room 4B").positionInRoot.y
        val time = node("9:00am - 10:00am").positionInRoot.y
        assertTrue("title $title, location $location, time $time", title < location && location < time)
    }

    @Test
    fun `a narrow block leaves the time out instead of cutting it`() {
        show(event("Design review", location = "Room 4B"), width = 45.dp, height = 100.dp, compact = true)
        assertTrue(texts().none { textOf(it).contains("am") })
        assertTrue(texts().any { textOf(it) == "Room 4B" })
    }

    @Test
    fun `an emoji shares the first line with the start of the title`() {
        val title = "Coffee with Sarah"
        val emoji = requireNotNull(EmojiMatcher.getEmoji(title))
        show(event(title), width = 45.dp, height = 60.dp, compact = true, showEmojis = true)
        val shown = texts().single { textOf(it).endsWith(title) }
        val layout = layoutOf(shown)
        assertTrue("first line holds more than the emoji: ${layout.getLineEnd(0)}", layout.getLineEnd(0) > emoji.length + 1)
    }

    @Test
    fun `the compact style uses 11sp titles, 3dp side padding and none on top`() {
        show(event("Standup"), width = 45.dp, height = 60.dp, compact = true)
        val title = node("Standup")
        assertEquals(11.sp, layoutOf(title).layoutInput.style.fontSize)
        assertEquals(3f, title.positionInRoot.x - block().left, 0.5f)
        assertEquals(0f, title.positionInRoot.y - block().top, 0.5f)
    }

    @Test
    fun `the default style keeps 12sp titles and 6dp side padding, none on top`() {
        show(event("Standup"), width = 120.dp, height = 60.dp)
        val title = node("Standup")
        assertEquals(12.sp, layoutOf(title).layoutInput.style.fontSize)
        assertEquals(6f, title.positionInRoot.x - block().left, 0.5f)
        assertEquals(0f, title.positionInRoot.y - block().top, 0.5f)
    }

    private fun assertWholeFirstLineIn20dp(compact: Boolean) {
        // At font scale 1.15 a title line is about 18dp: it fits a 20dp block only with no top
        // padding.
        show(event(longTitle, durationMinutes = 15), width = 120.dp, height = 20.dp, compact = compact, fontScale = 1.15f)
        val title = node(longTitle)
        val layout = layoutOf(title)
        val firstLineBottom = title.positionInRoot.y + layout.getLineBottom(0)
        assertTrue("first line ends at $firstLineBottom, block at ${block().bottom}", firstLineBottom <= block().bottom + 0.5f)
    }

    @Test
    fun `a 15-minute block shows its whole first title line at font scale 1_15`() = assertWholeFirstLineIn20dp(compact = false)

    @Test
    fun `a 15-minute compact block shows its whole first title line at font scale 1_15`() = assertWholeFirstLineIn20dp(compact = true)

    @Test
    fun `tags follow the text height, not the block height`() {
        val tagged = event("Design review").let { it.copy(event = it.event.copy(categories = listOf("Work"))) }
        show(tagged, width = 200.dp, height = 120.dp, textHeight = 40.dp)
        assertTrue("no tag in 40dp of text: ${texts().map { textOf(it) }}", texts().none { textOf(it) == "Work" })
    }

    @Test
    fun `no text is drawn below the text height`() {
        show(event(longTitle, location = "Meeting Room The Classroom"), width = 45.dp, height = 120.dp, compact = true, textHeight = 30.dp)
        val limit = block().top + 30f
        for (text in texts()) {
            val layout = layoutOf(text)
            val lastLineBottom = text.positionInRoot.y + layout.getLineBottom(layout.lineCount - 1)
            assertTrue("'${textOf(text)}' ends at $lastLineBottom past $limit", lastLineBottom <= limit + 0.5f)
        }
    }

    @Test
    fun `a free event's text clears its border in the compact style`() {
        show(event("Cyclonudista ride", transp = "TRANSPARENT"), width = 45.dp, height = 80.dp, compact = true)
        assertTrue(node("Cyclonudista ride").positionInRoot.x - block().left >= 3f - 0.5f)
    }

    private fun assertNoTextPastBottom(height: Dp, fontScale: Float) {
        show(event(longTitle, location = "Meeting Room The Classroom"), width = 45.dp, height = height, compact = true, fontScale = fontScale)
        // A Text's box is held inside the block, so check its drawn lines instead: the last one
        // shown must end inside the block. Lines past maxLines aren't drawn.
        val bottom = block().bottom
        for (text in texts()) {
            val layout = layoutOf(text)
            val lastLineBottom = text.positionInRoot.y + layout.getLineBottom(layout.lineCount - 1)
            assertTrue("'${textOf(text)}' last line ends at $lastLineBottom past $bottom; lines ${layout.lineCount} " +
                "bottoms ${(0 until layout.lineCount).map { layout.getLineBottom(it) }} style lineHeight ${layout.layoutInput.style.lineHeight} " +
                "fontSize ${layout.layoutInput.style.fontSize} density ${layout.layoutInput.density.density}/${layout.layoutInput.density.fontScale} top ${text.positionInRoot.y}", lastLineBottom <= bottom + 0.5f)
        }
    }

    @Test
    fun `no text runs past the bottom of a one-hour block at font scale 2`() = assertNoTextPastBottom(60.dp, 2f)

    @Test
    fun `no text runs past the bottom of a zoomed-in block at font scale 2`() = assertNoTextPastBottom(150.dp, 2f)

    @Test
    fun `no text runs past the bottom of a zoomed-out block at font scale 1_3`() = assertNoTextPastBottom(30.dp, 1.3f)

    @Test
    fun `no text runs past the bottom of a one-hour block at the default font`() = assertNoTextPastBottom(60.dp, 1f)

    @Test
    fun `no text runs past the bottom of a zoomed-in block at the default font`() = assertNoTextPastBottom(150.dp, 1f)

    @Test
    fun `a tag added to a shown event appears`() {
        val plain = event("Design review")
        val holder = androidx.compose.runtime.mutableStateOf<DisplayEvent>(plain)
        rule.setContent {
            MaterialTheme {
                EventBlock(displayEvent = holder.value, height = 120.dp, onClick = {}, modifier = Modifier.size(200.dp, 120.dp))
            }
        }
        rule.waitForIdle()
        assertTrue(texts().none { textOf(it) == "Work" })
        holder.value = plain.copy(event = plain.event.copy(categories = listOf("Work")))
        rule.waitForIdle()
        assertTrue("tag shown: ${texts().map { textOf(it) }}", texts().any { textOf(it) == "Work" })
    }

    @Test
    fun `a block shorter than a line shows one title line`() {
        show(event(longTitle, durationMinutes = 15), width = 45.dp, height = 20.dp, compact = true, fontScale = 2f)
        assertEquals(1, layoutOf(node(longTitle)).lineCount)
    }

    @Test
    fun `the block is one screen-reader item that opens the event`() {
        var opened = 0
        show(event("Design review", location = "Room 4B"), width = 200.dp, height = 60.dp, onClick = { opened++ })
        val merged = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val said = merged.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }
        assertTrue("merged text '$said'", said.contains("Design review") && said.contains("Room 4B"))
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }
}
