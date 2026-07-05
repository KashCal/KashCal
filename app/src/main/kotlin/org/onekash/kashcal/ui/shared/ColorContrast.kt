package org.onekash.kashcal.ui.shared

import androidx.compose.ui.graphics.Color

/**
 * WCAG 2.x relative luminance of a color (0.0 = black, 1.0 = white).
 *
 * Applies the sRGB gamma-expansion to each channel before applying the
 * Rec. 709 luminance weights, per WCAG 2.1 §"relative luminance". This is the
 * perceptual luminance used by contrast-ratio math — not the cheap Rec. 601
 * luma average, which overstates contrast for saturated mid-tone colors.
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
 * WCAG 2.x contrast ratio between two colors, in the range 1.0 (identical) to
 * 21.0 (black vs white). Symmetric in its arguments. WCAG AA requires >= 4.5:1
 * for normal text and >= 3:1 for large text / UI components.
 *
 * Alpha is ignored: colors are treated as opaque. Callers that composite over a
 * known surface should pre-blend before measuring.
 */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Pick a foreground color (black or white) with the higher WCAG contrast ratio
 * against the given background. For any solid color the winning choice is
 * provably >= ~4.58:1, so this always clears WCAG AA for normal text.
 */
fun contrastForegroundOn(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) {
        Color.White
    } else {
        Color.Black
    }
