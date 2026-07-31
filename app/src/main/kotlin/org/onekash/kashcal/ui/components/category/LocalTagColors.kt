package org.onekash.kashcal.ui.components.category

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Per-tag custom colors, keyed by tag name (a null value means "no custom color
 * chosen"). Provided at screen roots from `CategoryRepository.observeColors()`
 * so chips repaint when the user recolors a tag, without threading the color
 * through the event/occurrence stream (which would re-materialize it on every
 * recolor).
 *
 * The default is an empty map: any composable rendered without a provider
 * (previews, tests) simply falls back to the hash-derived color.
 */
val LocalTagColors = staticCompositionLocalOf<Map<String, Int?>> { emptyMap() }

/**
 * Resolve the display color for tag [name]: the user's chosen color if the map
 * carries a non-null entry for it, otherwise the deterministic hash color. Pure
 * ARGB Int math so it is plain-JVM unit-testable independent of Compose.
 *
 * The lookup is case-insensitive to match the rest of the tag system (the
 * metadata row's name is `COLLATE NOCASE`). The map is keyed by the row's
 * stored casing, but a chip resolves against its event's category string, whose
 * casing can differ (a migration backfill or server pull may keep a different
 * first-seen casing) — an exact-case-only lookup would silently miss the custom
 * color and fall back to the hash. A case-insensitive PK guarantees at most one
 * matching entry, so the scan is unambiguous.
 */
fun colorFor(customColors: Map<String, Int?>, name: String): Int {
    val custom = customColors.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    return custom ?: colorForTag(name)
}
