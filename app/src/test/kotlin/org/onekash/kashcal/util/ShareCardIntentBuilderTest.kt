package org.onekash.kashcal.util

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the share intent builder used by [ShareCardSheet]'s Send handler.
 *
 * When both PNG and ICS are available, the builder produces a single
 * `ACTION_SEND_MULTIPLE` intent with both URIs and ClipData so receivers
 * across separate processes/tasks see the URI grants.
 *
 * When only the PNG is available (ICS export failed), the builder falls
 * back to plain `ACTION_SEND` of just the image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ShareCardIntentBuilderTest {

    private val pngUri: Uri = Uri.parse("content://com.example.fileprovider/shared/card.png")
    private val icsUri: Uri = Uri.parse("content://com.example.fileprovider/shared/event.ics")

    @Test
    fun `with both URIs, builds ACTION_SEND_MULTIPLE`() {
        val intent = ShareCardIntentBuilder.buildPayload(pngUri = pngUri, icsUri = icsUri)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
    }

    @Test
    fun `with both URIs, EXTRA_STREAM contains both URIs in image-then-ics order`() {
        val intent = ShareCardIntentBuilder.buildPayload(pngUri = pngUri, icsUri = icsUri)
        @Suppress("DEPRECATION")
        val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull(streams)
        assertEquals(2, streams!!.size)
        assertEquals(pngUri, streams[0])
        assertEquals(icsUri, streams[1])
    }

    @Test
    fun `with both URIs, type is mixed-bag wildcard`() {
        val intent = ShareCardIntentBuilder.buildPayload(pngUri = pngUri, icsUri = icsUri)
        // SEND_MULTIPLE with mixed image/png + text/calendar uses */*
        // (or a common-prefix). We use */* so all receivers accept.
        assertEquals("*/*", intent.type)
    }

    @Test
    fun `with both URIs, ClipData carries both URIs for cross-task grant propagation`() {
        val intent = ShareCardIntentBuilder.buildPayload(pngUri = pngUri, icsUri = icsUri)
        val clipData = intent.clipData
        assertNotNull(clipData)
        assertEquals(2, clipData!!.itemCount)
        assertEquals(pngUri, clipData.getItemAt(0).uri)
        assertEquals(icsUri, clipData.getItemAt(1).uri)
    }

    @Test
    fun `with both URIs, FLAG_GRANT_READ_URI_PERMISSION is set`() {
        val intent = ShareCardIntentBuilder.buildPayload(pngUri = pngUri, icsUri = icsUri)
        assertTrue(
            "FLAG_GRANT_READ_URI_PERMISSION not set",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
    }

    @Test
    fun `with only PNG, builds plain ACTION_SEND`() {
        val intent = ShareCardIntentBuilder.buildPayload(pngUri = pngUri, icsUri = null)
        assertEquals(Intent.ACTION_SEND, intent.action)
    }

    @Test
    fun `with only PNG, EXTRA_STREAM is single URI and type is image_png`() {
        val intent = ShareCardIntentBuilder.buildPayload(pngUri = pngUri, icsUri = null)
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(pngUri, stream)
        assertEquals("image/png", intent.type)
    }

    @Test
    fun `with only PNG, FLAG_GRANT_READ_URI_PERMISSION is set`() {
        val intent = ShareCardIntentBuilder.buildPayload(pngUri = pngUri, icsUri = null)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `with only PNG, no ClipData (single-URI ACTION_SEND grants natively)`() {
        val intent = ShareCardIntentBuilder.buildPayload(pngUri = pngUri, icsUri = null)
        assertNull(intent.clipData)
    }
}
