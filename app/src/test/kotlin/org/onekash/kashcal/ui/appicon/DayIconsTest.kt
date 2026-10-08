package org.onekash.kashcal.ui.appicon

import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Assert.assertEquals
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
 * Tests [DayIcons]: one launcher icon per day of the month, each an adaptive icon whose themed
 * layer is the undated [R.drawable.ic_launcher_monochrome] glyph.
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
    fun `every day icon uses its own foreground and the undated themed glyph`() {
        days.forEach { day ->
            val layers = layerDrawables(DayIcons.mipmapFor(day))
            assertEquals("day $day foreground", DayIcons.foregroundFor(day), layers["foreground"])
            assertEquals("day $day monochrome", R.drawable.ic_launcher_monochrome, layers["monochrome"])
            assertEquals(
                "day $day background",
                R.color.ic_launcher_background,
                layers["background"],
            )
        }
    }

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

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
