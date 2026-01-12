package org.onekash.kashcal.util.location

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Detect if text looks like a street address.
 *
 * Uses word boundaries to avoid false positives like:
 * - "Dr. Smith" (dr as title, not drive)
 * - "Standup meeting" (st as part of word)
 * - "My Way Productions" (way as part of name)
 *
 * Positive detection requires:
 * - At least one digit (street number)
 * - At least one letter (street name)
 * - Either a street word OR comma-separated parts (city, state)
 *
 * @param text The location text to check
 * @return true if text appears to be a street address
 */
fun looksLikeAddress(text: String): Boolean {
    if (text.isBlank()) return false

    val hasNumber = text.any { it.isDigit() }
    val hasLetters = text.any { it.isLetter() }

    if (!hasNumber || !hasLetters) return false

    // Street words - must match as whole words (word boundary detection)
    val streetWords = setOf(
        "st", "street", "ave", "avenue", "blvd", "boulevard",
        "road", "rd", "drive", "dr", "lane", "ln", "way",
        "ct", "court", "pl", "place", "pkwy", "parkway",
        "cir", "circle", "ter", "terrace", "hwy", "highway"
    )

    // Split into words and check for exact match
    val words = text.lowercase().split(Regex("[\\s,]+"))
    val hasStreetWord = words.any { it in streetWords }

    // Address pattern: has number + letters + (street word OR comma-separated parts like "City, State")
    return hasStreetWord || text.count { it == ',' } >= 1
}

/**
 * Open address in maps app with web fallback.
 *
 * Uses geo: URI scheme supported by most maps applications.
 * Falls back to web maps if no maps app is installed.
 *
 * @param context Android context for launching intent
 * @param address The address to open in maps
 */
fun openInMaps(context: Context, address: String) {
    val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(address)}")
    val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)

    if (mapIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(mapIntent)
    } else {
        // Fallback: open web maps
        val webUri = Uri.parse("https://www.google.com/maps/search/${Uri.encode(address)}")
        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
    }
}
