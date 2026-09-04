package org.onekash.vcard

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Magic-byte recognition for the raster formats a contact photo can carry. The
 * sniffer is neutral (an [ImageFormat] enum, no ez-vcard type) so both
 * [VCardWriter] and the device-side photo transform can share it across the
 * module boundary.
 */
class ImageFormatTest {

    /** Concatenate byte groups, coercing ints to bytes, into one buffer. */
    private fun bytesOf(vararg parts: Int): ByteArray =
        ByteArray(parts.size) { parts[it].toByte() }

    @Test
    fun `JPEG magic bytes sniff to JPEG`() {
        assertEquals(ImageFormat.JPEG, ImageFormat.sniff(bytesOf(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)))
    }

    @Test
    fun `PNG magic bytes sniff to PNG`() {
        assertEquals(ImageFormat.PNG, ImageFormat.sniff(bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
    }

    @Test
    fun `GIF magic bytes sniff to GIF`() {
        assertEquals(ImageFormat.GIF, ImageFormat.sniff(bytesOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)))
    }

    @Test
    fun `WEBP RIFF container with WEBP fourcc at offset 8 sniffs to WEBP`() {
        // 'RIFF' (0-3), 4-byte little-endian size (4-7, any value), 'WEBP' (8-11).
        val webp = bytesOf(
            0x52, 0x49, 0x46, 0x46, // RIFF
            0x1A, 0x00, 0x00, 0x00, // size
            0x57, 0x45, 0x42, 0x50, // WEBP
            0x56, 0x50, 0x38, 0x20, // VP8 chunk
        )
        assertEquals(ImageFormat.WEBP, ImageFormat.sniff(webp))
    }

    @Test
    fun `a RIFF container that is not WEBP does not sniff to WEBP`() {
        // 'RIFF' but 'WAVE' at offset 8 (an audio container) — must not be misread as WEBP.
        val wav = bytesOf(
            0x52, 0x49, 0x46, 0x46, // RIFF
            0x24, 0x00, 0x00, 0x00, // size
            0x57, 0x41, 0x56, 0x45, // WAVE
        )
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.sniff(wav))
    }

    @Test
    fun `HEIF ftyp box with a heic brand at offset 4 sniffs to HEIF`() {
        // 4-byte box size (0-3), 'ftyp' (4-7), 'heic' major brand (8-11).
        val heic = bytesOf(
            0x00, 0x00, 0x00, 0x18, // box size
            0x66, 0x74, 0x79, 0x70, // ftyp
            0x68, 0x65, 0x69, 0x63, // heic
            0x00, 0x00, 0x00, 0x00,
        )
        assertEquals(ImageFormat.HEIF, ImageFormat.sniff(heic))
    }

    @Test
    fun `HEIF mif1 brand sniffs to HEIF`() {
        val mif1 = bytesOf(
            0x00, 0x00, 0x00, 0x18,
            0x66, 0x74, 0x79, 0x70, // ftyp
            0x6D, 0x69, 0x66, 0x31, // mif1
        )
        assertEquals(ImageFormat.HEIF, ImageFormat.sniff(mif1))
    }

    @Test
    fun `an ftyp box with an unrelated brand is not HEIF`() {
        // 'ftyp' but an mp4 brand ('isom') — HEIF sniff must require a known image brand.
        val mp4 = bytesOf(
            0x00, 0x00, 0x00, 0x18,
            0x66, 0x74, 0x79, 0x70, // ftyp
            0x69, 0x73, 0x6F, 0x6D, // isom
        )
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.sniff(mp4))
    }

    @Test
    fun `a too-short buffer is UNKNOWN, never an index crash`() {
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.sniff(byteArrayOf(0x52, 0x49)))
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.sniff(ByteArray(0)))
    }

    @Test
    fun `an unrecognized blob is UNKNOWN`() {
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.sniff(bytesOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)))
    }
}
