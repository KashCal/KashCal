package org.onekash.kashcal.domain

import java.util.Locale

/**
 * Matches event titles to emojis based on keywords.
 *
 * Used for display-only decoration of event titles across all calendar types
 * (iCloud, ICS subscriptions, Contact Birthdays, Local).
 *
 * Matching rules:
 * - Case-insensitive, folded with [Locale.ROOT] so the result never depends on
 *   the device locale (a locale that folds letters differently, e.g. Turkish
 *   mapping 'I' to a dotless 'ı', must not change how the ASCII keyword table
 *   matches).
 * - Whole-word matching (prevents partial matches like "scoffee" → coffee).
 * - When two keywords match overlapping spans of the title, the longer match
 *   wins (so a specific keyword like "eye doctor" beats the generic "doctor").
 * - Otherwise the higher-priority rule wins; equal priorities break by the
 *   order rules are declared.
 *
 * Matching is precomputed at class load: single-word keywords resolve through a
 * hash map keyed on the title's tokens, and only multi-token keywords need a
 * precompiled word-boundary regex — so the common no-match title costs a
 * tokenize pass, not a regex compile per keyword.
 */
object EmojiMatcher {

    private data class EmojiRule(
        val emoji: String,
        val keywords: List<String>,
        // Decides only *disjoint* matches in one title (e.g. christmas vs dinner).
        // Overlapping matches are resolved by specificity (longer wins), not by this.
        val priority: Int = 0
    )

    private val rules = listOf(
        // ===== CELEBRATIONS (Priority 10) =====
        EmojiRule("🎂", listOf("birthday", "bday", "b-day"), 10),
        EmojiRule("🎉", listOf("party", "celebration", "celebrate"), 10),
        EmojiRule("💑", listOf("anniversary"), 10),
        EmojiRule("💒", listOf("wedding"), 10),
        EmojiRule("🎓", listOf("graduation", "commencement"), 10),

        // ===== HOLIDAYS (Priority 10) =====
        EmojiRule("🎄", listOf("christmas", "xmas"), 10),
        EmojiRule("🎃", listOf("halloween"), 10),
        EmojiRule("🦃", listOf("thanksgiving"), 10),
        EmojiRule("🐰", listOf("easter"), 10),
        EmojiRule("💝", listOf("valentine", "valentines"), 10),
        EmojiRule("🎆", listOf("new year", "new years", "nye"), 10),
        EmojiRule("🕎", listOf("hanukkah", "chanukah"), 10),
        EmojiRule("🪔", listOf("diwali"), 10),

        // ===== FOOD & DRINK (Priority 5) =====
        EmojiRule("☕", listOf("coffee", "cafe", "starbucks"), 5),
        EmojiRule("🍵", listOf("tea time"), 5),
        EmojiRule("🍳", listOf("breakfast", "brunch"), 5),
        EmojiRule("🍽️", listOf("lunch", "dinner", "restaurant", "reservation"), 5),
        EmojiRule("🍺", listOf("drinks", "happy hour", "sports bar", "wine bar", "pub crawl", "brewery"), 5),
        EmojiRule("🍷", listOf("wine", "winery", "wine tasting"), 5),
        EmojiRule("🍕", listOf("pizza"), 5),
        EmojiRule("🍖", listOf("bbq", "barbecue", "cookout"), 5),

        // ===== TRAVEL (Priority 5) =====
        EmojiRule("✈️", listOf("flight", "airport", "flying"), 5),
        EmojiRule("🚂", listOf("train", "amtrak"), 5),
        EmojiRule("🚗", listOf("road trip"), 3),
        EmojiRule("🏨", listOf("hotel", "check-in", "checkout", "airbnb"), 5),
        EmojiRule("🏖️", listOf("beach", "vacation"), 5),
        EmojiRule("🚢", listOf("cruise", "ferry"), 5),
        EmojiRule("⛺", listOf("camping", "campsite"), 5),

        // ===== SPORTS & FITNESS (Priority 5) =====
        EmojiRule("🏋️", listOf("gym", "workout", "exercise", "crossfit"), 5),
        EmojiRule("🏃", listOf("morning run", "running", "jog", "marathon", "5k", "10k"), 5),
        EmojiRule("🧘", listOf("yoga", "meditation", "pilates"), 5),
        EmojiRule("🏊", listOf("swim", "swimming"), 5),
        EmojiRule("🎾", listOf("tennis"), 5),
        EmojiRule("⛳", listOf("golf", "tee time"), 5),
        EmojiRule("⚽", listOf("soccer"), 5),
        EmojiRule("🏀", listOf("basketball"), 5),
        EmojiRule("🥾", listOf("hike", "hiking", "trail"), 5),
        EmojiRule("⛷️", listOf("ski", "skiing", "snowboard"), 5),
        EmojiRule("🚴", listOf("cycling", "bike ride"), 5),
        EmojiRule("🎳", listOf("bowling"), 5),

        // ===== HEALTH & MEDICAL (Priority 5) =====
        EmojiRule("👨‍⚕️", listOf("doctor", "dentist", "checkup", "annual physical"), 5),
        EmojiRule("👁️", listOf("eye doctor", "optometrist", "eye exam"), 5),
        EmojiRule("💆", listOf("spa", "massage"), 5),
        EmojiRule("🧠", listOf("therapy", "therapist", "counseling"), 5),
        EmojiRule("💊", listOf("pharmacy", "prescription"), 5),
        EmojiRule("🐕", listOf("vet", "veterinarian"), 5),

        // ===== WORK & PROFESSIONAL (Priority 3-5) =====
        EmojiRule("📞", listOf("phone call", "conference call"), 3),
        EmojiRule("💻", listOf("zoom", "teams", "webinar", "video call", "google meet"), 5),
        EmojiRule("📊", listOf("presentation", "sales pitch", "pitch deck"), 5),
        EmojiRule("🤝", listOf("interview", "1:1", "one on one"), 5),
        EmojiRule("🏦", listOf("bank", "mortgage"), 5),
        EmojiRule("💰", listOf("tax", "accountant", "taxes"), 5),

        // ===== PERSONAL & HOME (Priority 3-5) =====
        EmojiRule("💇", listOf("haircut", "salon", "barber"), 5),
        EmojiRule("🛒", listOf("shopping", "groceries"), 3),
        EmojiRule("📦", listOf("delivery", "moving", "pickup"), 5),
        EmojiRule("🔧", listOf("plumber", "repair", "handyman"), 5),
        EmojiRule("🏠", listOf("open house", "house hunting", "realtor"), 5),

        // ===== ENTERTAINMENT (Priority 5) =====
        EmojiRule("🎬", listOf("movie", "cinema", "film festival"), 5),
        EmojiRule("🎵", listOf("concert", "live music"), 5),
        EmojiRule("🎭", listOf("theater", "theatre", "broadway", "recital"), 5),
        EmojiRule("🏛️", listOf("museum", "exhibit", "gallery"), 5),
        EmojiRule("🦁", listOf("zoo", "aquarium"), 5),
        EmojiRule("🎢", listOf("amusement park", "theme park", "disneyland", "disney"), 5),
        EmojiRule("📖", listOf("book club"), 5),

        // ===== EDUCATION (Priority 5) =====
        EmojiRule("📚", listOf("study", "lecture", "exam"), 5),
        EmojiRule("🏫", listOf("school", "pta"), 5),

        // ===== FAMILY & KIDS (Priority 5) =====
        EmojiRule("👶", listOf("daycare", "babysitter", "nanny"), 5),
        EmojiRule("👨‍👩‍👧", listOf("parent teacher", "family dinner"), 3),

        // ===== SOCIAL (Priority 5) =====
        EmojiRule("💕", listOf("date night"), 5),
        EmojiRule("👥", listOf("reunion", "get together"), 5),

        // ===== RELIGIOUS (Priority 3) =====
        EmojiRule("⛪", listOf("church"), 3),
        EmojiRule("🙏", listOf("prayer", "temple", "mosque", "synagogue"), 3),
    )

    /**
     * A single keyword flattened out of its rule, in declaration order.
     * [ruleIndex] is the position of the owning rule in [rules] — the declaration
     * order used to break ties between equal-priority matches.
     */
    private data class KeywordEntry(
        val keyword: String,
        val emoji: String,
        val priority: Int,
        val ruleIndex: Int,
    )

    private val allKeywords: List<KeywordEntry> = buildList {
        rules.forEachIndexed { ruleIndex, rule ->
            for (keyword in rule.keywords) {
                add(
                    KeywordEntry(
                        keyword = keyword.lowercase(Locale.ROOT),
                        emoji = rule.emoji,
                        priority = rule.priority,
                        ruleIndex = ruleIndex,
                    )
                )
            }
        }
    }

    /** Matches runs of word characters (letters, digits, underscore) — the token grain of `\b`. */
    private val wordToken = Regex("\\w+")

    /**
     * Titles mentioning any of these are never decorated: an emoji on a funeral,
     * a diagnosis, or a layoff reads as flippant. Checked as whole title tokens
     * ahead of any keyword match, so suppression always wins.
     */
    private val suppressWords = setOf(
        "funeral", "memorial", "hospice",
        "surgery", "biopsy", "chemo",
        "divorce", "custody", "hearing", "layoff",
    )

    /**
     * Single-word keywords, indexed by the word so a title token resolves with a
     * hash lookup instead of a regex scan. A word can map to several entries when
     * unrelated rules reuse it, so the value is a list resolved by the winner rule.
     */
    private val singleWordIndex: Map<String, List<KeywordEntry>>

    /**
     * Multi-token keywords (containing whitespace/hyphen/colon), each with a
     * precompiled word-boundary regex and its leading `\w+` token. A `\b lead …\b`
     * keyword can only match when [lead] appears as a whole token in the title, so
     * the hot path skips the regex entirely unless the title contains that token.
     */
    private val multiTokenKeywords: List<MultiTokenMatcher>

    init {
        // Split keywords by whether the title tokenizer can find them whole (exactly
        // one `\w+` run). Testing that invariant directly against wordToken — rather
        // than listing separators — keeps the split in lockstep with how the title is
        // tokenized: the same definition of a word decides both which path a keyword
        // takes and how the title is broken up.
        val (multiToken, singleWord) = allKeywords.partition { !it.keyword.matches(wordToken) }
        singleWordIndex = singleWord.groupBy { it.keyword }
        // Drop any keyword with no word character at all: its `\b…\b` regex could
        // never match a real title, and it has no leading token to gate on. Skipping
        // it degrades gracefully instead of letting the gate lookup throw.
        multiTokenKeywords = multiToken.mapNotNull { entry ->
            val lead = wordToken.find(entry.keyword)?.value ?: return@mapNotNull null
            MultiTokenMatcher(
                entry = entry,
                lead = lead,
                regex = Regex("\\b${Regex.escape(entry.keyword)}\\b"),
            )
        }
    }

    private data class MultiTokenMatcher(val entry: KeywordEntry, val lead: String, val regex: Regex)

    /** A keyword that matched the title, with the character span it covered. */
    private data class Candidate(val entry: KeywordEntry, val range: IntRange)

    /**
     * Returns the emoji for an event title, or null if no match.
     *
     * @param title The event title to match
     * @return Emoji string (e.g., "☕") or null if no keyword matches
     */
    fun getEmoji(title: String): String? {
        if (title.isBlank()) return null

        // A title that already carries an emoji (common on synced Apple/Notion
        // events) must not get a second one stacked in front of it.
        if (title.containsEmoji()) return null

        val lowerTitle = title.lowercase(Locale.ROOT)
        val candidates = ArrayList<Candidate>()
        val titleTokens = HashSet<String>()

        // Single-word keywords: resolve each title token through the hash index.
        // The same pass records the token set the multi-token gate reads below,
        // and lets a suppressed term short-circuit the whole title to no emoji.
        for (token in wordToken.findAll(lowerTitle)) {
            if (token.value in suppressWords) return null
            titleTokens.add(token.value)
            val entries = singleWordIndex[token.value] ?: continue
            for (entry in entries) {
                candidates.add(Candidate(entry, token.range))
            }
        }

        // Multi-token keywords: run the precompiled regex only when the keyword's
        // leading token is present — a `\b lead …\b` match is impossible otherwise,
        // so the common no-match title skips every regex scan.
        for (matcher in multiTokenKeywords) {
            if (matcher.lead !in titleTokens) continue
            val match = matcher.regex.find(lowerTitle) ?: continue
            candidates.add(Candidate(matcher.entry, match.range))
        }

        return electWinner(candidates)?.emoji
    }

    /** Higher priority first, then earlier declaration order. */
    private val winnerOrder = compareBy<Candidate>({ -it.entry.priority }, { it.entry.ruleIndex })

    /**
     * Picks the winning candidate. Specificity first: a match overlapped by a
     * strictly longer match is dropped, so "eye doctor" shadows the "doctor" it
     * contains. Among the survivors — none of which is a shorter piece of another —
     * the higher-priority rule wins, and equal priorities break by declaration
     * order. This leaves disjoint matches ("Christmas dinner") decided by priority.
     */
    private fun electWinner(candidates: List<Candidate>): KeywordEntry? {
        if (candidates.size <= 1) return candidates.firstOrNull()?.entry
        val survivors = candidates.filterNot { candidate ->
            candidates.any { other -> other.span > candidate.span && other.range.overlaps(candidate.range) }
        }
        return survivors.minWithOrNull(winnerOrder)?.entry
    }

    /** Character span the match covers, so a longer match can shadow a shorter overlapping one. */
    private val Candidate.span: Int get() = range.last - range.first + 1

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last

    /**
     * True if the string contains an emoji code point. Detects only characters that
     * render as an emoji — never ordinary text — so a title is left undecorated only
     * when it genuinely already carries one. Two signals:
     *  - a code point with default emoji presentation (Unicode Emoji_Presentation), or
     *  - any character followed by U+FE0F, the emoji variation selector, which forces
     *    emoji rendering of an otherwise text-default symbol (✈️, ⛷️, ❤️, and an
     *    explicitly-styled ™️).
     * A *bare* text symbol that merely has an emoji form (™, ✓, ➡, ↔ without FE0F)
     * renders as text and is deliberately not treated as an emoji, so titles like
     * "Zoom™ standup" still decorate. CJK ideographs, Kana, and accented Latin are
     * text and never match.
     */
    private fun String.containsEmoji(): Boolean {
        var i = 0
        while (i < length) {
            val cp = codePointAt(i)
            // U+FE0F only ever trails an emoji base; its presence anywhere in the
            // string is a reliable "this was styled as emoji" signal.
            if (cp == 0xFE0F || cp.isEmojiCodePoint()) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * True only for code points Unicode assigns default emoji presentation
     * (Emoji_Presentation=Yes) — the ones that render as emoji with no variation
     * selector. Text-default symbols (™, ✓, arrows, ↔) are excluded; they reach
     * emoji rendering only via the trailing U+FE0F handled in [containsEmoji].
     * The keyword table's own ✈️/⛷️ are text-default and match through that FE0F
     * path, not here.
     */
    private fun Int.isEmojiCodePoint(): Boolean = when (this) {
        in 0x1F000..0x1FAFF -> true                          // pictographs, transport, symbols
        0x231A, 0x231B, 0x2B50, 0x2B55 -> true               // ⌚⌛⭐⭕
        0x2B1B, 0x2B1C, 0x25FD, 0x25FE -> true               // ⬛⬜◽◾
        in 0x23E9..0x23EC, 0x23F0, 0x23F3 -> true            // ⏩⏪⏫⏬⏰⏳
        in 0x2614..0x2615 -> true                            // ☔☕
        in 0x2648..0x2653 -> true                            // zodiac ♈..♓
        0x267F, 0x2693, 0x26A1, 0x26CE, 0x26D4, 0x26EA -> true // ♿⚓⚡⛎⛔⛪
        in 0x26AA..0x26AB, in 0x26BD..0x26BE, in 0x26C4..0x26C5 -> true // ⚪⚫⚽⚾⛄⛅
        in 0x26F2..0x26F3, 0x26F5, 0x26FA, 0x26FD -> true    // ⛲⛳⛵⛺⛽
        0x2705, in 0x270A..0x270B, 0x2728 -> true            // ✅✊✋✨
        0x274C, 0x274E, in 0x2753..0x2755, 0x2757 -> true    // ❌❎❓❔❕❗
        in 0x2795..0x2797, 0x27B0, 0x27BF -> true            // ➕➖➗➰➿
        else -> false
    }

    /**
     * Formats a title with emoji prefix if a match is found.
     *
     * @param title The event title
     * @param showEmoji Whether to prepend emoji (user preference)
     * @return Title with emoji prefix (e.g., "☕ Coffee with Sarah") or original title
     */
    fun formatWithEmoji(title: String, showEmoji: Boolean): String {
        if (!showEmoji) return title
        val emoji = getEmoji(title) ?: return title
        return "$emoji $title"
    }

    /**
     * Every (keyword, emoji) pair in the rule table, in declaration order.
     * Exposed so tests can assert every keyword resolves to its own emoji in
     * isolation — a guard against a keyword being shadowed by another rule.
     */
    internal fun keywordEmojiPairs(): List<Pair<String, String>> =
        allKeywords.map { it.keyword to it.emoji }
}
