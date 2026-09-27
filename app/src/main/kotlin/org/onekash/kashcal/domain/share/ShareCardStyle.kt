package org.onekash.kashcal.domain.share

/**
 * Visual variant of the share card. [ShareCardStylePicker] picks one from the event title; the
 * user can switch it with the chip in [org.onekash.kashcal.ui.components.share.ShareCardSheet].
 */
sealed class ShareCardStyle {
    /** Default presentation: brand-yellow time bar and month label. */
    data object Standard : ShareCardStyle()

    /** Festive: confetti scatter and glow, brighter gold time bar and month label. */
    data object Celebration : ShareCardStyle()
}
