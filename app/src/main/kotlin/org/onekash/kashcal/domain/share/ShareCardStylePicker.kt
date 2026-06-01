package org.onekash.kashcal.domain.share

import java.util.Locale

/**
 * Auto-picks a [ShareCardStyle] from an event title.
 *
 * Returns [ShareCardStyle.Celebration] when the title contains a celebration
 * emoji or a celebration keyword on a word boundary; [ShareCardStyle.Standard]
 * otherwise. Pure function, no side effects, locale-independent (uses
 * [Locale.ROOT] for case folding to avoid the Turkish-i pitfall).
 */
object ShareCardStylePicker {

    private val CELEBRATION_EMOJIS = setOf(
        "🎂",   // 🎂 birthday cake
        "🎉",   // 🎉 party popper
        "🎊",   // 🎊 confetti ball
        "🥂",   // 🥂 clinking glasses
        "🎈",   // 🎈 balloon
        "🍾",   // 🍾 bottle with popping cork
        "💍",   // 💍 ring
        "🎓",   // 🎓 graduation cap
        "👶",   // 👶 baby
    )

    private val CELEBRATION_KEYWORDS = listOf(
        "birthday",
        "party",
        "wedding",
        "anniversary",
        "baby shower",
        "graduation",
        "new year",
        "housewarming",
    )

    /** Word-boundary regex per keyword. Pre-compiled at class-load time. */
    private val KEYWORD_PATTERNS: List<Regex> = CELEBRATION_KEYWORDS.map { keyword ->
        // \b matches Unicode word boundaries via the (?U) flag — but Kotlin
        // Regex on JVM uses java.util.regex which interprets \b as a *word*
        // boundary (transition between \w and \W). For ASCII keywords this
        // is sufficient. Anchor to start-of-word; allow any trailing chars
        // (matches "partygoers" → starts with "party") but not preceding
        // word chars (rejects "antiparty", "unbirthday").
        Regex("""\b${Regex.escape(keyword)}""", RegexOption.IGNORE_CASE)
    }

    fun autoPickFor(title: String?): ShareCardStyle {
        if (title.isNullOrBlank()) return ShareCardStyle.Standard

        // Lowercase via Locale.ROOT so Turkish-i case folding doesn't break
        // English keyword matching (BIRTHDAY → birthday, not bırthday).
        val rooted = title.lowercase(Locale.ROOT)

        if (CELEBRATION_EMOJIS.any { title.contains(it) }) {
            return ShareCardStyle.Celebration
        }
        if (KEYWORD_PATTERNS.any { it.containsMatchIn(rooted) }) {
            return ShareCardStyle.Celebration
        }
        return ShareCardStyle.Standard
    }
}
