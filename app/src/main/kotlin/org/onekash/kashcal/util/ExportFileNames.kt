package org.onekash.kashcal.util

import java.text.Normalizer

// Keeps Unicode letters, combining marks, digits, hyphens and whitespace from any script;
// strips everything else. Marks are kept so NFD-form diacritics (common in iCloud/macOS
// data) don't lose the accent off their base letter. \p{Z} keeps non-ASCII spaces (nbsp,
// ideographic space) so they collapse to a separator; deleting them would run adjacent words
// together.
private val DISALLOWED_CHARS = Regex("[^\\p{L}\\p{M}\\p{N}\\s\\p{Z}-]")
private val WHITESPACE_RUN = Regex("[\\s\\p{Z}]+")

/**
 * Returns a filesystem-safe base name, without extension, for a cached export file.
 *
 * Letters and digits from any script survive, so "Straße", "Müller" and "会議" aren't reduced
 * to ASCII. Punctuation other than `-`, symbols, emoji and path-unsafe characters (`/`, `\`,
 * `:`, `*`) are removed. Whitespace runs, non-ASCII spaces included, become one hyphen. The
 * name is then capped at [maxLength] chars on a code-point boundary, so a supplementary-plane
 * letter is never split into a lone surrogate, and leading and trailing hyphens are trimmed
 * after the cap. [fallback] is returned when nothing remains.
 */
fun sanitizeExportBaseName(name: String, fallback: String, maxLength: Int = 50): String {
    val cleaned = Normalizer.normalize(name, Normalizer.Form.NFC)
        .replace(DISALLOWED_CHARS, "")
        .replace(WHITESPACE_RUN, "-")
        .take(maxLength)
        .trimTrailingLoneSurrogate()
        .trim('-') // after take() so truncation can't leave a dangling hyphen
    return cleaned.ifEmpty { fallback }
}

private fun String.trimTrailingLoneSurrogate(): String =
    if (isNotEmpty() && last().isHighSurrogate()) dropLast(1) else this
