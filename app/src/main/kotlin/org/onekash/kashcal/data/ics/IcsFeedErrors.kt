package org.onekash.kashcal.data.ics

import org.onekash.kashcal.data.db.entity.IcsSubscription

/**
 * Holds the error texts a feed refresh stores in [IcsSubscription.lastError].
 *
 * The texts stay English: they are the diagnostic, and rows written by older versions hold the
 * same texts, so changing one stops those rows matching. The feed row shows them through
 * [org.onekash.kashcal.ui.screens.settings.icsFeedErrorMessage].
 */
object IcsFeedErrors {
    const val INVALID_FORMAT = "Invalid ICS format"
    const val EMPTY_RESPONSE = "Empty response from server"
    const val ACCESS_DENIED = "Authentication required"
    const val NOT_FOUND = "Calendar not found (404)"
    const val TOO_LARGE = "Calendar too large"
    const val TOO_LARGE_413 = "Calendar too large (413)"
    const val NO_EVENTS_PARSED = "Parsed 0 events from non-empty feed"
    const val UNKNOWN = "Unknown sync error"

    private const val NETWORK_PREFIX = "Network error: "
    private const val SSL_PREFIX = "SSL error: "
    private val httpStatus = Regex("""^HTTP (\d{3}): """)

    /** Returns the text for an HTTP error; [reason] is empty over HTTP/2. */
    fun http(code: Int, reason: String) = "HTTP $code: $reason"

    fun network(detail: String?) = "$NETWORK_PREFIX$detail"

    fun ssl(detail: String?) = "$SSL_PREFIX$detail"

    fun retriesExhausted(attempts: Int) = "Failed after $attempts retries"

    /** Returns the status code of a text made by [http], or null for any other text. */
    fun httpStatusOf(stored: String): Int? = httpStatus.find(stored)?.groupValues?.get(1)?.toInt()

    fun isNetwork(stored: String) = stored.startsWith(NETWORK_PREFIX)

    fun isSsl(stored: String) = stored.startsWith(SSL_PREFIX)

    private val notSavedText = Regex("""^Events not saved \((\d+)\)(?:: (.*))?$""", RegexOption.DOT_MATCHES_ALL)
    private const val NOT_SAVED_TITLE_LENGTH = 60

    /** Whitespace runs, including the line and paragraph separators `\s` leaves out. */
    private val titleSpace = Regex("""[\s\u0085\u2028\u2029]+""")

    /**
     * Returns the text for a refresh that stored the feed except [count] events, naming the first
     * of [titles] that isn't blank, on one line and cut to [NOT_SAVED_TITLE_LENGTH] code points.
     */
    fun notSaved(count: Int, titles: List<String>): String {
        val title = titles.asSequence()
            .map { it.replace(titleSpace, " ").trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { it.substring(0, it.offsetByCodePoints(0, minOf(NOT_SAVED_TITLE_LENGTH, it.codePointCount(0, it.length)))) }
            ?.trimEnd()
        return "Events not saved ($count)" + (title?.let { ": $it" } ?: "")
    }

    /** Returns the count and title of a text made by [notSaved], or null for any other text. */
    fun notSavedOf(stored: String): Pair<Int, String?>? =
        notSavedText.find(stored)?.let { it.groupValues[1].toInt() to it.groupValues[2].ifEmpty { null } }
}
