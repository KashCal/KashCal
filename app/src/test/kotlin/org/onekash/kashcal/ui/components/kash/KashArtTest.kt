package org.onekash.kashcal.ui.components.kash

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.testutil.resolveProjectRoot
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tests Kash's art: one light and one dark drawable per pose, the files' pixel sizes and total
 * weight, the wave's shoulder pivot, that the fur follows the app's theme rather than the phone's,
 * and that the art is decorative. Run the class alone: Robolectric runs of more than one class
 * hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KashArtTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val artDir = File(resolveProjectRoot(), "app/src/main/res/drawable-nodpi")

    private val widths = mapOf(
        "kash_asleep" to 480, "kash_wink" to 480, "kash_gift" to 480, "kash_cheer" to 480,
        "kash_mug" to 480, "kash_armful" to 640, "kash_wave_body" to 640, "kash_wave_arm" to 640,
    )

    @Test
    fun `every pose and wave layer has its own light and dark drawable`() {
        val ids = KashPose.entries.flatMap { listOf(kashDrawable(it, false), kashDrawable(it, true)) } +
            listOf(false, true).flatMap { dark -> kashWaveLayers(dark).let { listOf(it.body, it.arm) } }
        assertEquals(6, KashPose.entries.size)
        assertEquals(16, ids.size)
        assertEquals("ids are distinct", 16, ids.toSet().size)
        KashPose.entries.forEach { assertNotEquals(kashDrawable(it, false), kashDrawable(it, true)) }
    }

    @Test
    fun `each art file has its pixel width and the art's shape`() {
        for ((name, width) in widths) for (suffix in listOf("", "_dark")) {
            val file = File(artDir, "$name$suffix.webp")
            assertTrue("$file exists", file.isFile)
            val (w, h) = webpSize(file.readBytes())
            assertEquals("$name$suffix width", width, w)
            assertTrue("$name$suffix height $h", abs(h - (width * 210.0 / 220).roundToInt()) <= 2)
        }
    }

    @Test
    fun `all of Kash's art together stays within 400 KB`() {
        val files = artDir.listFiles { f -> f.name.startsWith("kash_") && f.name.endsWith(".webp") }.orEmpty()
        assertEquals(16, files.size)
        assertTrue("total ${files.sumOf { it.length() }} bytes", files.sumOf { it.length() } <= 400 * 1024)
    }

    @Test
    fun `the arm waves about its shoulder`() {
        assertEquals(0.843f, KASH_WAVE_PIVOT.pivotFractionX, 0.01f)
        assertEquals(0.462f, KASH_WAVE_PIVOT.pivotFractionY, 0.01f)
    }

    @Test
    fun `a dark app theme on a light phone uses the dark fur`() {
        var dark: Boolean? = null
        composeTestRule.setContent {
            KashCalTheme(themeMode = ThemeMode.DARK, colorSource = ColorSource.SEED) { dark = kashUsesDarkFur() }
        }
        composeTestRule.waitForIdle()
        assertEquals(true, dark)
    }

    @Test
    @Config(qualifiers = "night")
    fun `a light app theme on a dark phone uses the light fur`() {
        var dark: Boolean? = null
        composeTestRule.setContent {
            KashCalTheme(themeMode = ThemeMode.LIGHT, colorSource = ColorSource.SEED) { dark = kashUsesDarkFur() }
        }
        composeTestRule.waitForIdle()
        assertEquals(false, dark)
    }

    @Test
    fun `Kash is decorative and gives TalkBack nothing to read`() {
        composeTestRule.setContent {
            KashCalTheme(colorSource = ColorSource.SEED) { KashArt(KashPose.ARMFUL) }
        }
        composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .fetchSemanticsNodes()
            .let { assertEquals("content descriptions", 0, it.size) }
    }

    /** Returns a WebP's canvas size from its header (VP8X, VP8L or VP8 chunk). */
    private fun webpSize(b: ByteArray): Pair<Int, Int> {
        fun u24(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16)
        fun u16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        assertEquals("RIFF", String(b, 0, 4))
        assertEquals("WEBP", String(b, 8, 4))
        return when (String(b, 12, 4)) {
            "VP8X" -> (u24(24) + 1) to (u24(27) + 1)
            "VP8L" -> {
                val bits = u24(21) or ((b[24].toInt() and 0xFF) shl 24)
                ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
            }
            else -> (u16(26) and 0x3FFF) to (u16(28) and 0x3FFF)
        }
    }
}
