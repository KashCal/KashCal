package org.onekash.kashcal.util.text

import java.net.URI

/**
 * URL detection and handling utilities for event quick view.
 *
 * Features:
 * - Detect URLs in text (http/https, tel:, mailto:)
 * - Identify meeting platform links (Zoom, Teams, Google Meet, etc.)
 * - Format phone numbers for tel: URI
 * - Clean HTML entities from CalDAV descriptions
 * - Format reminder durations for display
 *
 * Note: Uses java.net.URI instead of android.net.Uri for testability
 * without Robolectric.
 *
 * @see extractUrls for URL detection in text
 * @see isMeetingUrl for meeting platform detection
 */

/**
 * Types of detected URLs.
 */
enum class UrlType {
    /** Standard web URL (http/https) */
    WEB,
    /** Meeting platform URL (Zoom, Teams, etc.) */
    MEETING,
    /** Phone number (tel: or detected pattern) */
    PHONE,
    /** Email address (mailto:) */
    EMAIL
}

/**
 * Represents a detected URL in text.
 *
 * @param url The URL string (normalized)
 * @param startIndex Start position in original text
 * @param endIndex End position in original text (exclusive)
 * @param type Type of URL for display/handling
 * @param displayText Accessibility text (e.g., "zoom.us", "Phone number")
 */
data class DetectedUrl(
    val url: String,
    val startIndex: Int,
    val endIndex: Int,
    val type: UrlType,
    val displayText: String
)

/**
 * Known meeting platform domains.
 * Add domains here to enable meeting link detection.
 */
val MEETING_DOMAINS = setOf(
    "teams.microsoft.com",
    "zoom.us",
    "meet.google.com",
    "webex.com",
    "gotomeeting.com",
    "whereby.com",
    "teams.live.com",
    "meet.jit.si"
)

// URL pattern - matches http/https URLs with case insensitivity
private val URL_PATTERN = Regex(
    """https?://[^\s<>"{}|\\^`\[\]]+""",
    RegexOption.IGNORE_CASE
)

// URL-like pattern without protocol (for domains like zoom.us/j/123)
private val URL_NO_PROTOCOL_PATTERN = Regex(
    """(?<![/@])(?:${MEETING_DOMAINS.joinToString("|") { Regex.escape(it) }})/[^\s<>"{}|\\^`\[\]]+""",
    RegexOption.IGNORE_CASE
)

// Tel URI pattern
private val TEL_URI_PATTERN = Regex(
    """tel:[+\d\-().]+""",
    RegexOption.IGNORE_CASE
)

// Mailto URI pattern
private val MAILTO_URI_PATTERN = Regex(
    """mailto:[\w._%+-]+@[\w.-]+\.[a-zA-Z]{2,}""",
    RegexOption.IGNORE_CASE
)

// US phone patterns (common formats)
// Order matters: more specific patterns (international) first to prevent shorter patterns
// from matching a substring of longer phone numbers
private val PHONE_PATTERNS = listOf(
    // +1-555-123-4567 or +1 (555) 123-4567 or +1.555.123.4567 (check first - most specific)
    Regex("""\+1[-.\s]?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]\d{4}"""),
    // (555) 123-4567 or (555) 123 4567
    Regex("""\(\d{3}\)\s*\d{3}[-.\s]\d{4}"""),
    // 555-123-4567 or 555.123.4567 or 555 123 4567 (least specific - check last)
    Regex("""\d{3}[-.\s]\d{3}[-.\s]\d{4}""")
)

// Trailing punctuation to strip from URLs
private val TRAILING_PUNCT = charArrayOf('.', ',', ')', ']', '>', ';', ':', '!', '?')

/**
 * Extract all URLs from text.
 *
 * Detects:
 * - Web URLs (http/https)
 * - Meeting URLs (recognized by domain)
 * - Tel: and mailto: URIs
 * - US phone number patterns
 *
 * @param text Text to search
 * @param limit Maximum URLs to return (default 50 for performance)
 * @return List of detected URLs with position and type info
 */
fun extractUrls(text: String, limit: Int = 50): List<DetectedUrl> {
    if (text.isBlank()) return emptyList()

    val results = mutableListOf<DetectedUrl>()

    // Find all http/https URLs
    URL_PATTERN.findAll(text).forEach { match ->
        if (results.size >= limit) return@forEach
        val normalized = normalizeUrl(match.value)
        if (isValidUrl(normalized)) {
            results.add(
                DetectedUrl(
                    url = normalized,
                    startIndex = match.range.first,
                    endIndex = match.range.last + 1,
                    type = if (isMeetingUrl(normalized)) UrlType.MEETING else UrlType.WEB,
                    displayText = getUrlDisplayText(normalized)
                )
            )
        }
    }

    // Find meeting URLs without protocol
    URL_NO_PROTOCOL_PATTERN.findAll(text).forEach { match ->
        if (results.size >= limit) return@forEach
        // Skip if this range overlaps with an already-found URL
        if (results.any { it.startIndex <= match.range.first && it.endIndex >= match.range.last + 1 }) {
            return@forEach
        }
        val normalized = "https://${match.value}"
        if (isValidUrl(normalized)) {
            results.add(
                DetectedUrl(
                    url = normalized,
                    startIndex = match.range.first,
                    endIndex = match.range.last + 1,
                    type = UrlType.MEETING,
                    displayText = getUrlDisplayText(normalized)
                )
            )
        }
    }

    // Find tel: URIs
    TEL_URI_PATTERN.findAll(text).forEach { match ->
        if (results.size >= limit) return@forEach
        results.add(
            DetectedUrl(
                url = match.value,
                startIndex = match.range.first,
                endIndex = match.range.last + 1,
                type = UrlType.PHONE,
                displayText = "Phone number"
            )
        )
    }

    // Find mailto: URIs
    MAILTO_URI_PATTERN.findAll(text).forEach { match ->
        if (results.size >= limit) return@forEach
        val email = match.value.removePrefix("mailto:")
        results.add(
            DetectedUrl(
                url = match.value,
                startIndex = match.range.first,
                endIndex = match.range.last + 1,
                type = UrlType.EMAIL,
                displayText = email
            )
        )
    }

    // Find phone numbers (not already found as tel:)
    PHONE_PATTERNS.forEach { pattern ->
        pattern.findAll(text).forEach { match ->
            if (results.size >= limit) return@forEach
            // Skip if overlaps with existing match
            if (results.any { overlaps(it.startIndex, it.endIndex, match.range.first, match.range.last + 1) }) {
                return@forEach
            }
            results.add(
                DetectedUrl(
                    url = formatPhoneUri(match.value),
                    startIndex = match.range.first,
                    endIndex = match.range.last + 1,
                    type = UrlType.PHONE,
                    displayText = "Phone number"
                )
            )
        }
    }

    // Sort by position for consistent ordering
    return results.sortedBy { it.startIndex }
}

/**
 * Check if text contains any URL.
 */
fun containsUrl(text: String): Boolean {
    if (text.isBlank()) return false
    return URL_PATTERN.containsMatchIn(text) ||
           URL_NO_PROTOCOL_PATTERN.containsMatchIn(text) ||
           TEL_URI_PATTERN.containsMatchIn(text) ||
           MAILTO_URI_PATTERN.containsMatchIn(text) ||
           PHONE_PATTERNS.any { it.containsMatchIn(text) }
}

/**
 * Check if URL is a known meeting platform.
 */
fun isMeetingUrl(url: String): Boolean {
    val host = try {
        URI(url.lowercase()).host ?: return false
    } catch (e: Exception) {
        return false
    }
    return MEETING_DOMAINS.any { host == it || host.endsWith(".$it") }
}

/**
 * Normalize a URL for consistent handling.
 *
 * - Strips trailing punctuation
 * - Adds https:// if no protocol
 * - Lowercases the scheme and host
 */
fun normalizeUrl(url: String): String {
    var result = url.trim()

    // Strip trailing punctuation (but preserve if part of URL path)
    while (result.isNotEmpty() && result.last() in TRAILING_PUNCT) {
        // Check if this punctuation is balanced (parentheses)
        if (result.last() == ')' && result.count { it == '(' } < result.count { it == ')' }) {
            result = result.dropLast(1)
        } else if (result.last() == ']' && result.count { it == '[' } < result.count { it == ']' }) {
            result = result.dropLast(1)
        } else if (result.last() !in listOf(')', ']')) {
            result = result.dropLast(1)
        } else {
            break
        }
    }

    // Add protocol if missing
    if (!result.startsWith("http://", ignoreCase = true) &&
        !result.startsWith("https://", ignoreCase = true) &&
        !result.startsWith("tel:", ignoreCase = true) &&
        !result.startsWith("mailto:", ignoreCase = true)) {
        result = "https://$result"
    }

    return result
}

/**
 * Validate URL format using java.net.URI.
 */
fun isValidUrl(url: String): Boolean {
    return try {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
        when (scheme) {
            "http", "https" -> uri.host?.isNotBlank() == true
            "tel" -> uri.schemeSpecificPart?.isNotBlank() == true
            "mailto" -> uri.schemeSpecificPart?.contains("@") == true
            else -> false
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Check if URL should be opened externally (not a deep link to own app).
 *
 * Only allows http(s), tel, and mailto schemes.
 */
fun shouldOpenExternally(url: String): Boolean {
    return try {
        val scheme = URI(url).scheme?.lowercase()
        scheme in listOf("http", "https", "tel", "mailto")
    } catch (e: Exception) {
        false
    }
}

/**
 * Format phone number as tel: URI.
 *
 * Preserves leading + for international format, strips all other non-numeric characters.
 */
fun formatPhoneUri(phone: String): String {
    val trimmed = phone.trim()
    val hasPlus = trimmed.startsWith("+")
    val digits = trimmed.filter { it.isDigit() }
    return if (hasPlus) "tel:+$digits" else "tel:$digits"
}

/**
 * Get display text for URL (domain or platform name).
 */
private fun getUrlDisplayText(url: String): String {
    return try {
        val host = URI(url).host?.lowercase() ?: return url
        when {
            "zoom.us" in host -> "Zoom"
            "teams.microsoft.com" in host || "teams.live.com" in host -> "Microsoft Teams"
            "meet.google.com" in host -> "Google Meet"
            "webex.com" in host -> "Webex"
            "gotomeeting.com" in host -> "GoToMeeting"
            "whereby.com" in host -> "Whereby"
            "meet.jit.si" in host -> "Jitsi Meet"
            else -> host.removePrefix("www.")
        }
    } catch (e: Exception) {
        url
    }
}

/**
 * Check if two ranges overlap.
 */
private fun overlaps(start1: Int, end1: Int, start2: Int, end2: Int): Boolean {
    return start1 < end2 && start2 < end1
}

// ========== HTML Entity Handling ==========

private val HTML_ENTITIES = mapOf(
    "&amp;" to "&",
    "&lt;" to "<",
    "&gt;" to ">",
    "&nbsp;" to " ",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&#x27;" to "'",
    "&apos;" to "'",
    "&#34;" to "\"",
    "&#x22;" to "\""
)

/**
 * Clean common HTML entities from text.
 *
 * CalDAV descriptions may contain HTML entities from web clients.
 * This cleans them for display without modifying the stored data.
 */
fun cleanHtmlEntities(text: String): String {
    var result = text
    HTML_ENTITIES.forEach { (entity, replacement) ->
        result = result.replace(entity, replacement, ignoreCase = true)
    }
    // Handle numeric entities
    result = result.replace(Regex("&#(\\d+);")) { match ->
        val code = match.groupValues[1].toIntOrNull()
        if (code != null && code in 0..0x10FFFF) {
            code.toChar().toString()
        } else {
            match.value
        }
    }
    return result
}

// ========== Reminder Duration Formatting ==========

/**
 * Format reminder list for display.
 *
 * @param reminders List of ISO duration strings (e.g., ["-PT15M", "-P1D"])
 * @return Formatted string (e.g., "15 min before, 1 day before") or null if empty
 */
fun formatRemindersForDisplay(reminders: List<String>?): String? {
    if (reminders.isNullOrEmpty()) return null
    return reminders.mapNotNull { formatDuration(it) }.joinToString(", ")
}

/**
 * Format ISO 8601 duration to human-readable string.
 *
 * Handles negative durations (before event start).
 *
 * @param isoDuration ISO duration string (e.g., "-PT15M", "-P1D", "PT30M")
 * @return Human-readable format (e.g., "15 min before", "1 day before", "30 min after")
 */
fun formatDuration(isoDuration: String): String? {
    if (isoDuration.isBlank()) return null

    val negative = isoDuration.startsWith("-")
    val duration = isoDuration.removePrefix("-").removePrefix("+")

    // Parse ISO 8601 duration: P[n]D[T[n]H[n]M[n]S]
    val match = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")
        .matchEntire(duration) ?: return null

    val days = match.groupValues[1].toIntOrNull() ?: 0
    val hours = match.groupValues[2].toIntOrNull() ?: 0
    val minutes = match.groupValues[3].toIntOrNull() ?: 0
    val seconds = match.groupValues[4].toIntOrNull() ?: 0

    // Build human-readable parts
    val parts = mutableListOf<String>()
    if (days > 0) {
        parts.add(if (days == 1) "1 day" else "$days days")
    }
    if (hours > 0) {
        parts.add(if (hours == 1) "1 hour" else "$hours hours")
    }
    if (minutes > 0) {
        parts.add(if (minutes == 1) "1 min" else "$minutes min")
    }
    if (seconds > 0 && parts.isEmpty()) {
        parts.add(if (seconds == 1) "1 sec" else "$seconds sec")
    }

    if (parts.isEmpty()) {
        return "At event time"
    }

    val timeString = parts.joinToString(" ")
    return if (negative) "$timeString before" else "$timeString after"
}
