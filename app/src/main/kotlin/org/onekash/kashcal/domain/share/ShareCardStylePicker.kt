package org.onekash.kashcal.domain.share

import java.util.Locale

/**
 * Picks a [ShareCardStyle] from an event title: [ShareCardStyle.Celebration] when the title has a
 * celebration emoji or starts a word with a celebration keyword, else [ShareCardStyle.Standard].
 * Case folding uses [Locale.ROOT], so the result doesn't depend on the device locale.
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

    /** One regex per keyword, anchored at the start of a word. */
    private val KEYWORD_PATTERNS: List<Regex> = CELEBRATION_KEYWORDS.map { keyword ->
        // \b is a word boundary (between \w and \W), which is enough for these ASCII keywords.
        // Only the start is anchored: "partygoers" matches, "antiparty"
        // and "unbirthday" don't.
        Regex("""\b${Regex.escape(keyword)}""", RegexOption.IGNORE_CASE)
    }

    fun autoPickFor(title: String?): ShareCardStyle {
        if (title.isNullOrBlank()) return ShareCardStyle.Standard

        // Locale.ROOT keeps "BIRTHDAY" from folding to a dotless "bırthday" in Turkish.
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
