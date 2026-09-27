package org.onekash.kashcal.sync.client.model

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Holds the per-recipient outcomes of a scheduling Outbox POST (RFC 6638 §6, §10.1).
 *
 * The server answers with a `CALDAV:schedule-response` holding one `CALDAV:response` per
 * recipient: its CAL-ADDRESS and a `CALDAV:request-status` code (RFC 6638 §10.4, e.g.
 * `2.0;Success`, `3.7;Invalid calendar user`). The status strings are kept as the server sent
 * them; [classifyRequestStatus] is the one place that turns them into a retry decision.
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
         * Parses a `schedule-response` body into per-recipient outcomes.
         *
         * Matches local element names, so any namespace prefix works. A blank, empty or
         * malformed body gives an empty recipient list and never throws: the Outbox result
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
                            // The CAL-ADDRESS is the <href> inside <recipient>; any
                            // other <href> in the response is not the recipient.
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
 * Says whether an Outbox recipient's `request-status` is worth sending again, by its status
 * class (RFC 6638 §3.6 / RFC 5546 §3.6 set retry behavior by status class, not attempt count).
 * [classifyRequestStatus] maps codes to these.
 */
enum class OutboxDeliveryClass {
    /** `2.x`: the message was sent; don't send it again. */
    SUCCESS,

    /**
     * `5.1`, any class other than 2, 3 or 5, or a missing or unparseable status: "the
     * originator can try to send the message again at a later time." The push sends it again
     * on a later run (an invite's sent marker stays unadvanced; a cancel is kept, up to its
     * attempt cap).
     */
    TRANSIENT,

    /**
     * `3.x` (invalid user or privileges), `5.2`, `5.3` and any other `5.x` but `5.1`: "the
     * originator ought not try to send the message again, at least without verifying/correcting the
     * calendar user address." The push stops sending (an invite's marker advances, a cancel is
     * dropped); an invite goes out again only after a SEQUENCE bump or an address correction.
     */
    PERMANENT,
}

/**
 * Classifies a raw `request-status` by the code before its `;` (RFC 6638 §10.4). A null,
 * blank or unrecognised code is [OutboxDeliveryClass.TRANSIENT], so an unclear outcome is
 * retried, never silently dropped.
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
