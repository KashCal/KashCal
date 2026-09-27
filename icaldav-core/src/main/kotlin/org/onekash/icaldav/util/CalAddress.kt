package org.onekash.icaldav.util

/**
 * Helpers for the iCalendar CAL-ADDRESS value type (RFC 5545 §3.3.3), used by ORGANIZER and
 * ATTENDEE.
 *
 * A CAL-ADDRESS is any URI, not only `mailto:`. Servers emit `urn:uuid:...` and principal hrefs
 * (`/.../principal/`, `https://.../principals/...`) as ORGANIZER and ATTENDEE values. The parser
 * strips a leading `mailto:` and stores the value bare, so the generator must re-prepend
 * `mailto:` only for a mailbox-shaped value and pass any other form through verbatim; otherwise
 * a `urn:uuid:` address round-trips to the invalid `mailto:urn:uuid:...`.
 */
object CalAddress {

    /**
     * Matches a bare mailbox (`local@domain.tld`), rejecting principal hrefs
     * (`/646691839/principal/`), HTTP and HTTPS principal URIs, `urn:uuid:` forms and shapes
     * like `@example.com` or `foo@`.
     *
     * One pattern serves the parser's `EMAIL=` fallback (is the primary value a usable mailto),
     * [format] (re-prepend `mailto:` on emit) and the app's address normalizer, so they can't
     * diverge.
     */
    val mailtoShape = Regex("""^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$""")

    /**
     * Renders a stored CAL-ADDRESS value for the wire.
     *
     * A value matching [mailtoShape] (`alice@example.test`) gets the `mailto:` scheme. Anything
     * else is emitted verbatim, so a value that already carries a scheme (`mailto:`, `urn:`,
     * `http(s):`) or is a principal path (`/...`) is never double-prefixed.
     */
    fun format(value: String): String =
        if (mailtoShape.matches(value)) "mailto:$value" else value
}
