package org.onekash.kashcal.domain

import java.util.Locale

/**
 * Matches event titles to emojis by keyword, for display only: the stored title never changes.
 * Covers event titles from every calendar source, including device calendars, and the Quick Add
 * preview.
 *
 * Matching rules:
 * - Case-insensitive, folded with [Locale.ROOT] so the result never depends on the device
 *   locale (Turkish folds 'I' to a dotless 'ı', which must not change how the ASCII keyword
 *   table matches).
 * - Whole words only, so "scoffee" doesn't match coffee.
 * - When two keywords match overlapping spans of the title, the longer match wins, so "eye
 *   doctor" beats "doctor".
 * - Otherwise the higher-priority rule wins; equal priorities break by declaration order.
 *
 * The matchers are built at class load: single-word keywords resolve through a hash map keyed
 * on the title's tokens, and only multi-token keywords use a precompiled word-boundary regex,
 * so a title with no match costs one tokenize pass.
 */
object EmojiMatcher {

    private data class EmojiRule(
        val emoji: String,
        val keywords: List<String>,
        // Decides only disjoint matches in one title (e.g. christmas vs dinner); overlapping
        // matches go to the longer one.
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

        // ===== TRAVEL (Priority 3-5) =====
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

        // ===== FAMILY & KIDS (Priority 3-5) =====
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
     * One keyword flattened out of its rule, in declaration order. [ruleIndex] is the owning
     * rule's position in [rules], which breaks ties between equal-priority matches.
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

    /** Matches runs of word characters (letters, digits, underscore), the token grain of `\b`. */
    private val wordToken = Regex("\\w+")

    /**
     * Titles containing any of these as a whole token are never decorated: an emoji on a
     * funeral, a diagnosis or a layoff reads as flippant. Suppression wins over any keyword.
     */
    private val suppressWords = setOf(
        "funeral", "memorial", "hospice",
        "surgery", "biopsy", "chemo",
        "divorce", "custody", "hearing", "layoff",
    )

    /**
     * Single-word keywords, indexed by the word so a title token resolves with a hash lookup.
     * A word can map to several entries when rules reuse it; [electWinner] picks between them.
     */
    private val singleWordIndex: Map<String, List<KeywordEntry>>

    /**
     * Multi-token keywords (containing a space, hyphen or colon), each with a precompiled
     * word-boundary regex and its leading `\w+` token. Such a keyword can only match when its
     * lead is a whole token of the title, so the regex runs only for titles containing it.
     */
    private val multiTokenKeywords: List<MultiTokenMatcher>

    init {
        // A keyword is single-word when it is one whole `\w+` run. Testing against wordToken,
        // not a list of separators, keeps the split in step with how the title is tokenized.
        val (multiToken, singleWord) = allKeywords.partition { !it.keyword.matches(wordToken) }
        singleWordIndex = singleWord.groupBy { it.keyword }
        // A keyword with no word character can never match and has no lead token, so it is
        // dropped instead of letting the lead lookup throw.
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
     * Returns the emoji (e.g. "☕") for [title], or null when the title is blank, already has an
     * emoji, contains a suppressed word, or matches no keyword.
     */
    fun getEmoji(title: String): String? {
        if (title.isBlank()) return null

        // A title that already carries an emoji must not get a second one in front of it.
        if (title.containsEmoji()) return null

        val lowerTitle = title.lowercase(Locale.ROOT)
        val candidates = ArrayList<Candidate>()
        val titleTokens = HashSet<String>()

        // Single-word keywords resolve through the hash index. The same pass records the
        // tokens the multi-token check reads below and stops at a suppressed word.
        for (token in wordToken.findAll(lowerTitle)) {
            if (token.value in suppressWords) return null
            titleTokens.add(token.value)
            val entries = singleWordIndex[token.value] ?: continue
            for (entry in entries) {
                candidates.add(Candidate(entry, token.range))
            }
        }

        // Multi-token keywords: run the regex only when the keyword's lead token is present,
        // since it can't match otherwise.
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
     * Picks the winning candidate. A match overlapped by a strictly longer match is dropped, so
     * "eye doctor" shadows the "doctor" it contains. Among the rest, the higher-priority rule
     * wins and equal priorities break by declaration order, so disjoint matches ("Christmas
     * dinner") are decided by priority.
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
     * Returns true if the string contains a character that renders as an emoji, so a title is
     * left undecorated only when it already carries one. Two signals:
     *  - a code point with default emoji presentation (Unicode Emoji_Presentation), or
     *  - U+FE0F, the emoji variation selector, which forces emoji rendering of a text-default
     *    symbol (✈️, ⛷️, ❤️, and a styled ™️).
     * A bare text symbol that has an emoji form (™, ✓, ➡, ↔ without FE0F) renders as text and
     * is deliberately not an emoji, so "Zoom™ standup" still decorates. CJK ideographs, Kana
     * and accented Latin never match.
     */
    private fun String.containsEmoji(): Boolean {
        var i = 0
        while (i < length) {
            val cp = codePointAt(i)
            // U+FE0F anywhere in the string means a character was styled as emoji.
            if (cp == 0xFE0F || cp.isEmojiCodePoint()) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * Returns true only for code points with default emoji presentation (Emoji_Presentation=Yes),
     * which render as emoji with no variation selector. Text-default symbols (™, ✓, arrows, ↔)
     * are excluded; they render as emoji only with the U+FE0F that [containsEmoji] checks. The
     * keyword table's own ✈️ and ⛷️ are text-default and match through that FE0F check.
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
     * Returns [title] with its emoji and a space prepended ("☕ Coffee with Sarah"), or [title]
     * unchanged when [showEmoji] (the user preference) is off or [getEmoji] finds none.
     */
    fun formatWithEmoji(title: String, showEmoji: Boolean): String {
        if (!showEmoji) return title
        val emoji = getEmoji(title) ?: return title
        return "$emoji $title"
    }

    /**
     * Returns every (keyword, emoji) pair in the rule table, in declaration order, so
     * `EmojiMatcherTest` can check that each keyword alone resolves to its own emoji and no rule
     * shadows it.
     */
    internal fun keywordEmojiPairs(): List<Pair<String, String>> =
        allKeywords.map { it.keyword to it.emoji }
}
