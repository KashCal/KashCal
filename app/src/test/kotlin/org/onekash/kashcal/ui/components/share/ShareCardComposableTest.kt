package org.onekash.kashcal.ui.components.share

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.share.DateChipText
import org.onekash.kashcal.domain.share.ShareCardStyle
import org.onekash.kashcal.domain.share.StripePosition
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [ShareCardComposable], under Robolectric in the unit-test source set (no
 * emulator). Visual fidelity (typography sizes, gradient brushes) is checked by hand in @Preview
 * and by [ShareCardScreenshotTest]. These tests check that:
 *  - the title, date chip, location, time text and attribution render;
 *  - the caller's time text shows as given ("All day", "Sun – Wed");
 *  - the day stripe renders only when [StripePosition.visible] is true; the card doesn't read
 *    `isAllDay` or `isMultiDay`, so the all-day and multi-day cases pass a hidden stripe;
 *  - the confetti overlay appears for Celebration and not for Standard;
 *  - a null title doesn't crash;
 *  - [normalizeShareAddress] collapses whitespace runs and trims, and a multi-line location
 *    renders on one line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class ShareCardComposableTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val regularTimedDateChip = DateChipText.Single("31", "MAY", "SUN")
    private val regularTimedStripe = StripePosition(0.479f, 0.0625f, visible = true)
    private val labels12h = listOf("12a", "6a", "12p", "6p", "12a")

    @Test
    fun renders_title_numeral_month_dow_for_regular_timed_event() {
        composeTestRule.setContent {
            MaterialTheme {
                ShareCardFixtures.StandardTimed()
            }
        }

        composeTestRule.onNodeWithText("Brunch at Sam's").assertIsDisplayed()
        composeTestRule.onNodeWithText("31").assertIsDisplayed()
        composeTestRule.onNodeWithText("MAY").assertIsDisplayed()
        composeTestRule.onNodeWithText("SUN").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sam's Café · Mission St").assertIsDisplayed()
        composeTestRule.onNodeWithText("11:30 AM – 1:00 PM").assertIsDisplayed()
        composeTestRule.onNodeWithText("Made with KashCal").assertIsDisplayed()
    }

    @Test
    fun all_day_event_shows_All_day_label_and_hides_stripe_and_time_line() {
        composeTestRule.setContent {
            MaterialTheme {
                ShareCardFixtures.AllDay()
            }
        }
        composeTestRule.onNodeWithText("All day").assertIsDisplayed()
        composeTestRule.onNodeWithTag(ShareCardTags.TAG_DAY_STRIPE)
            .assertDoesNotExist()
    }

    @Test
    fun multi_day_event_shows_range_chip_and_dow_subtitle() {
        composeTestRule.setContent {
            MaterialTheme {
                ShareCardComposable(
                    title = "Conference",
                    location = "Moscone Center",
                    // The caller (MainActivity) composes "Sun – Wed" for a multi-day timed event
                    // and appends " · All day" for a multi-day all-day one. The chip's range
                    // carries the calendar dates.
                    timeRangeText = "Sun – Wed",
                    style = ShareCardStyle.Standard,
                    dateChip = DateChipText.Range("MAY 31 – JUN 3"),
                    stripe = StripePosition.Hidden,
                    stripeLabels = labels12h,
                    isAllDay = false,
                    isMultiDay = false,
                    multiDayRangeText = null,
                    attribution = "Made with KashCal",
                )
            }
        }
        composeTestRule.onNodeWithText("MAY 31 – JUN 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sun – Wed").assertIsDisplayed()
        composeTestRule.onNodeWithTag(ShareCardTags.TAG_DAY_STRIPE)
            .assertDoesNotExist()
    }

    @Test
    fun celebration_renders_confetti_overlay_regular_does_not() {
        composeTestRule.setContent {
            MaterialTheme {
                ShareCardFixtures.Celebration()
            }
        }
        composeTestRule.onNodeWithTag(ShareCardTags.TAG_CONFETTI).assertIsDisplayed()
    }

    @Test
    fun regular_does_not_render_confetti_overlay() {
        composeTestRule.setContent {
            MaterialTheme {
                ShareCardComposable(
                    title = "Brunch at Sam's",
                    location = null,
                    timeRangeText = "11:30 AM – 1:00 PM",
                    style = ShareCardStyle.Standard,
                    dateChip = regularTimedDateChip,
                    stripe = regularTimedStripe,
                    stripeLabels = labels12h,
                    isAllDay = false,
                    isMultiDay = false,
                    multiDayRangeText = null,
                    attribution = "Made with KashCal",
                )
            }
        }
        composeTestRule.onAllNodesWithTag(ShareCardTags.TAG_CONFETTI).assertCountEquals(0)
    }

    @Test
    fun null_title_does_not_crash() {
        composeTestRule.setContent {
            MaterialTheme {
                ShareCardComposable(
                    title = null,
                    location = null,
                    timeRangeText = "11:30 AM – 1:00 PM",
                    style = ShareCardStyle.Standard,
                    dateChip = regularTimedDateChip,
                    stripe = regularTimedStripe,
                    stripeLabels = labels12h,
                    isAllDay = false,
                    isMultiDay = false,
                    multiDayRangeText = null,
                    attribution = "Made with KashCal",
                )
            }
        }
        // Composition doesn't crash, and the numeral still renders.
        composeTestRule.onNodeWithText("31").assertIsDisplayed()
    }

    @Test
    fun normalizeShareAddress_collapses_embedded_newlines_to_single_spaces() {
        val raw = "Sam's Café\n123 Mission St\nSan Francisco, CA 94110"
        assertEquals(
            "Sam's Café 123 Mission St San Francisco, CA 94110",
            normalizeShareAddress(raw),
        )
    }

    @Test
    fun normalizeShareAddress_collapses_tab_and_multi_space_runs() {
        val raw = "Suite 200\t\t Main Hall   Building   B"
        assertEquals("Suite 200 Main Hall Building B", normalizeShareAddress(raw))
    }

    @Test
    fun normalizeShareAddress_trims_leading_and_trailing_whitespace() {
        assertEquals("Moscone Center", normalizeShareAddress("  \n Moscone Center \n "))
    }

    @Test
    fun normalizeShareAddress_leaves_clean_single_line_unchanged() {
        val clean = "Sam's Café, 123 Mission St, San Francisco, CA 94110"
        assertEquals(clean, normalizeShareAddress(clean))
    }

    @Test
    fun multi_line_location_renders_as_single_collapsed_line() {
        composeTestRule.setContent {
            MaterialTheme {
                ShareCardComposable(
                    title = "Brunch at Sam's",
                    location = "Sam's Café\n123 Mission St\nSan Francisco, CA 94110",
                    timeRangeText = "11:30 AM – 1:00 PM",
                    style = ShareCardStyle.Standard,
                    dateChip = regularTimedDateChip,
                    stripe = regularTimedStripe,
                    stripeLabels = labels12h,
                    isAllDay = false,
                    isMultiDay = false,
                    multiDayRangeText = null,
                    attribution = "Made with KashCal",
                )
            }
        }
        composeTestRule
            .onNodeWithText("Sam's Café 123 Mission St San Francisco, CA 94110")
            .assertIsDisplayed()
    }

    @Test
    fun stripe_visible_when_position_is_visible() {
        composeTestRule.setContent {
            MaterialTheme {
                ShareCardComposable(
                    title = "Brunch at Sam's",
                    location = null,
                    timeRangeText = "11:30 AM – 1:00 PM",
                    style = ShareCardStyle.Standard,
                    dateChip = regularTimedDateChip,
                    stripe = regularTimedStripe,
                    stripeLabels = labels12h,
                    isAllDay = false,
                    isMultiDay = false,
                    multiDayRangeText = null,
                    attribution = "Made with KashCal",
                )
            }
        }
        composeTestRule.onNodeWithTag(ShareCardTags.TAG_DAY_STRIPE).assertIsDisplayed()
    }

}
