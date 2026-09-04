package org.onekash.vcard

/**
 * The raster image formats a contact photo can carry, recognized from the leading
 * magic bytes. Deliberately neutral — an enum, never an ez-vcard type — so it can be
 * shared across the module boundary: [VCardWriter] maps it to an ez-vcard image type
 * internally, and the device-side photo transform reuses the same sniff to decide
 * whether a thumbnail needs transcoding, without either side gaining an ez-vcard
 * dependency.
 */
enum class ImageFormat {
    JPEG,
    PNG,
    GIF,
    WEBP,
    HEIF,
    UNKNOWN;

    companion object {
        // HEIF/HEIC and related ISOBMFF image brands (the 4 chars after the 'ftyp' box tag).
        private val HEIF_BRANDS = setOf("heic", "heix", "mif1", "msf1", "heim", "heis")

        /**
         * Recognize a raster format from [bytes]' leading magic number; [UNKNOWN] when
         * nothing matches or the buffer is too short. Never throws on a short/empty buffer.
         */
        fun sniff(bytes: ByteArray): ImageFormat = when {
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> JPEG
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> PNG
            bytes.startsWith(0x47, 0x49, 0x46) -> GIF
            isWebp(bytes) -> WEBP
            isHeif(bytes) -> HEIF
            else -> UNKNOWN
        }

        // WebP is a RIFF container: 'RIFF' at offset 0, then a 4-byte size, then 'WEBP' at offset 8.
        private fun isWebp(bytes: ByteArray): Boolean =
            bytes.startsWith(0x52, 0x49, 0x46, 0x46) && bytes.matchesAt(8, "WEBP")

        // HEIF is ISOBMFF: a 4-byte box size, the 'ftyp' box tag at offset 4, then a brand at offset 8.
        private fun isHeif(bytes: ByteArray): Boolean =
            bytes.matchesAt(4, "ftyp") && bytes.brandAt(8) in HEIF_BRANDS

        private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
            size >= prefix.size && prefix.withIndex().all { (i, b) -> this[i] == b.toByte() }

        /** True if the ASCII [tag] appears verbatim starting at [offset]. */
        private fun ByteArray.matchesAt(offset: Int, tag: String): Boolean {
            if (size < offset + tag.length) return false
            return tag.indices.all { this[offset + it] == tag[it].code.toByte() }
        }

        /** The 4-char ASCII brand at [offset], or "" if the buffer is too short. */
        private fun ByteArray.brandAt(offset: Int): String {
            if (size < offset + 4) return ""
            return String(CharArray(4) { (this[offset + it].toInt() and 0xFF).toChar() })
        }
    }
}
