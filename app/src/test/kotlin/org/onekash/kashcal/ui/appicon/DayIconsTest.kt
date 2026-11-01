package org.onekash.kashcal.ui.appicon

import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Tests [DayIcons]: one launcher icon per day of the month, each an adaptive icon with its own
 * dated themed layer, while the static icons keep the undated [R.drawable.ic_launcher_monochrome].
 */
@RunWith(RobolectricTestRunner::class)
// AdaptiveIconDrawable.getMonochrome is API 33.
@Config(sdk = [34])
class DayIconsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val days = 1..31

    @Test
    fun `every day of the month has its own launcher icon and foreground`() {
        assertEquals(31, days.map { DayIcons.mipmapFor(it) }.toSet().size)
        assertEquals(31, days.map { DayIcons.foregroundFor(it) }.toSet().size)
    }

    @Test
    fun `days outside 1 to 31 are rejected`() {
        listOf(0, 32, -1).forEach { day ->
            assertThrows(IllegalArgumentException::class.java) { DayIcons.mipmapFor(day) }
            assertThrows(IllegalArgumentException::class.java) { DayIcons.foregroundFor(day) }
        }
    }

    @Test
    fun `every day icon inflates as an adaptive icon with all three layers`() {
        days.forEach { day ->
            val drawable = context.getDrawable(DayIcons.mipmapFor(day))
            assertTrue("day $day is an adaptive icon", drawable is AdaptiveIconDrawable)
            val icon = drawable as AdaptiveIconDrawable
            assertNotNull("day $day foreground", icon.foreground)
            assertNotNull("day $day background", icon.background)
            assertNotNull("day $day monochrome", icon.monochrome)
        }
    }

    @Test
    fun `every day icon uses its own foreground and its own dated themed glyph`() {
        val monochromes = days.map { day ->
            val layers = layerDrawables(DayIcons.mipmapFor(day))
            assertEquals("day $day foreground", DayIcons.foregroundFor(day), layers["foreground"])
            val monochrome = layers.getValue("monochrome")
            assertEquals(
                "day $day monochrome",
                "ic_launcher_day_${day}_monochrome",
                context.resources.getResourceEntryName(monochrome),
            )
            assertEquals(
                "day $day background",
                R.color.ic_launcher_background,
                layers["background"],
            )
            monochrome
        }
        assertEquals(31, monochromes.toSet().size)
        assertFalse(R.drawable.ic_launcher_monochrome in monochromes)
    }

    @Test
    fun `every dated themed glyph is the outlined card with its own number`() {
        val numerals = days.map { day ->
            val glyph = vectorElements(themedLayerOf(day))
            val root = glyph.single { it.name == "vector" }
            assertEquals("day $day viewport width", "88.0", root.attrs["viewportWidth"])
            assertEquals("day $day viewport height", "88.0", root.attrs["viewportHeight"])
            val group = glyph.single { it.name == "group" }
            assertEquals("day $day translateX", "44.0", group.attrs["translateX"])
            assertEquals("day $day translateY", "44.0", group.attrs["translateY"])
            assertEquals("day $day scaleX", "0.9", group.attrs["scaleX"])
            assertEquals("day $day scaleY", "0.9", group.attrs["scaleY"])
            assertEquals("day $day rotation", "-2.0", group.attrs["rotation"])
            val paths = glyph.filter { it.name == "path" }
            val (stroked, filled) = paths.partition { "strokeWidth" in it.attrs }
            assertEquals(
                "day $day card and tabs",
                CARD_AND_TABS,
                stroked.map { it.attrs["pathData"] },
            )
            stroked.forEach { path ->
                assertEquals("day $day stroke width", "3.5", path.attrs["strokeWidth"])
                assertEquals("day $day line cap", ROUND, path.attrs["strokeLineCap"])
                assertEquals("day $day line join", ROUND, path.attrs["strokeLineJoin"])
                assertFalse("day $day stroked path has no fill", "fillColor" in path.attrs)
                assertEquals("day $day stroke colour", WHITE, path.attrs["strokeColor"])
            }
            val numeral = filled.single()
            assertEquals("day $day numeral colour", WHITE, numeral.attrs["fillColor"])
            assertSameNumeralMovedDown(day, numeral.attrs.getValue("pathData"))
            numeral.attrs.getValue("pathData")
        }
        assertEquals(31, numerals.toSet().size)
    }

    @Test
    fun `the default round and supporter icons keep the undated themed glyph`() {
        listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round, R.mipmap.ic_launcher_supporter)
            .forEach { mipmap ->
                val name = context.resources.getResourceEntryName(mipmap)
                assertEquals(
                    "$name monochrome",
                    R.drawable.ic_launcher_monochrome,
                    layerDrawables(mipmap)["monochrome"],
                )
            }
    }

    /**
     * Asserts [themed] is day [day]'s foreground numeral (same digits, font, size and x position)
     * moved down by [THEMED_NUMERAL_DROP], so each themed glyph shows its own day.
     */
    private fun assertSameNumeralMovedDown(day: Int, themed: String) {
        val foreground = vectorElements(DayIcons.foregroundFor(day))
            .single { it.name == "path" && it.attrs["fillColor"] == FOREGROUND_NUMERAL }
            .attrs.getValue("pathData")
        val expected = pathCommands(foreground)
        val actual = pathCommands(themed)
        assertEquals(
            "day $day numeral commands",
            expected.map { it.first },
            actual.map { it.first },
        )
        expected.zip(actual).forEachIndexed { i, (exp, act) ->
            assertEquals("day $day numeral command $i arguments", exp.second.size, act.second.size)
            exp.second.indices.forEach { j ->
                // V takes only y; H only x; every other command takes x, y pairs.
                val isY = exp.first == 'V' || (exp.first != 'H' && j % 2 == 1)
                val want = exp.second[j] + if (isY) THEMED_NUMERAL_DROP else 0.0
                // Both files round coordinates to three decimals.
                assertEquals("day $day numeral command $i argument $j", want, act.second[j], 0.002)
            }
        }
    }

    /** Splits SVG path data into each command letter and its numeric arguments. */
    private fun pathCommands(pathData: String): List<Pair<Char, List<Double>>> =
        Regex("([A-Za-z])([^A-Za-z]*)").findAll(pathData).map { match ->
            val args = Regex("-?[0-9]*\\.?[0-9]+").findAll(match.groupValues[2])
                .map { it.value.toDouble() }.toList()
            match.groupValues[1][0] to args
        }.toList()

    @Test
    fun `every day foreground is three cards side by side`() {
        days.forEach { day ->
            val parser = context.resources.getXml(DayIcons.foregroundFor(day))
            var topLevelGroups = 0
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                // The vector root is depth 1, so its direct children are depth 2.
                if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 2) {
                    assertEquals("day $day top-level element", "group", parser.name)
                    topLevelGroups++
                }
            }
            assertEquals("day $day card groups", 3, topLevelGroups)
        }
    }

    private fun themedLayerOf(day: Int): Int =
        layerDrawables(DayIcons.mipmapFor(day)).getValue("monochrome")

    /** Reads each adaptive-icon layer's `android:drawable` resource id from the compiled XML. */
    private fun layerDrawables(mipmapId: Int): Map<String, Int> {
        val parser = context.resources.getXml(mipmapId)
        val layers = mutableMapOf<String, Int>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name in setOf("foreground", "background", "monochrome")) {
                layers[parser.name] = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0)
            }
        }
        return layers
    }

    private data class Element(val name: String, val attrs: Map<String, String>)

    /** Reads every element of a compiled vector drawable with its android: attributes. */
    private fun vectorElements(drawableId: Int): List<Element> {
        val parser = context.resources.getXml(drawableId)
        val elements = mutableListOf<Element>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            val attrs = (0 until parser.attributeCount)
                .filter { parser.getAttributeNamespace(it) == ANDROID_NS }
                .associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
            elements += Element(parser.name, attrs)
        }
        return elements
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        // Compiled colour attributes read back as #AARRGGBB.
        const val WHITE = "#ffffffff"
        const val FOREGROUND_NUMERAL = "#fffdf8ec"
        // Compiled enum attributes read back as their integer value; round is 1 for cap and join.
        const val ROUND = "1"
        // The card outline, then the two binding tabs.
        val CARD_AND_TABS = listOf(
            "M-12,-17H12A6,6 0 0 1 18,-11V15A6,6 0 0 1 12,21" +
                "H-12A6,6 0 0 1 -18,15V-11A6,6 0 0 1 -12,-17Z",
            "M-9,-23V-12",
            "M9,-23V-12",
        )
        // The themed numeral's baseline (12.354, centring the digits on the card) less the
        // foreground's (9).
        const val THEMED_NUMERAL_DROP = 3.354
    }
}
