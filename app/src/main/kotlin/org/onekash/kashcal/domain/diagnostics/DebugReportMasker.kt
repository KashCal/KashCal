package org.onekash.kashcal.domain.diagnostics

import org.onekash.kashcal.util.maskEmail
import org.onekash.kashcal.util.maskHost
import org.onekash.kashcal.util.maskUid

/**
 * Masks machine-shaped personal values in one line of a debug report.
 *
 * Masked: the rest of the line after a [CREDENTIAL_KEYS] key followed by `:` or `=`; URLs
 * (scheme kept); email addresses, also with `@` written as `%40`; UUIDs; IP addresses; the host
 * after "resolve host", "connect to", "Hostname" or "CN="; other host names that end in a
 * two-letter label or a [GENERIC_TLDS] label; segments of six or more digits in a path; and
 * runs of 24 or more token characters that hold both a letter and a digit, unless the run is a
 * CamelCase class name ([camelCaseIdentifier]) right after `.` or `$`, such as a Kotlin
 * synthetic class.
 *
 * Not masked: event titles, calendar and contact names, and bare numbers, which the report needs
 * for timestamps and ids. The report sheet tells the user so.
 *
 * Repeated groups are bounded or possessive, so a line costs time linear in its length and no
 * pattern recurses per character (`DebugReportMaskerTest` compares 256 KB and 1.25 MB lines and
 * masks 600 KB credential and identifier runs).
 */
object DebugReportMasker {

    private const val MASK = "***"
    private const val IP_MASK = "<ip>"

    private val CREDENTIAL_KEYS = listOf("authorization", "password", "passwd", "token")

    private val GENERIC_TLDS = listOf(
        "com", "net", "org", "info", "biz", "app", "dev", "cloud", "email", "lan", "local",
        "internal", "arpa", "home", "xyz", "box", "ovh", "tech", "online", "site", "family",
        "space", "host", "xn--[a-z0-9-]{1,59}",
    )

    // Two-letter labels that end source file names in stack frames, not hosts.
    private val FILE_EXTENSIONS = listOf("kt", "js", "py", "db", "so", "gz", "sh", "md", "ts")

    private val HOST_CONTEXT_WORDS = listOf("host", "connect to", "CN=")

    private const val CLASS_NAME_SEPARATORS = ".$"

    private val credential = Regex(
        """(${CREDENTIAL_KEYS.joinToString("|")})("?\s*[:=]\s*).+""",
        RegexOption.IGNORE_CASE
    )

    private val url = Regex("""\b(https?|webcal|wss?)://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    private val email = Regex(
        """(?<![A-Za-z0-9._+-])[A-Za-z0-9._+-]{1,64}+(?:@|%40)[A-Za-z0-9-]{1,63}+(?:\.[A-Za-z0-9-]{1,63}+){1,10}"""
    )

    private val uuid = Regex(
        """(?<![0-9A-Fa-f-])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![0-9A-Fa-f-])"""
    )

    // Connection errors name the host outside any URL. The host needs a letter, so an IP
    // address is left to the IP rules.
    private val contextHost = Regex(
        """(resolve host "|connect to |hostname |CN=)((?=[A-Za-z0-9.-]{0,253}[A-Za-z])[A-Za-z0-9-]{1,63}+(?:\.[A-Za-z0-9-]{1,63}+){1,10})""",
        RegexOption.IGNORE_CASE
    )

    private val ipv4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")

    // Candidates only; isIpv6 drops clock times such as 13:58:09.
    private val ipv6Candidate = Regex(
        """(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Fa-f:])"""
    )

    // The last label must end there. A following `.` and a letter means a Java package, and `(`,
    // `$`, `:` plus a non-digit or " cannot" mean a class name, so stack frames stay readable;
    // `:` plus a digit is a port and still masks.
    private val bareHost = Regex(
        """(?<![A-Za-z0-9.-])(?:[A-Za-z0-9-]{1,63}+\.){1,10}""" +
            """(?!(?:${FILE_EXTENSIONS.joinToString("|")})(?![A-Za-z0-9-]))""" +
            """(?:[a-z]{2}|${GENERIC_TLDS.joinToString("|")})""" +
            """(?![A-Za-z0-9-])(?!\.[A-Za-z])(?![($])(?!:\D)(?! cannot)""",
        RegexOption.IGNORE_CASE
    )

    private val pathDigits = Regex("""(?<=/)\d{6,}+(?![0-9])""")

    // Words that each start with a capital, then optional digits: `ExternalSyntheticLambda2`.
    private val camelCaseIdentifier = Regex("""(?:[A-Z][a-z]++)++[0-9]*+""")

    private val token = Regex("""(?<![A-Za-z0-9_+/=-])[A-Za-z0-9_+/=-]{24,}+""")

    /** Returns [line] with the values listed on [DebugReportMasker] masked. */
    fun mask(line: String): String {
        var out = line
        // Each pattern but the token one needs its trigger text, so most log lines skip most
        // patterns.
        if (CREDENTIAL_KEYS.any { out.contains(it, ignoreCase = true) }) {
            out = credential.replace(out) { it.groupValues[1] + it.groupValues[2] + MASK }
        }
        if (out.contains("://")) out = url.replace(out) { it.groupValues[1] + "://" + MASK }
        if (out.contains('@') || out.contains("%40")) {
            out = email.replace(out) { it.value.replace("%40", "@").maskEmail() }
        }
        if (out.contains('-')) out = uuid.replace(out) { it.value.maskUid() }
        if (HOST_CONTEXT_WORDS.any { out.contains(it, ignoreCase = true) }) {
            out = contextHost.replace(out) { it.groupValues[1] + it.groupValues[2].maskHost() }
        }
        if (out.contains('.')) out = ipv4.replace(out, IP_MASK)
        if (out.contains(':')) out = ipv6Candidate.replace(out) { if (isIpv6(it.value)) IP_MASK else it.value }
        if (out.contains('.')) out = bareHost.replace(out) { it.value.maskHost() }
        if (out.contains('/')) out = pathDigits.replace(out, MASK)
        val beforeTokens = out
        out = token.replace(beforeTokens) { match ->
            val start = match.range.first
            val inClassName = start > 0 && beforeTokens[start - 1] in CLASS_NAME_SEPARATORS &&
                camelCaseIdentifier.matches(match.value)
            if (isTokenShaped(match.value) && !inClassName) MASK else match.value
        }
        return out
    }

    private fun isIpv6(value: String): Boolean =
        value.contains("::") || value.any { it in 'a'..'f' || it in 'A'..'F' } || value.count { it == ':' } >= 5

    private fun isTokenShaped(value: String): Boolean =
        value.any { it.isLetter() } && value.any { it.isDigit() }
}
