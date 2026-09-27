package org.onekash.kashcal.domain.category

/** Why a typed tag name was rejected. */
enum class CategoryNameError {
    /** Blank after trimming and stripping a leading '#'. */
    EMPTY,

    /** Contains a comma (the RFC 5545 CATEGORIES wire separator). */
    COMMA,

    /** Longer than [CategoryNameValidator.MAX_LENGTH] after trimming and stripping. */
    TOO_LONG,
}

/** Outcome of validating free-typed tag input. */
sealed interface CategoryName {
    /**
     * A usable tag name. [value] carries the casing to store: the input's own (trimmed,
     * hash-stripped), or that of a case-insensitive match in the caller's existing set.
     */
    data class Valid(val value: String) : CategoryName

    /** The input isn't a usable tag name. */
    data class Invalid(val error: CategoryNameError) : CategoryName
}

/**
 * The single source of truth for tag-name rules, shared by every entry point (for example the
 * form's chip input, inline "#" autocomplete, Quick Add "#tag" extraction and tag rename) so a
 * name accepted in one place is accepted in all.
 *
 * Rules, in order: trim whitespace; strip one leading '#' and trim again; reject empty; reject
 * commas; reject more than [MAX_LENGTH] chars. Comparison against existing tags is
 * case-insensitive, but storage keeps first-seen casing: `Work` and `work` are the same tag, and
 * whichever was created first keeps its casing.
 */
object CategoryNameValidator {

    const val MAX_LENGTH = 64

    /**
     * Validates [raw] against the tag-name rules.
     *
     * @param existing known tag names, any casing. When [raw] matches one case-insensitively,
     *   the result carries the existing value, so the caller reuses the stored tag instead of
     *   creating a differently cased duplicate.
     */
    fun validate(raw: String, existing: Set<String>? = null): CategoryName {
        var name = raw.trim()
        if (name.startsWith("#")) {
            name = name.removePrefix("#").trim()
        }

        if (name.isEmpty()) return CategoryName.Invalid(CategoryNameError.EMPTY)
        if (name.contains(',')) return CategoryName.Invalid(CategoryNameError.COMMA)
        if (name.length > MAX_LENGTH) return CategoryName.Invalid(CategoryNameError.TOO_LONG)

        val match = existing?.firstOrNull { it.equals(name, ignoreCase = true) }
        return CategoryName.Valid(match ?: name)
    }

    /**
     * Returns the cleaned name for valid input, with the casing [validate] picks, or null if the
     * input is rejected.
     */
    fun normalize(raw: String, existing: Set<String>? = null): String? =
        (validate(raw, existing) as? CategoryName.Valid)?.value
}
