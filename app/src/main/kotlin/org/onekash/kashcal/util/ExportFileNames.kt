package org.onekash.kashcal.util

import java.text.Normalizer

// Keep Unicode letters, combining marks (accents on decomposed input), digits,
// and any whitespace from any script; strip everything else. Marks are kept so
// NFD-form diacritics (common in iCloud/macOS data) survive rather than dropping
// the accent off its base letter. \p{Z} keeps non-ASCII spaces (nbsp, ideographic
// space) so they collapse to a separator rather than being deleted, which would
// run adjacent words together.
private val DISALLOWED_CHARS = Regex("[^\\p{L}\\p{M}\\p{N}\\s\\p{Z}-]")
private val WHITESPACE_RUN = Regex("[\\s\\p{Z}]+")

/**
 * Produce a filesystem-safe base name (no extension) for a cached export file.
 *
 * Preserves Unicode letters and digits from any script, so titles like
 * "Straße", "Müller", or "会議" keep their characters instead of being reduced
 * to ASCII. Punctuation, symbols, emoji, and characters that are unsafe in a
 * path (`/`, `\`, `:`, `*`, …) are removed, because those are not letters,
 * marks, or digits. Whitespace (including non-ASCII spaces) collapses to single
 * hyphens; the name is then capped at [maxLength] on a code-point boundary (so a
 * supplementary-plane letter is never split into a lone surrogate), leading and
 * trailing hyphens are trimmed after the cap, and [fallback] is used when nothing
 * usable remains.
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
