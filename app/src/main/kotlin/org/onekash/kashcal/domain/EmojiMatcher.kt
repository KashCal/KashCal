package org.onekash.kashcal.domain

/**
 * Matches event titles to emojis based on keywords.
 *
 * Used for display-only decoration of event titles across all calendar types
 * (iCloud, ICS subscriptions, Contact Birthdays, Local).
 *
 * Matching rules:
 * - Case-insensitive
 * - Word boundary matching (prevents partial matches like "scoffee" → coffee)
 * - Priority-based (higher priority rules checked first)
 * - First match wins
 */
object EmojiMatcher {

    private data class EmojiRule(
        val emoji: String,
        val keywords: List<String>,
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
        EmojiRule("🍺", listOf("drinks", "happy hour", "bar", "pub", "brewery"), 5),
        EmojiRule("🍷", listOf("wine", "winery", "wine tasting"), 5),
        EmojiRule("🍕", listOf("pizza"), 5),
        EmojiRule("🍖", listOf("bbq", "barbecue", "cookout", "grill"), 5),

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
        EmojiRule("🏃", listOf("run", "running", "jog", "marathon", "5k", "10k"), 5),
        EmojiRule("🧘", listOf("yoga", "meditation", "pilates"), 5),
        EmojiRule("🏊", listOf("swim", "swimming", "pool"), 5),
        EmojiRule("🎾", listOf("tennis"), 5),
        EmojiRule("⛳", listOf("golf", "tee time"), 5),
        EmojiRule("⚽", listOf("soccer"), 5),
        EmojiRule("🏀", listOf("basketball"), 5),
        EmojiRule("🥾", listOf("hike", "hiking", "trail"), 5),
        EmojiRule("⛷️", listOf("ski", "skiing", "snowboard"), 5),
        EmojiRule("🚴", listOf("cycling", "bike ride"), 5),
        EmojiRule("🎳", listOf("bowling"), 5),

        // ===== HEALTH & MEDICAL (Priority 5) =====
        EmojiRule("👨‍⚕️", listOf("doctor", "dentist", "checkup", "physical"), 5),
        EmojiRule("👁️", listOf("eye doctor", "optometrist", "eye exam"), 5),
        EmojiRule("💆", listOf("spa", "massage"), 5),
        EmojiRule("🧠", listOf("therapy", "therapist", "counseling"), 5),
        EmojiRule("💊", listOf("pharmacy", "prescription"), 5),
        EmojiRule("🐕", listOf("vet", "veterinarian"), 5),

        // ===== WORK & PROFESSIONAL (Priority 3-5) =====
        EmojiRule("📞", listOf("call", "phone call"), 3),
        EmojiRule("💻", listOf("zoom", "teams", "webinar", "video call", "google meet"), 5),
        EmojiRule("📊", listOf("presentation", "demo", "pitch"), 5),
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
        EmojiRule("🎬", listOf("movie", "cinema", "film"), 5),
        EmojiRule("🎵", listOf("concert", "live music"), 5),
        EmojiRule("🎭", listOf("theater", "theatre", "broadway", "recital"), 5),
        EmojiRule("🏛️", listOf("museum", "exhibit", "gallery"), 5),
        EmojiRule("🦁", listOf("zoo", "aquarium"), 5),
        EmojiRule("🎢", listOf("amusement park", "theme park", "disneyland", "disney"), 5),
        EmojiRule("📖", listOf("book club"), 5),

        // ===== EDUCATION (Priority 5) =====
        EmojiRule("📚", listOf("study", "class", "lecture", "exam", "test"), 5),
        EmojiRule("🏫", listOf("school", "pta"), 5),

        // ===== FAMILY & KIDS (Priority 5) =====
        EmojiRule("👶", listOf("daycare", "babysitter", "nanny"), 5),
        EmojiRule("👨‍👩‍👧", listOf("parent teacher", "family dinner"), 3),

        // ===== SOCIAL (Priority 5) =====
        EmojiRule("💕", listOf("date night"), 5),
        EmojiRule("👥", listOf("reunion", "get together"), 5),

        // ===== RELIGIOUS (Priority 3) =====
        EmojiRule("⛪", listOf("church", "mass"), 3),
        EmojiRule("🙏", listOf("prayer", "temple", "mosque", "synagogue"), 3),
    ).sortedByDescending { it.priority }

    /**
     * Returns the emoji for an event title, or null if no match.
     *
     * @param title The event title to match
     * @return Emoji string (e.g., "☕") or null if no keyword matches
     */
    fun getEmoji(title: String): String? {
        if (title.isBlank()) return null

        val lowerTitle = title.lowercase()
        for (rule in rules) {
            for (keyword in rule.keywords) {
                if (lowerTitle.containsWord(keyword)) {
                    return rule.emoji
                }
            }
        }
        return null
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
     * Word boundary matching to prevent partial matches.
     * "scoffee" should NOT match "coffee".
     */
    private fun String.containsWord(word: String): Boolean {
        // Use regex with word boundaries for accurate matching
        val pattern = "\\b${Regex.escape(word)}\\b".toRegex(RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(this)
    }
}
