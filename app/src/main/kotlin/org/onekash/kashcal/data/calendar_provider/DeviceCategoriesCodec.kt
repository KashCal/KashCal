package org.onekash.kashcal.data.calendar_provider

/**
 * Device-event tags in CalendarProvider's ExtendedProperties.
 *
 * `CalendarContract.Events` has no categories column; the only per-event key/value store is
 * the `ExtendedProperties` table. Tags are stored there under the NAME [EXTNAME_CATEGORIES],
 * joined into one VALUE by a single backslash; a backslash inside a name is dropped before
 * joining so the separator stays unambiguous. Third-party CalDAV sync adapters read this shape
 * back as iCalendar CATEGORIES, so a tag added here reaches the server for calendars synced
 * that way; a local or Google-synced calendar keeps (or drops) it without sending CATEGORIES.
 *
 * The read accepts any foreign content (any casing, names this app never wrote, empty or
 * malformed values) and never throws.
 */

/** ExtendedProperties NAME under which tag names are stored. */
internal const val EXTNAME_CATEGORIES = "categories"

/** Separator between tag names in the stored VALUE: one backslash (0x5C); `'\\'` is one char. */
internal const val CATEGORIES_SEPARATOR = '\\'

/**
 * Joins [names], cleaned by [cleanCategoryNames], into one ExtendedProperties VALUE, or returns
 * null when no name survives (the caller then writes no row, or clears the existing one).
 */
internal fun encodeCategories(names: List<String>): String? {
    val cleaned = cleanCategoryNames(names)
    if (cleaned.isEmpty()) return null
    return cleaned.joinToString(CATEGORIES_SEPARATOR.toString())
}

/**
 * Returns the tag names [encodeCategories] stores: each with every backslash removed and then
 * trimmed, blanks dropped, and duplicates collapsed case-insensitively keeping the first-seen
 * casing. Exposed so the tag registry records the names that land in the provider: `a\b` is
 * stored as `ab`, so it must be reconciled as `ab`, not the raw form value.
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
 * Splits a stored ExtendedProperties [value] into tag names: splits on the separator, trims
 * each part and drops blanks. Null or blank input gives an empty list. Casing is kept as
 * stored, so foreign content shows as written.
 */
internal fun decodeCategories(value: String?): List<String> {
    if (value.isNullOrBlank()) return emptyList()
    return value.split(CATEGORIES_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
