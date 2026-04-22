package org.onekash.kashcal.ui.shared

import androidx.annotation.StringRes
import org.onekash.kashcal.R

data class PaletteEntry(
    val name: String,
    val argb: Int,
    @StringRes val labelRes: Int
)

/**
 * RFC 7986 §5.9 compliant event color palette.
 *
 * Canonical storage stays as `Int?` ARGB in `Event.color`. CSS3 names are
 * derived at boundaries (CalDAV emit/parse, UI label) via [nameForHex] and
 * [hexForName]. Non-palette hex values are preserved on edit and round-trip
 * through CalDAV as `#RRGGBB`.
 */
object EventColorPalette {

    val entries: List<PaletteEntry> = listOf(
        PaletteEntry("tomato", 0xFFFF6347.toInt(), R.string.color_tomato),
        PaletteEntry("darkorange", 0xFFFF8C00.toInt(), R.string.color_darkorange),
        PaletteEntry("gold", 0xFFFFD700.toInt(), R.string.color_gold),
        PaletteEntry("yellowgreen", 0xFF9ACD32.toInt(), R.string.color_yellowgreen),
        PaletteEntry("mediumseagreen", 0xFF3CB371.toInt(), R.string.color_mediumseagreen),
        PaletteEntry("teal", 0xFF008080.toInt(), R.string.color_teal),
        PaletteEntry("steelblue", 0xFF4682B4.toInt(), R.string.color_steelblue),
        PaletteEntry("slateblue", 0xFF6A5ACD.toInt(), R.string.color_slateblue),
        PaletteEntry("mediumorchid", 0xFFBA55D3.toInt(), R.string.color_mediumorchid),
        PaletteEntry("hotpink", 0xFFFF69B4.toInt(), R.string.color_hotpink),
        PaletteEntry("slategray", 0xFF708090.toInt(), R.string.color_slategray),
    )

    private val nameToArgb: Map<String, Int> = entries.associate { it.name to it.argb }
    private val argbToName: Map<Int, String> = entries.associate { it.argb to it.name }
    private val argbToLabelRes: Map<Int, Int> = entries.associate { it.argb to it.labelRes }

    fun hexForName(name: String): Int? = nameToArgb[name.lowercase()]

    fun nameForHex(argb: Int): String? = argbToName[argb]

    @StringRes
    fun stringResIdForColor(argb: Int?): Int {
        if (argb == null) return R.string.label_calendar_default
        return argbToLabelRes[argb] ?: R.string.label_custom
    }
}
