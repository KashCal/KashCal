package org.onekash.kashcal.domain.search

import androidx.compose.runtime.Immutable

/** Where a search word matched when the title doesn't explain the result. */
enum class MatchField { LOCATION, NOTES, GUEST }

/** The line a search result shows under its date: [field] and the text that matched there. */
@Immutable
data class MatchReason(val field: MatchField, val text: String)

/**
 * Says why an event matched a search when its title alone doesn't: the first of location,
 * notes and guests that holds a word the title lacks, matched as the search matches
 * ([SearchFold], guest addresses through [SearchQuery.emailShapedAddress]).
 */
object SearchMatchExplainer {

    /** One guest: a display name and an address as stored (`mailto:` allowed). */
    data class Guest(val name: String?, val address: String?)

    private val WHITESPACE = Regex("\\s+")

    /** Characters of notes kept on each side of the match. */
    private const val NOTES_CONTEXT = 40

    /**
     * Returns the reason line for [words], or null when [title] holds every word or no other
     * field holds one. Notes are shown on one line around the first match, cut with "…"; a
     * guest by its name when the name matched, else by its email without `mailto:`.
     */
    fun explain(
        words: List<String>,
        title: String,
        location: String?,
        notes: String?,
        guests: List<Guest>,
    ): MatchReason? {
        val missing = words.filterNot { SearchFold.contains(title, it) }.map(SearchFold::fold)
        if (missing.isEmpty()) return null
        fun matches(text: String?) = text != null && SearchFold.fold(text).let { folded -> missing.any { it in folded } }

        if (matches(location)) return MatchReason(MatchField.LOCATION, location!!)
        if (notes != null) {
            val flat = notes.replace(WHITESPACE, " ").trim()
            val folded = SearchFold.fold(flat)
            val at = missing.map { folded.indexOf(it) }.filter { it >= 0 }.minOrNull()
            if (at != null) return MatchReason(MatchField.NOTES, snippet(flat, at))
        }
        for (guest in guests) {
            if (matches(guest.name)) return MatchReason(MatchField.GUEST, guest.name!!)
            val email = SearchQuery.emailShapedAddress(guest.address)
            if (matches(email)) return MatchReason(MatchField.GUEST, email!!)
        }
        return null
    }

    private fun snippet(text: String, at: Int): String {
        val from = (at - NOTES_CONTEXT).coerceAtLeast(0)
        val to = (at + NOTES_CONTEXT * 2).coerceAtMost(text.length)
        return buildString {
            if (from > 0) append('…')
            append(text, from, to)
            if (to < text.length) append('…')
        }
    }
}
