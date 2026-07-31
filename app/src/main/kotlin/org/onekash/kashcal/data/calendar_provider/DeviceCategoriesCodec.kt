package org.onekash.kashcal.data.calendar_provider

/**
 * Encode/decode device-calendar tags for CalendarProvider's ExtendedProperties.
 *
 * `CalendarContract.Events` has no categories column; the only per-event
 * key/value store is the generic `ExtendedProperties` table. Tags are stored
 * there under the NAME [EXTNAME_CATEGORIES], with the tag names joined into one
 * VALUE by a single backslash character. A backslash inside a name is dropped
 * before joining so the separator stays unambiguous. This is the shape
 * third-party CalDAV sync adapters read back as iCalendar CATEGORIES, so a tag
 * added here round-trips to the server for calendars synced that way; a local
 * or Google-synced calendar keeps (or drops) it without traveling as CATEGORIES.
 *
 * The read path tolerates arbitrary foreign content — any casing, names this
 * app never created, empty or malformed values — and never throws.
 */

/** ExtendedProperties NAME under which tag names are stored. */
internal const val EXTNAME_CATEGORIES = "categories"

/**
 * Separator between tag names in the stored VALUE. A single backslash character
 * (byte 0x5C); the `'\\'` literal denotes one char, not two.
 */
internal const val CATEGORIES_SEPARATOR = '\\'

/**
 * Join [names] into a single ExtendedProperties VALUE, or return null when
 * nothing usable survives (the caller then writes no row / clears an existing
 * one). Each name is trimmed and has every backslash removed so it can't be
 * confused with the separator; blanks are dropped and duplicates are collapsed
 * case-insensitively, keeping the first-seen casing.
 */
internal fun encodeCategories(names: List<String>): String? {
    val cleaned = cleanCategoryNames(names)
    if (cleaned.isEmpty()) return null
    return cleaned.joinToString(CATEGORIES_SEPARATOR.toString())
}

/**
 * The exact tag names [encodeCategories] would store: each trimmed, with every
 * backslash removed, blanks dropped, and duplicates collapsed case-insensitively
 * (first-seen casing kept). Exposed so the registry records the same names that
 * land in the provider — a tag like `a\b` is stored as `ab`, so it must be
 * reconciled as `ab`, not the raw form value.
 */
internal fun cleanCategoryNames(names: List<String>): List<String> {
    val cleaned = LinkedHashMap<String, String>() // lowercase key -> first-seen casing
    for (raw in names) {
        val name = raw.replace(CATEGORIES_SEPARATOR.toString(), "").trim()
        if (name.isEmpty()) continue
        cleaned.putIfAbsent(name.lowercase(), name)
    }
    return cleaned.values.toList()
}

/**
 * Split a stored ExtendedProperties [value] back into tag names. Returns an
 * empty list for null/blank input; otherwise splits on the separator, trims
 * each segment, and drops blanks. Casing is left exactly as stored — foreign
 * content is surfaced verbatim.
 */
internal fun decodeCategories(value: String?): List<String> {
    if (value.isNullOrBlank()) return emptyList()
    return value.split(CATEGORIES_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
