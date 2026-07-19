package org.onekash.kashcal.ui.components.hub

import java.util.Locale

/**
 * Normalizes free-form input into a display monogram of at most two letters:
 * keep only letters, uppercase them locale-independently, and take the first
 * two. A blank/letter-free input yields an empty string, which callers render
 * as the avatar's generic glyph rather than a monogram.
 *
 * Iterates by Unicode code point (not by `Char`) so an astral-plane letter is
 * never split into a broken half of a surrogate pair. Uppercasing uses
 * [Locale.ROOT] so casing is stable across device locales (e.g. it avoids the
 * Turkish dotless-i mapping "i" -> "İ"), and is applied per letter BEFORE the
 * take-two so a letter whose uppercase expands (e.g. German "ß" -> "SS") still
 * counts as one and the result never exceeds two source letters.
 */
fun normalizeInitials(raw: String): String {
    val letters = StringBuilder()
    var taken = 0
    var i = 0
    while (i < raw.length && taken < 2) {
        val cp = raw.codePointAt(i)
        val charCount = Character.charCount(cp)
        if (Character.isLetter(cp)) {
            letters.append(String(Character.toChars(cp)).uppercase(Locale.ROOT))
            taken++
        }
        i += charCount
    }
    return letters.toString()
}
