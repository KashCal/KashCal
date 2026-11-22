package org.onekash.kashcal.ui.util.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import org.onekash.kashcal.domain.search.SearchFold

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

/**
 * Returns [text] with [style] on every occurrence of each of [words], matched the way search
 * matches ([SearchFold]: case folded per code point, accents kept), overlapping matches joined
 * into one span. Folding keeps every index in place, so spans never shift for "İ" or "ß".
 */
fun highlighted(text: String, words: List<String>, style: SpanStyle): AnnotatedString {
    val folded = SearchFold.fold(text)
    val ranges = words.filter { it.isNotBlank() }.map(SearchFold::fold).flatMap { word ->
        generateSequence(folded.indexOf(word).takeIf { it >= 0 }) { at -> folded.indexOf(word, at + 1).takeIf { it >= 0 } }
            .map { it until it + word.length }
            .toList()
    }.sortedBy { it.first }
    if (ranges.isEmpty()) return AnnotatedString(text)
    val merged = mutableListOf<IntRange>()
    for (range in ranges) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return buildAnnotatedString {
        append(text)
        merged.forEach { addStyle(style, it.first, it.last + 1) }
    }
}
