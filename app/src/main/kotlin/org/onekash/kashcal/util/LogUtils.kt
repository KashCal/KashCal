package org.onekash.kashcal.util

/**
 * Masks an email for logging, keeping the first 3 characters of the local part and the
 * domain's last label; each masked span becomes three asterisks. "john.doe@icloud.com" keeps
 * "joh" and ".com".
 *
 * - A local part of 3 characters or fewer keeps only its first character.
 * - No `@`, or an `@` first, returns only the asterisks.
 * - Subdomains are masked too: "user@mail.icloud.com" keeps "use" and ".com".
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

/**
 * Masks a device calendar event id for logging, keeping its first 4 digits: 1234567 → "1234***".
 */
fun Long.maskEventId(): String = "${toString().take(4)}***"

/**
 * Masks an iCalendar UID for logging, keeping the first and last four characters so logs from
 * different sync runs still correlate an event. UIDs of 8 characters or fewer become "<short>".
 */
fun String.maskUid(): String =
    if (length <= 8) "<short>" else "${take(4)}***${takeLast(4)}"

/**
 * Masks a server hostname for logging, keeping the first three characters and the top-level
 * label so two hosts can still be told apart in a sync log:
 * "caldav.example.com" → "cal***.com",
 * "p180-caldav.icloud.com" → "p18***.com".
 * "localhost" is kept as is (it identifies no one). An IP address keeps its first group only
 * ("192.168.1.20" → "192.***"), and a single label its first three characters
 * ("nas" → "nas***"). An empty string returns only the asterisks.
 */
fun String.maskHost(): String {
    if (isEmpty()) return "***"
    if (equals("localhost", ignoreCase = true)) return this
    val isIpv4 = all { it.isDigit() || it == '.' }
    if (isIpv4 || contains(':')) {
        val separator = if (isIpv4) '.' else ':'
        return "${substringBefore(separator)}$separator***"
    }
    val lastDot = lastIndexOf('.')
    return if (lastDot > 0) "${take(3)}***${substring(lastDot)}" else "${take(3)}***"
}
