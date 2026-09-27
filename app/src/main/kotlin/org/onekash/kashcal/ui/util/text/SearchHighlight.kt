package org.onekash.kashcal.ui.util.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Returns [text] as an [AnnotatedString] with [style] on every non-overlapping occurrence of
 * [query], matched ignoring case.
 *
 * Matching uses [String.regionMatches] with `ignoreCase = true` against [text] itself, which
 * folds Latin and Cyrillic case per character without changing string length. Don't lowercase
 * [text] into a separate buffer: case mapping can change length (Turkish 'İ' lowercases to the
 * two code units 'i̇', German 'ß' uppercases to 'SS'), so indices from that buffer would
 * misplace highlights in [text].
 *
 * An empty or whitespace-only [query] returns [text] unchanged with no spans. Callers that know
 * the query is empty should skip this function and pass the raw [String] to `Text` for
 * byte-identical rendering.
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
