package org.onekash.kashcal.domain.share

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the share-card scaling step. Regardless of the device's native
 * density (and therefore the captured bitmap's pixel size), the final PNG
 * must be exactly 1080×1350 px so chat clients render every share with
 * the same crispness.
 *
 * This is the regression test for v23.7.71 where the LocalDensity-swap
 * approach silently captured at the wrong size on some devices, causing
 * top + bottom content to be cut off.
 *
 * Robolectric — Bitmap is android.graphics.Bitmap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ShareCardScalingTest {

    @Test
    fun `scaleToShareCardOutput produces exactly 1080x1350 from a 360x450 source`() {
        // Source matches what a 1.0-density device would capture
        val source = Bitmap.createBitmap(360, 450, Bitmap.Config.ARGB_8888)
        val out = scaleToShareCardOutput(source)
        assertEquals(SHARE_CARD_PNG_WIDTH, out.width)
        assertEquals(SHARE_CARD_PNG_HEIGHT, out.height)
    }

    @Test
    fun `scaleToShareCardOutput produces exactly 1080x1350 from a 720x900 source`() {
        // Source matches what a 2.0-density device would capture
        val source = Bitmap.createBitmap(720, 900, Bitmap.Config.ARGB_8888)
        val out = scaleToShareCardOutput(source)
        assertEquals(SHARE_CARD_PNG_WIDTH, out.width)
        assertEquals(SHARE_CARD_PNG_HEIGHT, out.height)
    }

    @Test
    fun `scaleToShareCardOutput produces exactly 1080x1350 from a 1080x1350 source`() {
        // Source matches what a 3.0-density device would capture — already
        // target size; helper still returns the same dimensions.
        val source = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        val out = scaleToShareCardOutput(source)
        assertEquals(SHARE_CARD_PNG_WIDTH, out.width)
        assertEquals(SHARE_CARD_PNG_HEIGHT, out.height)
    }

    @Test
    fun `scaleToShareCardOutput produces exactly 1080x1350 from a 1440x1800 source`() {
        // Source matches what a 4.0-density (XXXHDPI) device would capture
        val source = Bitmap.createBitmap(1440, 1800, Bitmap.Config.ARGB_8888)
        val out = scaleToShareCardOutput(source)
        assertEquals(SHARE_CARD_PNG_WIDTH, out.width)
        assertEquals(SHARE_CARD_PNG_HEIGHT, out.height)
    }

    @Test
    fun `scaleToShareCardOutput recycles the source bitmap when scaling produces a new instance`() {
        val source = Bitmap.createBitmap(720, 900, Bitmap.Config.ARGB_8888)
        val out = scaleToShareCardOutput(source)
        // Different instance returned, source recycled.
        assertNotSame(source, out)
        assertTrue("source bitmap should be recycled after scaling", source.isRecycled)
    }

    @Test
    fun `target constants match the spec — 4 by 5 portrait at 1080x1350`() {
        assertEquals(1080, SHARE_CARD_PNG_WIDTH)
        assertEquals(1350, SHARE_CARD_PNG_HEIGHT)
        // 4:5 aspect ratio
        assertEquals(SHARE_CARD_PNG_WIDTH * 5, SHARE_CARD_PNG_HEIGHT * 4)
    }
}
