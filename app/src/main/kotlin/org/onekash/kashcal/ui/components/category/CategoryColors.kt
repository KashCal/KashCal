package org.onekash.kashcal.ui.components.category

/**
 * Deterministic tag colors for tags without a custom color ([colorFor] picks between them).
 * Names hash into a fixed palette, ignoring case, so `Work` and `work` (one tag under
 * case-insensitive dedup) share a swatch.
 *
 * ARGB Int math with no Compose `Color`, so it is plain-JVM unit-testable.
 */

/** The seven-color tag palette (opaque ARGB). */
val CATEGORY_PALETTE = intArrayOf(
    0xFF4457C9.toInt(), // indigo
    0xFF2E9F63.toInt(), // green
    0xFFE04A8E.toInt(), // pink
    0xFFE47F1B.toInt(), // amber
    0xFF1F9E98.toInt(), // teal
    0xFF7B3CA8.toInt(), // purple
    0xFFC4371D.toInt(), // red
)

/**
 * Maps a tag [name] to a stable palette color, lowercased first to match the case-insensitive
 * dedup rule.
 */
fun colorForTag(name: String): Int {
    val hash = name.lowercase().hashCode() and Int.MAX_VALUE
    return CATEGORY_PALETTE[hash % CATEGORY_PALETTE.size]
}

/**
 * Returns a readable foreground (label, "x") for a chip filled with [background]: white when
 * its Rec. 709 luminance is below 140 of 255, else black. Legible in light and dark themes.
 */
fun onColorFor(background: Int): Int {
    val r = (background shr 16) and 0xFF
    val g = (background shr 8) and 0xFF
    val b = background and 0xFF
    // Luminance in 0..255.
    val luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b)
    return if (luminance < 140.0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
}
