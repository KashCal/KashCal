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
 * 15 CSS3 named colors curated to mirror Google Calendar's hue spread while
 * staying within RFC 7986 §5.9 which restricts COLOR to case-insensitive CSS3
 * names. Canonical storage stays as `Int?` ARGB in `Event.color`; CSS3 names
 * are derived at boundaries (CalDAV emit/parse, UI label) via [nameForHex] and
 * [hexForName]. Non-palette hex values are preserved on edit and round-trip
 * through CalDAV as `#RRGGBB` (pragmatic fallback — spec prefers names).
 */
object EventColorPalette {

    val entries: List<PaletteEntry> = listOf(
        PaletteEntry("tomato", 0xFFFF6347.toInt(), R.string.color_tomato),
        PaletteEntry("orangered", 0xFFFF4500.toInt(), R.string.color_orangered),
        PaletteEntry("darkorange", 0xFFFF8C00.toInt(), R.string.color_darkorange),
        PaletteEntry("gold", 0xFFFFD700.toInt(), R.string.color_gold),
        PaletteEntry("yellowgreen", 0xFF9ACD32.toInt(), R.string.color_yellowgreen),
        PaletteEntry("limegreen", 0xFF32CD32.toInt(), R.string.color_limegreen),
        PaletteEntry("mediumseagreen", 0xFF3CB371.toInt(), R.string.color_mediumseagreen),
        PaletteEntry("seagreen", 0xFF2E8B57.toInt(), R.string.color_seagreen),
        PaletteEntry("lightseagreen", 0xFF20B2AA.toInt(), R.string.color_lightseagreen),
        PaletteEntry("dodgerblue", 0xFF1E90FF.toInt(), R.string.color_dodgerblue),
        PaletteEntry("royalblue", 0xFF4169E1.toInt(), R.string.color_royalblue),
        PaletteEntry("mediumslateblue", 0xFF7B68EE.toInt(), R.string.color_mediumslateblue),
        PaletteEntry("mediumorchid", 0xFFBA55D3.toInt(), R.string.color_mediumorchid),
        PaletteEntry("hotpink", 0xFFFF69B4.toInt(), R.string.color_hotpink),
        PaletteEntry("dimgray", 0xFF696969.toInt(), R.string.color_dimgray),
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
