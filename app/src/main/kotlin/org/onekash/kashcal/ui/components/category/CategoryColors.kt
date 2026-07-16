package org.onekash.kashcal.ui.components.category

/**
 * Deterministic tag colors used until a persisted per-tag color store exists.
 * Names hash into a fixed palette; the same name always maps to the same
 * swatch, and casing is ignored so `Work` and `work` (the same tag under
 * case-insensitive dedup) share a color.
 *
 * Pure ARGB Int math — no Compose `Color` — so it is plain-JVM unit-testable.
 * Once user-chosen colors are stored, call sites become
 * `categoryRepository.colorFor(name) ?: colorForTag(name)`, keeping this as
 * the fallback for tags without a stored color yet.
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
 * Map a tag [name] to a stable palette color. Lowercased before hashing so the
 * result matches the case-insensitive dedup rule (`Work` == `work`).
 */
fun colorForTag(name: String): Int {
    val hash = name.lowercase().hashCode() and Int.MAX_VALUE
    return CATEGORY_PALETTE[hash % CATEGORY_PALETTE.size]
}

/**
 * Choose a readable foreground (label / "x") for a filled chip of background
 * [background]: white on dark, black on light, decided by relative luminance
 * (Rec. 709 coefficients). Keeps filled chips legible in both light and dark
 * themes.
 */
fun onColorFor(background: Int): Int {
    val r = (background shr 16) and 0xFF
    val g = (background shr 8) and 0xFF
    val b = background and 0xFF
    // Perceptual luminance in 0..255.
    val luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b)
    return if (luminance < 140.0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
}
