package org.onekash.kashcal.ui.appicon

import androidx.annotation.DrawableRes
import org.onekash.kashcal.R

/**
 * Maps a day of the month to its launcher icon and foreground layer.
 *
 * Each icon is the default card artwork with the day's number on the front card, generated as
 * vector paths so no font is needed at runtime. Every icon's themed layer is the undated
 * [R.drawable.ic_launcher_monochrome] glyph.
 */
object DayIcons {

    private val MIPMAPS = listOf(
        R.mipmap.ic_launcher_day_1,
        R.mipmap.ic_launcher_day_2,
        R.mipmap.ic_launcher_day_3,
        R.mipmap.ic_launcher_day_4,
        R.mipmap.ic_launcher_day_5,
        R.mipmap.ic_launcher_day_6,
        R.mipmap.ic_launcher_day_7,
        R.mipmap.ic_launcher_day_8,
        R.mipmap.ic_launcher_day_9,
        R.mipmap.ic_launcher_day_10,
        R.mipmap.ic_launcher_day_11,
        R.mipmap.ic_launcher_day_12,
        R.mipmap.ic_launcher_day_13,
        R.mipmap.ic_launcher_day_14,
        R.mipmap.ic_launcher_day_15,
        R.mipmap.ic_launcher_day_16,
        R.mipmap.ic_launcher_day_17,
        R.mipmap.ic_launcher_day_18,
        R.mipmap.ic_launcher_day_19,
        R.mipmap.ic_launcher_day_20,
        R.mipmap.ic_launcher_day_21,
        R.mipmap.ic_launcher_day_22,
        R.mipmap.ic_launcher_day_23,
        R.mipmap.ic_launcher_day_24,
        R.mipmap.ic_launcher_day_25,
        R.mipmap.ic_launcher_day_26,
        R.mipmap.ic_launcher_day_27,
        R.mipmap.ic_launcher_day_28,
        R.mipmap.ic_launcher_day_29,
        R.mipmap.ic_launcher_day_30,
        R.mipmap.ic_launcher_day_31,
    )

    private val FOREGROUNDS = listOf(
        R.drawable.ic_launcher_day_1_foreground,
        R.drawable.ic_launcher_day_2_foreground,
        R.drawable.ic_launcher_day_3_foreground,
        R.drawable.ic_launcher_day_4_foreground,
        R.drawable.ic_launcher_day_5_foreground,
        R.drawable.ic_launcher_day_6_foreground,
        R.drawable.ic_launcher_day_7_foreground,
        R.drawable.ic_launcher_day_8_foreground,
        R.drawable.ic_launcher_day_9_foreground,
        R.drawable.ic_launcher_day_10_foreground,
        R.drawable.ic_launcher_day_11_foreground,
        R.drawable.ic_launcher_day_12_foreground,
        R.drawable.ic_launcher_day_13_foreground,
        R.drawable.ic_launcher_day_14_foreground,
        R.drawable.ic_launcher_day_15_foreground,
        R.drawable.ic_launcher_day_16_foreground,
        R.drawable.ic_launcher_day_17_foreground,
        R.drawable.ic_launcher_day_18_foreground,
        R.drawable.ic_launcher_day_19_foreground,
        R.drawable.ic_launcher_day_20_foreground,
        R.drawable.ic_launcher_day_21_foreground,
        R.drawable.ic_launcher_day_22_foreground,
        R.drawable.ic_launcher_day_23_foreground,
        R.drawable.ic_launcher_day_24_foreground,
        R.drawable.ic_launcher_day_25_foreground,
        R.drawable.ic_launcher_day_26_foreground,
        R.drawable.ic_launcher_day_27_foreground,
        R.drawable.ic_launcher_day_28_foreground,
        R.drawable.ic_launcher_day_29_foreground,
        R.drawable.ic_launcher_day_30_foreground,
        R.drawable.ic_launcher_day_31_foreground,
    )

    /** Returns the adaptive launcher icon for [day]; throws for a day outside 1..31. */
    @DrawableRes
    fun mipmapFor(day: Int): Int = MIPMAPS[index(day)]

    /** Returns the icon's foreground layer for [day], for previews; throws outside 1..31. */
    @DrawableRes
    fun foregroundFor(day: Int): Int = FOREGROUNDS[index(day)]

    private fun index(day: Int): Int {
        require(day in 1..31) { "day of month out of range: $day" }
        return day - 1
    }
}
