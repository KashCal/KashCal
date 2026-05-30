package org.onekash.kashcal.ui.util.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Returns [text] as an [AnnotatedString] with [style] applied to every
 * non-overlapping occurrence of [query]. Matching is case-insensitive
 * via [String.regionMatches] with `ignoreCase = true`, which folds Latin
 * and Cyrillic case correctly without changing string length.
 *
 * Intentionally NOT lowercasing [text] into a separate buffer: a locale-
 * aware `lowercase()` can change length (Turkish 'İ' → 'i̇' is 1→2
 * code units, German 'ß' round-trips to 'SS'), so indices computed in
 * the lowercased buffer cannot be safely used to slice [text]. Using
 * `regionMatches` against [text] directly keeps highlight offsets aligned
 * with the original string under every locale.
 *
 * When [query] is empty or whitespace-only, returns the input unchanged
 * with no spans. This is the empty-query fast path required by C1: callers
 * that know the query is empty should skip this function entirely and
 * pass the raw [String] to `Text` for byte-identical rendering.
 */
fun highlighted(text: String, query: String, style: SpanStyle): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)

    val q = query.length
    return buildAnnotatedString {
        var cursor = 0
        while (cursor <= text.length - q) {
            if (text.regionMatches(cursor, query, 0, q, ignoreCase = true)) {
                withStyle(style) {
                    append(text.substring(cursor, cursor + q))
                }
                cursor += q
            } else {
                append(text[cursor])
                cursor++
            }
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
