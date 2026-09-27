package org.onekash.kashcal.ui.shared

import androidx.compose.ui.graphics.Color

/**
 * Returns the WCAG 2.x relative luminance of [color], from 0.0 (black) to 1.0 (white).
 *
 * Expands each sRGB channel's gamma, then applies the Rec. 709 weights, per the WCAG 2.1
 * definition of relative luminance. The cheaper Rec. 601 luma average overstates contrast for
 * saturated mid-tone colors, so contrast math must not use it.
 */
fun relativeLuminance(color: Color): Double {
    fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
}

/**
 * Returns the WCAG 2.x contrast ratio of [a] and [b], from 1.0 (identical) to 21.0 (black and
 * white); symmetric in its arguments. WCAG AA requires at least 4.5:1 for normal text and 3:1 for
 * large text and UI components.
 *
 * Alpha is ignored and both colors are treated as opaque, so a caller compositing over a known
 * surface should blend first.
 */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Returns black or white, whichever has the higher WCAG contrast ratio on [background]. For any
 * opaque background the winner is at least ~4.58:1, so it always clears WCAG AA for normal text.
 */
fun contrastForegroundOn(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) {
        Color.White
    } else {
        Color.Black
    }
