package org.onekash.kashcal.network

import android.util.Log
import okhttp3.Response
import java.io.IOException

private const val TAG = "HttpResponseBodyReader"

/**
 * Maximum number of bytes buffered from an HTTP response body.
 *
 * Responses are read fully into a String for parsing (CalDAV multistatus XML,
 * ICS feeds), so the whole body lives on the heap. This cap is an OOM backstop
 * against a malicious or malformed server returning a pathologically large body.
 *
 * 50 MB is chosen to comfortably clear legitimate payloads — CalDAV multiget
 * batches are well under 1 MB, and ICS subscription feeds can legitimately run
 * to tens of MB (large public/shared calendars) — while still rejecting the
 * hundreds-of-MB / GB bodies that would exhaust a default Android heap.
 *
 * Note: this caps the WHOLE response body. KashCal does not currently parse or
 * store inline attachments (ATTACH); if that changes, this becomes an effective
 * attachment ceiling and the read path should stream large attachments to disk
 * rather than buffering them as a String.
 */
const val MAX_HTTP_RESPONSE_SIZE_BYTES: Long = 50L * 1024 * 1024

/**
 * Thrown by [readBoundedBody] when a response body exceeds the size limit.
 *
 * Subclasses [IOException] so existing broad `catch (IOException)` handlers
 * still treat it as a network failure, while callers that want a distinct
 * "too large" message can catch this type specifically.
 */
class ResponseTooLargeException(message: String) : IOException(message)

/**
 * Read this response's body into a String, rejecting bodies larger than
 * [maxBytes]. Always closes the body.
 *
 * The body is decoded using its declared Content-Type charset, falling back to
 * UTF-8 when none is declared. A null body decodes to the empty string.
 *
 * @throws ResponseTooLargeException if the body exceeds [maxBytes], detected
 *   either via the Content-Length header (cheap, before buffering) or, for
 *   chunked/streaming responses with no Content-Length, via the buffered size.
 */
fun Response.readBoundedBody(maxBytes: Long = MAX_HTTP_RESPONSE_SIZE_BYTES): String {
    val body = this.body ?: return ""
    return body.use { b ->
        val source = b.source()
        val contentLength = b.contentLength()
        // contentLength is -1 when unknown (chunked/streaming) — falls through
        // to the buffered-size check below.
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
 * Read this response's body into a [ByteArray], rejecting bodies larger than
 * [maxBytes]. Always closes the body. A null body decodes to an empty array.
 *
 * Unlike [readBoundedBody], the bytes are returned verbatim with NO charset
 * decode — decoding a binary payload (JPEG/PNG photo) through a String would
 * replace any byte sequence that isn't valid in the declared charset with the
 * Unicode replacement character and corrupt the image. Same two-stage size
 * guard as [readBoundedBody]: the Content-Length header first (cheap, before
 * buffering), then the buffered size for chunked/streaming responses.
 *
 * @throws ResponseTooLargeException if the body exceeds [maxBytes].
 */
fun Response.readBoundedBytes(maxBytes: Long = MAX_HTTP_RESPONSE_SIZE_BYTES): ByteArray {
    val body = this.body ?: return ByteArray(0)
    return body.use { b ->
        val source = b.source()
        val contentLength = b.contentLength()
        // contentLength is -1 when unknown (chunked/streaming) — falls through
        // to the buffered-size check below.
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
