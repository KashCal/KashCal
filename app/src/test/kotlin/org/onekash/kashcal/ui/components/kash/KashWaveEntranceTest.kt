package org.onekash.kashcal.ui.components.kash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests Kash's first-run entrance: the frame at each moment of the settle and the two waves, and
 * that it plays once, in real time, and not at all with motion off. Run the class
 * alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KashWaveEntranceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `Kash starts above its place, hidden, arm down`() {
        val start = kashWaveFrame(0)
        assertEquals(-14f, start.offsetDp, 0.01f)
        assertEquals(0f, start.alpha, 0.01f)
        assertEquals(0f, start.armDegrees, 0.01f)
    }

    @Test
    fun `Kash lands with a slight squash, then rests`() {
        val landing = kashWaveFrame(300)
        assertTrue("squashed: ${landing.scaleY} < 1 < ${landing.scaleX}", landing.scaleY < 1f && landing.scaleX > 1f)
        val touchdown = kashWaveFrame(480)
        assertEquals(0f, touchdown.offsetDp, 0.01f)
        assertEquals(1f, touchdown.alpha, 0.01f)
        assertNotEquals("still settling", KashWaveFrame.REST, touchdown)
        val settled = kashWaveFrame(704)
        assertEquals(1f, settled.scaleX, 0.001f)
        assertEquals(1f, settled.scaleY, 0.001f)
    }

    @Test
    fun `Kash waves twice after landing`() {
        assertEquals(0f, kashWaveFrame(704).armDegrees, 0.01f)
        assertEquals(-18f, kashWaveFrame(960).armDegrees, 0.01f)
        assertEquals(0f, kashWaveFrame(1216).armDegrees, 0.01f)
        assertEquals(-18f, kashWaveFrame(1472).armDegrees, 0.01f)
        assertEquals(0f, kashWaveFrame(1728).armDegrees, 0.01f)
    }

    @Test
    fun `after the waves Kash stays at rest, and any time is valid`() {
        assertEquals(KashWaveFrame.REST, kashWaveFrame(KASH_WAVE_TOTAL_MS.toLong()))
        assertEquals(KashWaveFrame.REST, kashWaveFrame(60_000))
        assertEquals(kashWaveFrame(0), kashWaveFrame(-50))
    }

    @Test
    fun `it plays once, in real time`() {
        val frames = mutableListOf<KashWaveFrame>()
        var recompose by mutableIntStateOf(0)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            KashCalTheme(colorSource = ColorSource.SEED) {
                recompose.let { KashWaveEntrance(motionEnabled = true, onFrame = { frames += it }) }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        assertNotEquals("starts moving", KashWaveFrame.REST, frames.last())
        // Times count from the end of the start delay; the animation also starts about one frame
        // after the first composition, hence the slack.
        val at = { ms: Long -> composeTestRule.mainClock.advanceTimeBy(ms); frames.last() }
        val touchdown = at(KASH_WAVE_START_DELAY_MS + 464)
        assertTrue("squashed at 480 ms: ${touchdown.scaleX}", touchdown.scaleX > 1f && touchdown.scaleY < 1f)
        assertEquals(-18f, at(480).armDegrees, 2f)
        assertEquals(0f, at(256).armDegrees, 2f)
        at(1000)
        assertEquals(KashWaveFrame.REST, frames.last())
        recompose++
        composeTestRule.mainClock.advanceTimeByFrame()
        assertEquals("no replay", KashWaveFrame.REST, frames.last())
    }

    @Test
    fun `with motion off Kash is at rest from the start`() {
        val frames = mutableListOf<KashWaveFrame>()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            KashCalTheme(colorSource = ColorSource.SEED) {
                KashWaveEntrance(motionEnabled = false, onFrame = { frames += it })
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it == KashWaveFrame.REST })
    }
}
