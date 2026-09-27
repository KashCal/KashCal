package org.onekash.kashcal.util.text

import java.net.URI

/**
 * Link detection for event text in the quick view sheets: web, meeting, tel:, mailto: and US
 * phone number links ([extractUrls]), HTML detection ([looksLikeHtml]) and entity decoding
 * ([cleanHtmlEntities]).
 *
 * Uses java.net.URI, not android.net.Uri, so the functions run in plain JVM tests without
 * Robolectric.
 */

/** Kinds of link [extractUrls] detects. */
enum class UrlType {
    /** An http or https URL that isn't a meeting link. */
    WEB,
    /** A URL on one of the [MEETING_DOMAINS] or a subdomain of one. */
    MEETING,
    /** A tel: URI or a detected phone number. */
    PHONE,
    /** A mailto: URI. */
    EMAIL
}

/**
 * A link found in text.
 *
 * @param url the link to open: an http or https URL after [normalizeUrl], a schemeless meeting link
 *   with https:// prefixed, a tel: URI, or the mailto: URI as found
 * @param endIndex exclusive
 * @param displayText the name the accessibility label uses: the platform name ("Zoom"), the
 *   host, or the email address. The label for [UrlType.PHONE] ignores it.
 */
data class DetectedUrl(
    val url: String,
    val startIndex: Int,
    val endIndex: Int,
    val type: UrlType,
    val displayText: String
)

/**
 * Meeting platform domains; a URL on one of them or a subdomain is a meeting link.
 *
 * A domain added here is also matched without a scheme. [getUrlDisplayText] labels it by its host
 * unless it has its own platform name there.
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

// http and https URLs, any case.
private val URL_PATTERN = Regex(
    """https?://[^\s<>"{}|\\^`\[\]]+""",
    RegexOption.IGNORE_CASE
)

// A meeting domain with a path but no scheme, such as zoom.us/j/123, not preceded by / or @.
private val URL_NO_PROTOCOL_PATTERN = Regex(
    """(?<![/@])(?:${MEETING_DOMAINS.joinToString("|") { Regex.escape(it) }})/[^\s<>"{}|\\^`\[\]]+""",
    RegexOption.IGNORE_CASE
)

private val TEL_URI_PATTERN = Regex(
    """tel:[+\d\-().]+""",
    RegexOption.IGNORE_CASE
)

private val MAILTO_URI_PATTERN = Regex(
    """mailto:[\w._%+-]+@[\w.-]+\.[a-zA-Z]{2,}""",
    RegexOption.IGNORE_CASE
)

// US phone formats, most specific first: a later pattern skips text an earlier one matched,
// so the +1 form must run before the shorter forms can match its last ten digits.
private val PHONE_PATTERNS = listOf(
    // +1-555-123-4567, +1 (555) 123-4567 or +1.555.123.4567
    Regex("""\+1[-.\s]?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]\d{4}"""),
    // (555) 123-4567 or (555) 123 4567
    Regex("""\(\d{3}\)\s*\d{3}[-.\s]\d{4}"""),
    // 555-123-4567, 555.123.4567 or 555 123 4567
    Regex("""\d{3}[-.\s]\d{3}[-.\s]\d{4}""")
)

// Trailing punctuation [normalizeUrl] strips from a URL.
private val TRAILING_PUNCT = charArrayOf('.', ',', ')', ']', '>', ';', ':', '!', '?')

/**
 * Returns up to [limit] links in [text], sorted by position; the default of 50 bounds the work.
 *
 * Finds http and https URLs (a meeting link when [isMeetingUrl]), meeting domains without a
 * scheme, tel: and mailto: URIs, and US phone numbers. Web URLs failing [isValidUrl] are
 * dropped. A schemeless meeting link inside an earlier URL and a phone number overlapping any
 * earlier link are skipped; tel: and mailto: matches are not checked for overlap.
 */
fun extractUrls(text: String, limit: Int = 50): List<DetectedUrl> {
    if (text.isBlank()) return emptyList()

    val results = mutableListOf<DetectedUrl>()

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

    URL_NO_PROTOCOL_PATTERN.findAll(text).forEach { match ->
        if (results.size >= limit) return@forEach
        // Skip a match inside an already-found URL.
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

    PHONE_PATTERNS.forEach { pattern ->
        pattern.findAll(text).forEach { match ->
            if (results.size >= limit) return@forEach
            // Skip a number overlapping any link found so far, a tel: URI included.
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

    return results.sortedBy { it.startIndex }
}

/**
 * Returns whether [text] matches any pattern [extractUrls] uses, without its validity and overlap
 * checks, so it can be true when [extractUrls] finds nothing.
 */
fun containsUrl(text: String): Boolean {
    if (text.isBlank()) return false
    return URL_PATTERN.containsMatchIn(text) ||
           URL_NO_PROTOCOL_PATTERN.containsMatchIn(text) ||
           TEL_URI_PATTERN.containsMatchIn(text) ||
           MAILTO_URI_PATTERN.containsMatchIn(text) ||
           PHONE_PATTERNS.any { it.containsMatchIn(text) }
}

/** Returns whether [url]'s host is one of the [MEETING_DOMAINS] or a subdomain of one. */
fun isMeetingUrl(url: String): Boolean {
    val host = try {
        URI(url.lowercase()).host ?: return false
    } catch (_: Exception) {
        return false
    }
    return MEETING_DOMAINS.any { host == it || host.endsWith(".$it") }
}

/**
 * Strips trailing punctuation from [url] and prefixes https:// unless it starts with http://,
 * https://, tel: or mailto:.
 *
 * A trailing `)` is stripped only while the URL has more `)` than `(`, and `]` likewise, so a
 * path like `wiki/Foo_(bar)` keeps its own. Case is left as is.
 */
fun normalizeUrl(url: String): String {
    var result = url.trim()

    while (result.isNotEmpty() && result.last() in TRAILING_PUNCT) {
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

    if (!result.startsWith("http://", ignoreCase = true) &&
        !result.startsWith("https://", ignoreCase = true) &&
        !result.startsWith("tel:", ignoreCase = true) &&
        !result.startsWith("mailto:", ignoreCase = true)) {
        result = "https://$result"
    }

    return result
}

/**
 * Returns whether [url] parses as an http or https URL with a host, a non-empty tel: URI, or a
 * mailto: URI containing @.
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
 * Returns whether [url] is safe to hand to another app: only http, https, tel and mailto pass,
 * so a link can't deep-link into this app or open another scheme.
 */
fun shouldOpenExternally(url: String): Boolean {
    return try {
        val scheme = URI(url).scheme?.lowercase()
        scheme in SAFE_OPEN_SCHEMES
    } catch (_: Exception) {
        false
    }
}

/** Returns [phone] as a tel: URI of its digits, keeping a leading +. */
fun formatPhoneUri(phone: String): String {
    val trimmed = phone.trim()
    val hasPlus = trimmed.startsWith("+")
    val digits = trimmed.filter { it.isDigit() }
    return if (hasPlus) "tel:+$digits" else "tel:$digits"
}

/**
 * Returns a platform name when [url]'s host contains a known meeting domain, else the host
 * without www., or [url] itself when it has no host or doesn't parse.
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

/** Returns whether the half-open ranges overlap. */
private fun overlaps(start1: Int, end1: Int, start2: Int, end2: Int): Boolean {
    return start1 < end2 && start2 < end1
}

// ========== HTML Detection ==========

// Tag names that mark a description as HTML. Keep the list narrow: plain text like
// "see you <3" or "a < b" must not match.
private const val HTML_TAG_NAMES =
    "a|br|p|div|span|b|strong|i|em|u|s|strike|del|" +
        "ul|ol|li|h[1-6]|html|html-blob|head|body|meta|font|img|" +
        "table|tr|td|th|thead|tbody|blockquote|pre|code|hr"

// Matches a tag closed by `>`: `<name>`, `<name/>`, `<name attr=…>`, `<name attr/>` or
// `</name>`, plus `<!--` for comments.
//
// The `>` must be in the same tag so stray `<a lot of options` or `<i am busy` (a one-letter
// tag name, a word and no `>`) aren't taken as HTML: the HTML renderer would silently drop the
// text after such a `<`.
private val HTML_TAG_REGEX = Regex(
    "<(?:/?(?:$HTML_TAG_NAMES)(?:\\s+[^<>]*)?/?>|!--)",
    RegexOption.IGNORE_CASE
)

/**
 * Returns whether [text] contains an allow-listed HTML tag or an HTML comment, so it renders
 * through `AnnotatedString.fromHtml`.
 *
 * Plain text that only contains `<` ("see you <3", "a < b") returns false, because an HTML
 * parser would silently drop those characters.
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
 * Decodes common named HTML entities and numeric entities in [text], for display only; the
 * stored description is unchanged.
 *
 * CalDAV descriptions may carry entities from web clients. A numeric entity that isn't a
 * Unicode scalar value stays literal.
 */
fun cleanHtmlEntities(text: String): String {
    var result = text
    HTML_ENTITIES.forEach { (entity, replacement) ->
        result = result.replace(entity, replacement, ignoreCase = true)
    }
    // Decimal (&#NNN;) and hex (&#xHH;) forms; hex is at least as common as decimal for emoji
    // in real HTML.
    result = result.replace(NUMERIC_ENTITY) { match ->
        val hex = match.groupValues[1].isNotEmpty()
        val digits = match.groupValues[2]
        decodeCodePoint(digits, radix = if (hex) 16 else 10) ?: match.value
    }
    return result
}

/** Matches a decimal or hex numeric HTML entity, capturing the x marker and the digits. */
private val NUMERIC_ENTITY = Regex("&#([xX]?)([0-9a-fA-F]+);")

/**
 * Decodes a numeric entity's digits to its character, or null when the value isn't a Unicode
 * scalar value (out of range or a lone surrogate) so the caller leaves the entity literal.
 *
 * `Character.toChars` emits a code point above U+FFFF as a surrogate pair instead of
 * truncating it to its low 16 bits.
 */
private fun decodeCodePoint(digits: String, radix: Int): String? {
    val code = digits.toIntOrNull(radix) ?: return null
    if (!Character.isValidCodePoint(code) || code in 0xD800..0xDFFF) return null
    return String(Character.toChars(code))
}

