package org.onekash.kashcal.network

import android.util.Log
import okhttp3.Response
import java.io.IOException

private const val TAG = "HttpResponseBodyReader"

/**
 * Caps the bytes buffered from an HTTP response body.
 *
 * Bodies are read whole onto the heap for parsing (CalDAV multistatus XML, ICS feeds), so the
 * cap is an OOM backstop against a malicious or malformed server. 50 MB clears real payloads
 * (CalDAV multiget batches are well under 1 MB; large public ICS feeds run to tens of MB) and
 * still rejects the hundreds-of-MB bodies that would exhaust a default Android heap.
 *
 * The cap covers the whole body. KashCal doesn't parse or store inline attachments (ATTACH);
 * if it starts to, this becomes the attachment ceiling and large attachments should stream to
 * disk instead of being buffered as a String.
 */
const val MAX_HTTP_RESPONSE_SIZE_BYTES: Long = 50L * 1024 * 1024

/**
 * Thrown by [readBoundedBody] and [readBoundedBytes] when a body exceeds the size limit.
 *
 * An [IOException], so a broad `catch (IOException)` treats it as a network failure; a caller
 * that wants a distinct "too large" message catches this type first.
 */
class ResponseTooLargeException(message: String) : IOException(message)

/**
 * Reads this response's body into a String, rejecting bodies over [maxBytes]. Always closes
 * the body.
 *
 * Decodes with the Content-Type charset, falling back to UTF-8. A null body reads as "".
 *
 * @throws ResponseTooLargeException if the body exceeds [maxBytes], detected from the
 *   Content-Length header before buffering or, without one (chunked or streaming), from the
 *   buffered size.
 */
fun Response.readBoundedBody(maxBytes: Long = MAX_HTTP_RESPONSE_SIZE_BYTES): String {
    val body = this.body ?: return ""
    return body.use { b ->
        val source = b.source()
        val contentLength = b.contentLength()
        // contentLength is -1 when unknown (chunked or streaming); the buffered-size check
        // below catches those.
        if (contentLength > maxBytes) {
            Log.w(TAG, "Response rejected: Content-Length $contentLength exceeds limit")
            throw ResponseTooLargeException(
                "Response too large: Content-Length $contentLength exceeds ${maxBytes / 1024 / 1024}MB"
            )
        }
        source.request(maxBytes + 1)
        if (source.buffer.size > maxBytes) {
            Log.w(TAG, "Response rejected: buffered ${source.buffer.size} bytes exceeds limit")
            throw ResponseTooLargeException(
                "Response too large: buffered ${source.buffer.size} bytes exceeds ${maxBytes / 1024 / 1024}MB"
            )
        }
        val charset = b.contentType()?.charset() ?: Charsets.UTF_8
        source.buffer.readString(charset)
    }
}

/**
 * Reads this response's body into a [ByteArray], rejecting bodies over [maxBytes]. Always
 * closes the body. A null body reads as an empty array.
 *
 * The bytes are not charset-decoded: decoding a binary payload such as a JPEG or PNG photo
 * through a String replaces every invalid byte sequence with U+FFFD and corrupts the image.
 *
 * @throws ResponseTooLargeException if the body exceeds [maxBytes], checked the same two ways
 *   as [readBoundedBody].
 */
fun Response.readBoundedBytes(maxBytes: Long = MAX_HTTP_RESPONSE_SIZE_BYTES): ByteArray {
    val body = this.body ?: return ByteArray(0)
    return body.use { b ->
        val source = b.source()
        val contentLength = b.contentLength()
        // contentLength is -1 when unknown (chunked or streaming); the buffered-size check
        // below catches those.
        if (contentLength > maxBytes) {
            Log.w(TAG, "Response rejected: Content-Length $contentLength exceeds limit")
            throw ResponseTooLargeException(
                "Response too large: Content-Length $contentLength exceeds ${maxBytes / 1024 / 1024}MB"
            )
        }
        source.request(maxBytes + 1)
        if (source.buffer.size > maxBytes) {
            Log.w(TAG, "Response rejected: buffered ${source.buffer.size} bytes exceeds limit")
            throw ResponseTooLargeException(
                "Response too large: buffered ${source.buffer.size} bytes exceeds ${maxBytes / 1024 / 1024}MB"
            )
        }
        source.buffer.readByteArray()
    }
}
