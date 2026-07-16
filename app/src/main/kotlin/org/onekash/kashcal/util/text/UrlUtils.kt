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
    } catch (_: Exception) {
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
    } catch (_: Exception) {
        false
    }
}

private val SAFE_OPEN_SCHEMES = setOf("http", "https", "tel", "mailto")

/**
 * Check if URL should be opened externally (not a deep link to own app).
 *
 * Only allows http(s), tel, and mailto schemes.
 */
fun shouldOpenExternally(url: String): Boolean {
    return try {
        val scheme = URI(url).scheme?.lowercase()
        scheme in SAFE_OPEN_SCHEMES
    } catch (_: Exception) {
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
internal fun getUrlDisplayText(url: String): String {
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
    } catch (_: Exception) {
        url
    }
}

/**
 * Check if two ranges overlap.
 */
private fun overlaps(start1: Int, end1: Int, start2: Int, end2: Int): Boolean {
    return start1 < end2 && start2 < end1
}

// ========== HTML Detection ==========

// Allow-list of tag names that mark a description as HTML.
// Must be narrow: plain text like "see you <3" or "a < b" must NOT match.
private const val HTML_TAG_NAMES =
    "a|br|p|div|span|b|strong|i|em|u|s|strike|del|" +
        "ul|ol|li|h[1-6]|html|html-blob|head|body|meta|font|img|" +
        "table|tr|td|th|thead|tbody|blockquote|pre|code|hr"

// Matches a well-formed-looking tag: `<name>`, `<name/>`, `<name attr=…>`,
// `<name attr/>`, or `</name>` — always terminated by `>`. Also matches
// `<!--` for comments.
//
// Requires `>` in the same tag so that stray `<a lot of options` or
// `<i am busy` (single-letter tag name followed by a word but no closing
// `>`) are NOT treated as HTML. This prevents HtmlCompat.fromHtml from
// silently dropping text after an innocent `<`.
private val HTML_TAG_REGEX = Regex(
    "<(?:/?(?:$HTML_TAG_NAMES)(?:\\s+[^<>]*)?/?>|!--)",
    RegexOption.IGNORE_CASE
)

/**
 * Heuristic check: does this text contain structural HTML that should be
 * rendered via `AnnotatedString.fromHtml`?
 *
 * Returns `false` for plain text that merely contains `<` (e.g. "see you <3",
 * "a < b"), because passing such text through an HTML parser would silently
 * drop those characters.
 *
 * Returns `true` when a recognizable tag name (anchor, paragraph, list item,
 * bold, italic, etc.) or an HTML comment is present.
 */
fun looksLikeHtml(text: String): Boolean {
    if (text.isEmpty()) return false
    return HTML_TAG_REGEX.containsMatchIn(text)
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
    // Handle numeric entities in both decimal (&#NNN;) and hex (&#xHH;) forms —
    // the hex form is at least as common as decimal for emoji in real HTML.
    result = result.replace(NUMERIC_ENTITY) { match ->
        val hex = match.groupValues[1].isNotEmpty() // the 'x'/'X' marker matched
        val digits = match.groupValues[2]
        decodeCodePoint(digits, radix = if (hex) 16 else 10) ?: match.value
    }
    return result
}

/** Matches a decimal or hex numeric HTML entity, capturing the 'x' marker and the digits. */
private val NUMERIC_ENTITY = Regex("&#([xX]?)([0-9a-fA-F]+);")

/**
 * Decode a numeric HTML entity's digits to its character(s), or null if the value
 * is not a Unicode scalar value (out of range, or a bare surrogate half) so the
 * caller can leave the entity literal. Builds via [Character.toChars] so code
 * points above U+FFFF are emitted as a surrogate pair rather than truncated to
 * their low 16 bits.
 */
private fun decodeCodePoint(digits: String, radix: Int): String? {
    val code = digits.toIntOrNull(radix) ?: return null
    if (!Character.isValidCodePoint(code) || code in 0xD800..0xDFFF) return null
    return String(Character.toChars(code))
}

