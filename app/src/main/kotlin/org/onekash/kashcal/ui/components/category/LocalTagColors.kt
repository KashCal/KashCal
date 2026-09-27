package org.onekash.kashcal.ui.components.category

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Holds per-tag custom colors by tag name, null for no custom color.
 *
 * `MainActivity` provides it from HomeViewModel's `tagColors`, fed by
 * `EventReader.observeTagColors()`, so chips repaint on a recolor without threading the color
 * through the event and occurrence streams (which would re-materialize it on every recolor).
 * Without a provider (previews, tests) the map is empty and every tag gets its hash color.
 */
val LocalTagColors = staticCompositionLocalOf<Map<String, Int?>> { emptyMap() }

/**
 * Returns tag [name]'s display color: the non-null entry in [customColors], else [colorForTag].
 * ARGB Int math, so it is plain-JVM unit-testable.
 *
 * The lookup is case-insensitive because the map is keyed by the metadata row's stored casing
 * while a chip passes its event's category string, whose casing can differ (a migration backfill
 * or server pull may keep another first-seen casing). An exact-case lookup would silently miss
 * the custom color. The row's `COLLATE NOCASE` primary key allows at most one match.
 */
fun colorFor(customColors: Map<String, Int?>, name: String): Int {
    val custom = customColors.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    return custom ?: colorForTag(name)
}
