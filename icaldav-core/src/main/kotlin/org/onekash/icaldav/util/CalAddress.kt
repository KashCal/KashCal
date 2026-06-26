package org.onekash.icaldav.util

/**
 * Helpers for the iCalendar CAL-ADDRESS value type (RFC 5545 §3.3.3), used by
 * ORGANIZER and ATTENDEE.
 *
 * A CAL-ADDRESS is *any* URI, not only `mailto:`. Servers routinely emit
 * `urn:uuid:...` and principal hrefs (`/.../principal/`, `https://.../principals/...`)
 * as ORGANIZER/ATTENDEE values. The parser strips a leading `mailto:` and stores
 * the value bare, so the generator must re-prepend `mailto:` ONLY for a
 * mailbox-shaped value and pass any other URI form through verbatim — otherwise
 * a `urn:uuid:` address round-trips to the invalid `mailto:urn:uuid:...`.
 */
object CalAddress {

    /**
     * Mailbox-shape detection: strict enough to reject principal hrefs
     * (`/646691839/principal/`), HTTP/HTTPS principal URIs, `urn:uuid:` forms,
     * and pathological `@example.com` / `foo@` shapes. Shared by the parser
     * (which decides whether a primary value is a usable mailto) and the
     * generator (which decides whether to re-prepend `mailto:` on emit) so the
     * two sides cannot diverge.
     */
    val mailtoShape = Regex("""^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$""")

    /**
     * Render a stored CAL-ADDRESS value for the wire.
     *
     * - A mailbox-shaped value (`alice@example.test`) gets the `mailto:` scheme.
     * - A value that already carries any URI scheme (`mailto:`, `urn:`,
     *   `http(s):`) or is an absolute principal path (`/...`) is emitted verbatim
     *   — never double-prefixed.
     */
    fun format(value: String): String =
        if (mailtoShape.matches(value)) "mailto:$value" else value
}
