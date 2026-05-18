package org.onekash.kashcal.util

/**
 * Mask email for safe logging. Shows first 3 chars + domain TLD only.
 * Example: "john.doe@icloud.com" → "joh***@***.com"
 *
 * Edge cases:
 * - Short local parts (≤3 chars): Shows first char + "***"
 * - No @ symbol: Returns "***"
 * - Subdomains: Intentionally masked (more secure) - "user@mail.icloud.com" → "use***@***.com"
 */
fun String.maskEmail(): String {
    val atIndex = indexOf('@')
    if (atIndex <= 0) return "***"
    val maskedLocal = if (atIndex <= 3) "${first()}***" else "${take(3)}***"
    val domainPart = substring(atIndex + 1)
    val domainDot = domainPart.lastIndexOf('.')
    val maskedDomain = if (domainDot > 0) "***${domainPart.substring(domainDot)}" else "***"
    return "$maskedLocal@$maskedDomain"
}

/** Mask a device calendar event id for logging. Example: 1234567 → "1234***". */
fun Long.maskEventId(): String = "${toString().take(4)}***"

/**
 * Mask an iCalendar UID for logging. Keeps first/last four chars so log
 * grep'ing across sync sessions still correlates events; everything else
 * is replaced with "***". Short UIDs (≤8) collapse to "<short>".
 */
fun String.maskUid(): String =
    if (length <= 8) "<short>" else "${take(4)}***${takeLast(4)}"
