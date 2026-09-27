package org.onekash.kashcal.ui.components.hub

import java.util.Locale

/**
 * Normalizes free-form input into a monogram of at most two letters: keeps only letters,
 * uppercases them locale-independently and takes the first two. Input with no letter yields an
 * empty string, which [AccountAvatar] renders as its generic glyph.
 *
 * Iterates by code point, not `Char`, so an astral-plane letter is never split into half a
 * surrogate pair. [Locale.ROOT] keeps casing stable across device locales (no Turkish
 * "i" -> "İ"). Each letter is uppercased as it is taken, so one whose uppercase expands
 * (German "ß" -> "SS") still counts once and the result never exceeds two source letters.
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
