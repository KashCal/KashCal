package org.onekash.kashcal.ui.components.attendees

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import org.onekash.kashcal.util.AddressNormalizer

/**
 * Returns a stable avatar palette index for [address], so a person keeps one colour across the
 * picker list and the form chips.
 *
 * Keyed on the lowercased [AddressNormalizer.canonical] form, so a `mailto:` prefix or case
 * never shifts the colour. The [String.hashCode] is masked to a non-negative value
 * (`Int.MIN_VALUE` becomes 0) before the modulo.
 */
fun avatarColorIndex(address: String, paletteSize: Int): Int {
    require(paletteSize > 0) { "paletteSize must be positive" }
    val key = AddressNormalizer.canonical(address).lowercase()
    val hash = key.hashCode()
    val nonNegative = if (hash == Int.MIN_VALUE) 0 else (hash and Int.MAX_VALUE)
    return nonNegative % paletteSize
}

/**
 * Distinct, accessible hues that read on light and dark surfaces. Reordering it changes every
 * person's colour, since [avatarColorIndex] indexes into it.
 */
private val AVATAR_PALETTE: List<Color> = listOf(
    Color(0xFF1A73C2), // blue
    Color(0xFF2E8B57), // green
    Color(0xFFB8860B), // amber
    Color(0xFF8E44AD), // purple
    Color(0xFFC0392B), // red
    Color(0xFF16808A), // teal
)

/** Returns the avatar background colour for [address]. */
@Composable
@ReadOnlyComposable
fun avatarColorFor(address: String): Color =
    AVATAR_PALETTE[avatarColorIndex(address, AVATAR_PALETTE.size)]
