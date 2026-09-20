package org.onekash.kashcal.ui.components.share

import androidx.compose.runtime.Composable
import org.onekash.kashcal.domain.share.DateChipText
import org.onekash.kashcal.domain.share.ShareCardStyle
import org.onekash.kashcal.domain.share.StripePosition

/**
 * Shared synthetic share-card fixtures. Both the behavioral test
 * ([ShareCardComposableTest]) and the visual goldens ([ShareCardScreenshotTest])
 * render these same inputs, so a golden can't silently drift from what the
 * behavioral assertions cover. Each fixture is the bare `ShareCardComposable`
 * call — the caller supplies its own theme wrapper.
 */
internal object ShareCardFixtures {

    val labels12h = listOf("12a", "6a", "12p", "6p", "12a")

    @Composable
    fun StandardTimed() {
        ShareCardComposable(
            title = "Brunch at Sam's",
            location = "Sam's Café · Mission St",
            timeRangeText = "11:30 AM – 1:00 PM",
            style = ShareCardStyle.Standard,
            dateChip = DateChipText.Single("31", "MAY", "SUN"),
            stripe = StripePosition(0.479f, 0.0625f, visible = true),
            stripeLabels = labels12h,
            isAllDay = false,
            isMultiDay = false,
            multiDayRangeText = null,
            attribution = "Made with KashCal",
        )
    }

    @Composable
    fun AllDay() {
        ShareCardComposable(
            title = "Vacation",
            location = null,
            timeRangeText = "All day",
            style = ShareCardStyle.Standard,
            dateChip = DateChipText.Single("31", "MAY", "SUN"),
            stripe = StripePosition.Hidden,
            stripeLabels = labels12h,
            isAllDay = true,
            isMultiDay = false,
            multiDayRangeText = null,
            attribution = "Made with KashCal",
        )
    }

    @Composable
    fun Celebration() {
        ShareCardComposable(
            title = "🎂 Maya turns 5",
            location = null,
            timeRangeText = "2:00 – 5:00 PM",
            style = ShareCardStyle.Celebration,
            dateChip = DateChipText.Single("14", "JUN", "SAT"),
            stripe = StripePosition(0.583f, 0.125f, visible = true),
            stripeLabels = labels12h,
            isAllDay = false,
            isMultiDay = false,
            multiDayRangeText = null,
            attribution = "Made with KashCal",
        )
    }
}
