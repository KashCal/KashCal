package org.onekash.kashcal.sync.parser

import android.graphics.Color
import org.onekash.kashcal.ui.shared.EventColorPalette

/**
 * Parses RFC 7986 COLOR strings from CalDAV servers to Android ARGB.
 *
 * Returns null on anything unparseable (preserves local on the refresh path).
 * Handles `#RRGGBB`, `#RRGGBBAA` (iCloud), `#RGB`, and CSS3 named colors.
 */
object ServerColorParser {

    fun parseCaldavColorToArgb(color: String?): Int? {
        if (color.isNullOrBlank()) return null
        val trimmed = color.trim()

        EventColorPalette.hexForName(trimmed)?.let { return it }

        if (!trimmed.startsWith("#")) return null

        val expanded = when (trimmed.length) {
            4 -> {
                val r = trimmed[1]; val g = trimmed[2]; val b = trimmed[3]
                "#$r$r$g$g$b$b"
            }
            7 -> trimmed
            9 -> {
                val rgb = trimmed.substring(1, 7)
                val alpha = trimmed.substring(7, 9)
                "#$alpha$rgb"
            }
            else -> return null
        }

        return try {
            Color.parseColor(expanded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
