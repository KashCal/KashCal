package org.onekash.kashcal.sync.contacts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.vcard.model.Photo
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ContactPhotoTranscoder] transcodes only the two formats strict servers drop
 * (WebP, HEIF) to JPEG and leaves everything else untouched. The real platform
 * codec is replaced with a fake via the internal constructor, so these assert the
 * ROUTING decision — Robolectric cannot decode WebP/HEIF, so real-pixel output is
 * device-verified, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContactPhotoTranscoderTest {

    private val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x11)

    private fun webpBytes() = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x1A, 0x00, 0x00, 0x00,
        0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20,
    )
    private fun heifBytes() = byteArrayOf(
        0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
        0x68, 0x65, 0x69, 0x63, 0x00, 0x00, 0x00, 0x00,
    )
    private fun jpegBytes() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private fun pngBytes() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)

    @Test
    fun `a WebP photo is transcoded to JPEG and the encoder is invoked once`() {
        var calls = 0
        val transcoder = ContactPhotoTranscoder { calls++; fakeJpeg }

        val out = transcoder.normalize(Photo(data = webpBytes(), contentType = null))

        assertEquals("encoder invoked exactly once", 1, calls)
        // contentType is cleared so the writer sniffs the fresh JPEG bytes and stamps
        // image/jpeg; a literal "jpeg" would serialize as octet-stream on a 4.0 book.
        assertNull("transcoded photo carries no contentType label", out?.contentType)
        assertArrayEquals(fakeJpeg, out?.data)
    }

    @Test
    fun `a HEIF photo is routed to the encoder`() {
        var calls = 0
        val transcoder = ContactPhotoTranscoder { calls++; fakeJpeg }

        val out = transcoder.normalize(Photo(data = heifBytes(), contentType = null))

        assertEquals(1, calls)
        assertNull("transcoded photo carries no contentType label", out?.contentType)
        assertArrayEquals(fakeJpeg, out?.data)
    }

    @Test
    fun `a JPEG photo passes through untouched, encoder not invoked`() {
        var calls = 0
        val transcoder = ContactPhotoTranscoder { calls++; fakeJpeg }
        val input = Photo(data = jpegBytes(), contentType = null)

        val out = transcoder.normalize(input)

        assertEquals("JPEG needs no transcode", 0, calls)
        assertSame("same photo returned", input, out)
    }

    @Test
    fun `a PNG photo passes through untouched`() {
        var calls = 0
        val transcoder = ContactPhotoTranscoder { calls++; fakeJpeg }
        val input = Photo(data = pngBytes(), contentType = null)

        assertSame(input, transcoder.normalize(input))
        assertEquals(0, calls)
    }

    @Test
    fun `a url-only photo passes through unchanged`() {
        val transcoder = ContactPhotoTranscoder { error("codec must not be called for a url-only photo") }
        val input = Photo(url = "https://example.test/p.jpg")

        assertSame(input, transcoder.normalize(input))
    }

    @Test
    fun `a null photo stays null`() {
        val transcoder = ContactPhotoTranscoder { error("codec must not be called for a null photo") }

        assertNull(transcoder.normalize(null))
    }

    @Test
    fun `a decode failure returning null keeps the original photo, never drops it`() {
        val transcoder = ContactPhotoTranscoder { null }
        val input = Photo(data = webpBytes(), contentType = null)

        val out = transcoder.normalize(input)

        assertSame("original photo returned when the codec fails", input, out)
    }

    @Test
    fun `a transcode that throws keeps the original photo`() {
        val transcoder = ContactPhotoTranscoder { throw RuntimeException("decoder blew up") }
        val input = Photo(data = webpBytes(), contentType = null)

        val out = transcoder.normalize(input)

        assertSame("original photo returned when the codec throws", input, out)
    }
}
