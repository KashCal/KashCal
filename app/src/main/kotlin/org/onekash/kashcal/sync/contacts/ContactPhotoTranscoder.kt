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
 * Re-encodes a WebP or HEIF device contact photo as JPEG before it is pushed to a CardDAV
 * server. Every other photo (JPEG, PNG, GIF, unknown bytes, url-only, or none) passes through.
 *
 * Strict servers (Nextcloud, Zoho) drop a `PHOTO` whose bytes are WebP or HEIF. JPEG is what
 * every server accepts, and HEIF has no `Bitmap.compress` encoder, so JPEG is the only target.
 * PNG and GIF are lossless and widely stored, so they are left as they are.
 *
 * A pure byte transform with no ContentResolver write. Idempotent: a stored JPEG sniffs to
 * JPEG and passes through, so a WebP contact settles on a stable JPEG after one round on
 * servers that keep bytes as sent.
 *
 * The platform codec (ImageDecoder and Bitmap.compress) is injectable so a unit test can check
 * the routing without a real decoder (Robolectric doesn't decode WebP/HEIF); the real-pixel
 * transcode is device-verified.
 */
class ContactPhotoTranscoder @Inject constructor() {

    /**
     * The decode and encode codec: the platform one on the `@Inject` path, a fake through the
     * secondary constructor. Not a default value on the `@Inject` constructor: Hilt ignores
     * Kotlin constructor defaults and would fail to resolve a `Function1<ByteArray, ByteArray?>`
     * binding.
     */
    private var transcodeToJpeg: (ByteArray) -> ByteArray? = ::decodeAndReencodeAsJpeg

    /** Takes a fake codec so a test can check the routing without a real decoder. */
    internal constructor(transcodeToJpeg: (ByteArray) -> ByteArray?) : this() {
        this.transcodeToJpeg = transcodeToJpeg
    }

    /**
     * Returns [photo] with WebP/HEIF bytes transcoded to JPEG; any other photo is returned
     * unchanged.
     *
     * The transcoded photo's contentType is null so the writer sniffs the JPEG bytes and labels
     * the image type. A literal "jpeg" contentType doesn't match ez-vcard's predefined JPEG
     * constant (extension "jpg"), so it would serialize as `data:application/octet-stream` on a
     * 4.0 book, the mislabel a strict server drops. If the codec returns null or throws, the
     * original photo is returned, never dropped, and keeps its image-type label. Only the
     * exception type is logged.
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
 * Decodes image bytes and re-encodes them as JPEG. Forces a software bitmap because
 * [Bitmap.compress] can't read a hardware one. Returns null if the encode fails; decode errors
 * propagate to [ContactPhotoTranscoder.normalize], which keeps the original.
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
