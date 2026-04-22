package org.onekash.kashcal.ui.shared

import androidx.compose.ui.graphics.Color

/**
 * Pick a foreground color (black or white) with adequate contrast against the
 * given background, using the standard luma formula (Rec. 601 weights).
 */
fun contrastForegroundOn(background: Color): Color {
    val luminance = 0.299f * background.red +
        0.587f * background.green +
        0.114f * background.blue
    return if (luminance > 0.5f) Color.Black else Color.White
}
