package org.onekash.kashcal.sync.contacts

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import org.onekash.vcard.ImageFormat
import org.onekash.vcard.model.Photo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Normalizes a device contact photo before it is pushed to a CardDAV server: a
 * WebP or HEIF thumbnail is decoded and re-encoded as JPEG, while every other
 * shape (JPEG/PNG/GIF/unknown bytes, a url-only photo, or no photo) passes through
 * untouched.
 *
 * Why only WebP/HEIF: strict servers (Nextcloud, Zoho) drop a `PHOTO` whose bytes
 * are WebP or HEIF; JPEG is what every server accepts, and HEIF has no
 * `Bitmap.compress` encoder anyway, so JPEG is the only viable transcode target.
 * PNG and GIF are lossless and widely stored, so they are left as-is.
 *
 * This is a READ-side, pure byte transform — it performs no ContentResolver write,
 * so it stays clear of the sync/contacts write fence. It is idempotent: a stored
 * JPEG sniffs to JPEG and passes through, so a WebP→JPEG contact converges to a
 * stable JPEG after one round on byte-preserving servers.
 *
 * The platform codec (ImageDecoder + Bitmap.compress) is injected so a unit test
 * can assert the routing decision without a real decoder (Robolectric does not
 * decode WebP/HEIF); the real-pixel transcode is device-verified.
 */
class ContactPhotoTranscoder @Inject constructor() {

    /**
     * The decode+encode codec. The [@Inject] path wires the real platform codec; a
     * test injects a fake via the secondary constructor. Deliberately NOT a default
     * value on the [@Inject] constructor — Hilt ignores Kotlin constructor defaults
     * and would try (and fail) to resolve a `Function1<ByteArray, ByteArray?>` binding.
     */
    private var transcodeToJpeg: (ByteArray) -> ByteArray? = ::decodeAndReencodeAsJpeg

    /** Test seam: supply a fake codec so routing can be asserted without a real decoder. */
    internal constructor(transcodeToJpeg: (ByteArray) -> ByteArray?) : this() {
        this.transcodeToJpeg = transcodeToJpeg
    }

    /**
     * Return [photo] with WebP/HEIF bytes transcoded to JPEG; any other photo is
     * returned unchanged. The transcoded photo's contentType is cleared to null so
     * the writer sniffs the fresh JPEG bytes and stamps the proper image type: a
     * literal "jpeg" contentType does NOT match ez-vcard's predefined JPEG constant
     * (whose extension is "jpg"), so it would serialize as data:application/octet-stream
     * on a 4.0 book — exactly the mislabel a strict server drops. Fail-safe: if the
     * codec returns null or throws, the ORIGINAL photo is returned (never dropped) so
     * its already-correct image-type label still applies. Only the exception TYPE is logged.
     */
    fun normalize(photo: Photo?): Photo? {
        val data = photo?.data ?: return photo
        return when (ImageFormat.sniff(data)) {
            ImageFormat.WEBP, ImageFormat.HEIF -> transcodeOrKeep(photo, data)
            else -> photo
        }
    }

    private fun transcodeOrKeep(photo: Photo, data: ByteArray): Photo {
        val jpeg = try {
            transcodeToJpeg(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "photo transcode failed (${e.javaClass.simpleName}); keeping original bytes")
            null
        }
        return if (jpeg != null) photo.copy(data = jpeg, contentType = null) else photo
    }
}

private const val TAG = "ContactPhotoTranscoder"
private const val JPEG_QUALITY = 90

/**
 * Decode arbitrary image bytes and re-encode as JPEG. Forces a software bitmap so
 * [Bitmap.compress] can read it back (a hardware bitmap cannot). Returns null if
 * the encode step reports failure; decode errors propagate to the fail-safe caller.
 */
private fun decodeAndReencodeAsJpeg(data: ByteArray): ByteArray? {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(data))
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    return ByteArrayOutputStream().use { out ->
        if (bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) out.toByteArray() else null
    }
}
