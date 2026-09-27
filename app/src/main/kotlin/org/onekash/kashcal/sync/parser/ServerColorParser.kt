package org.onekash.kashcal.sync.parser

import android.graphics.Color
import org.onekash.kashcal.ui.shared.EventColorPalette

/**
 * Parses a server's `calendar-color` value to Android ARGB.
 *
 * Accepts `#RRGGBB`, `#RRGGBBAA` (iCloud), `#RGB` and the CSS3 color names RFC 7986 COLOR
 * uses. Returns null for anything else; on metadata refresh a null keeps the local color.
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
