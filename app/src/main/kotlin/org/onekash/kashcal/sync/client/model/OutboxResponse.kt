package org.onekash.kashcal.sync.client.model

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parsed result of a scheduling-Outbox POST (RFC 6638 §6, §10.1).
 *
 * The server answers an Outbox POST with a `CALDAV:schedule-response` document
 * containing one `CALDAV:response` per recipient — each carrying the
 * recipient's CAL-ADDRESS and a `CALDAV:request-status` outcome code
 * (RFC 6638 §10.4, e.g. `2.0;Success`, `3.7;Invalid calendar user`).
 *
 * This model holds the raw per-recipient status strings server-faithfully; the
 * leading-digit delivery classification lives in [classifyRequestStatus] so the
 * retry policy has a single home (no re-derivation at the call site).
 */
data class OutboxResponse(
    val recipients: List<RecipientStatus>
) {
    /**
     * One recipient's outcome from the schedule-response.
     *
     * @param recipient the recipient CAL-ADDRESS the server echoed (typically a
     *   `mailto:` href).
     * @param requestStatus the raw `request-status` code string, or null when
     *   the server omitted it for this recipient.
     */
    data class RecipientStatus(
        val recipient: String,
        val requestStatus: String?
    )

    companion object {
        private const val TAG = "OutboxResponse"

        private val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }

        /**
         * Parse a `schedule-response` XML body into per-recipient outcomes.
         *
         * Namespace-prefix tolerant (matches on local element names) and
         * defensive: a blank, empty-element, or malformed/non-XML body yields
         * an empty recipient list rather than throwing — an Outbox POST result
         * is best-effort and must never crash the push.
         */
        fun parse(xml: String): OutboxResponse {
            if (xml.isBlank()) return OutboxResponse(emptyList())
            return try {
                val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }
                val recipients = mutableListOf<RecipientStatus>()

                // Per-<response> accumulators.
                var inResponse = false
                var inRecipient = false
                var recipientHref: String? = null
                var requestStatus: String? = null

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "response" -> {
                                inResponse = true
                                recipientHref = null
                                requestStatus = null
                            }
                            // The recipient CAL-ADDRESS lives in a <href> nested
                            // inside <recipient>; scope to <recipient> so a
                            // sibling <calendar-data> href is never mistaken.
                            "recipient" -> inRecipient = true
                            "href" -> if (inResponse && inRecipient) {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    recipientHref = parser.text?.trim()
                                }
                            }
                            "request-status" -> if (inResponse) {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    requestStatus = parser.text?.trim()
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> when (parser.name) {
                            "recipient" -> inRecipient = false
                            "response" -> {
                                inResponse = false
                                recipientHref?.let {
                                    recipients.add(RecipientStatus(it, requestStatus))
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
                OutboxResponse(recipients)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse schedule-response: ${e.message}")
                OutboxResponse(emptyList())
            }
        }
    }
}

/**
 * The retry disposition of an Outbox per-recipient `request-status`, keyed off
 * the leading status digit (RFC 6638 §3.6 / RFC 5546 §3.6, which prescribe
 * retry behavior by status class, not by an attempt count).
 */
enum class OutboxDeliveryClass {
    /** `2.x` — the message was sent; stop, the send is done. */
    SUCCESS,

    /**
     * `5.1`, a transport/network failure, or an unparseable status — "the
     * originator can try to send the message again at a later time." The
     * idempotency marker is left unadvanced so the next push retries.
     */
    TRANSIENT,

    /**
     * `3.x` (invalid user / privileges), `5.2`, `5.3` — "the originator ought
     * not try to send the message again, at least without verifying/correcting
     * the calendar user address." The marker is advanced to stop the loop;
     * recovery rides a later SEQUENCE bump or an address correction.
     */
    PERMANENT,
}

/**
 * Classify a raw `request-status` string by its leading status code (RFC 6638
 * §10.4). A null/blank/unparseable code is treated as [TRANSIENT] so an
 * ambiguous outcome is retried rather than silently dropped.
 */
fun classifyRequestStatus(requestStatus: String?): OutboxDeliveryClass {
    val code = requestStatus?.substringBefore(';')?.trim().orEmpty()
    return when {
        code.startsWith("2.") -> OutboxDeliveryClass.SUCCESS
        code == "5.1" -> OutboxDeliveryClass.TRANSIENT
        code.startsWith("3.") || code.startsWith("5.") -> OutboxDeliveryClass.PERMANENT
        else -> OutboxDeliveryClass.TRANSIENT
    }
}
