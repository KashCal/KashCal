package org.onekash.kashcal.domain.share

/**
 * Visual variant for [ShareCardComposable]. Auto-picked from event signals
 * via [ShareCardStylePicker]; user can override via the chip in
 * [org.onekash.kashcal.ui.components.share.ShareCardSheet].
 */
sealed class ShareCardStyle {
    /** Calm, default presentation. Yellow time-bar, brand-yellow month label. */
    data object Standard : ShareCardStyle()

    /** Festive: confetti scatter, pink time-bar, pink month label. */
    data object Celebration : ShareCardStyle()
}
