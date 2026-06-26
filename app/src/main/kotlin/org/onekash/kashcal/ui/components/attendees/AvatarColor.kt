package org.onekash.kashcal.ui.components.attendees

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import org.onekash.kashcal.util.AddressNormalizer

/**
 * Deterministic per-identity avatar colour: `hash(canonical address) %
 * paletteSize`. The same person always gets the same bucket across the picker
 * list and the form chips (Alice always blue, Bob always teal), which reads as
 * intentional rather than random.
 *
 * Keyed on [AddressNormalizer.canonical] (lowercased) so `mailto:` prefix and
 * case never shift the colour. Uses the absolute value of [String.hashCode]
 * guarded against the `abs(Int.MIN_VALUE)` overflow.
 */
fun avatarColorIndex(address: String, paletteSize: Int): Int {
    require(paletteSize > 0) { "paletteSize must be positive" }
    val key = AddressNormalizer.canonical(address).lowercase()
    val hash = key.hashCode()
    val nonNegative = if (hash == Int.MIN_VALUE) 0 else (hash and Int.MAX_VALUE)
    return nonNegative % paletteSize
}

/**
 * Curated avatar palette — distinct, accessible hues that read on both light
 * and dark surfaces. Order is stable so [avatarColorIndex] maps consistently.
 */
private val AVATAR_PALETTE: List<Color> = listOf(
    Color(0xFF1A73C2), // blue
    Color(0xFF2E8B57), // green
    Color(0xFFB8860B), // amber
    Color(0xFF8E44AD), // purple
    Color(0xFFC0392B), // red
    Color(0xFF16808A), // teal
)

/** The deterministic avatar background colour for [address]. */
@Composable
@ReadOnlyComposable
fun avatarColorFor(address: String): Color =
    AVATAR_PALETTE[avatarColorIndex(address, AVATAR_PALETTE.size)]
