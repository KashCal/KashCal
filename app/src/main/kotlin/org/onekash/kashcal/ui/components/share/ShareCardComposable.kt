package org.onekash.kashcal.ui.components.share

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import org.onekash.kashcal.domain.share.DateChipText
import org.onekash.kashcal.domain.share.ShareCardStyle
import org.onekash.kashcal.domain.share.StripePosition

/**
 * Lays out the share card: pure visual, no side effects, no remembered state. It measures
 * 420 × 525 dp at the host's density; [ShareCardSheet] captures it and
 * [org.onekash.kashcal.domain.share.ShareCardRenderer] scales the capture to a 1080 × 1350 px
 * PNG.
 *
 * [isAllDay], [isMultiDay], [multiDayRangeText] and [allDayLabel] aren't read: the caller
 * composes [timeRangeText] and [dateChip], and [stripe] carries the stripe's visibility.
 */
@Composable
fun ShareCardComposable(
    title: String?,
    location: String?,
    timeRangeText: String?,
    style: ShareCardStyle,
    dateChip: DateChipText,
    stripe: StripePosition,
    stripeLabels: List<String>,
    isAllDay: Boolean,
    isMultiDay: Boolean,
    multiDayRangeText: String?,
    attribution: String,
    allDayLabel: String = "All day",
    modifier: Modifier = Modifier,
) {
    // Celebration uses a brighter gold. Both variants keep accents in the yellow family for
    // contrast on the teal background: pink fails WCAG AA on teal for both the time bar and
    // the small month label. Celebration also adds the background scatter, the glyph and a
    // warmer title gradient below.
    val isCelebration = style == ShareCardStyle.Celebration
    val timeAccent = if (isCelebration) Palette.brandYellowBright else Palette.brandYellow
    val monthAccent = if (isCelebration) Palette.brandYellowBright else Palette.brandYellow
    val titleGradient = if (isCelebration) {
        Brush.verticalGradient(listOf(Palette.brandYellowPale, Palette.brandYellow))
    } else {
        Brush.verticalGradient(listOf(Palette.brandCream, Palette.brandWarmCream))
    }

    // The capture region is 420 × 525 dp, the 4:5 ratio of the 1080 × 1350 PNG. The renderer's
    // scaleToShareCardOutput rescales any capture to that size, so the region's size doesn't
    // change the PNG's.
    //
    // It must hold the rotated silhouettes' corners. A 340 dp wide silhouette rotated 4 degrees
    // has a half-width of 170·cos(4°) + 212.5·sin(4°) ≈ 184.4 dp, and 208.7 dp with its
    // 24.3 dp translation, so the 210 dp half-width leaves a 1.3 dp margin, and a 400 dp wide
    // region would clip the corners.
    //
    //   - capture region:  420 × 525 (outer Box)
    //   - silhouettes:     340 × 425, centered, rotated ±4° and translated ±24.3 dp
    //                      horizontally, corners visible inside the capture
    //   - main card:       336 × 420, centered, on top
    //
    // requiredSize, not size: ShareCardSheet hosts the card in a Box with .height(300.dp)
    // and .padding(horizontal = 36.dp), which is 288 × 300 dp on a 360 dp wide phone. Plain
    // size would coerce the outer Box, silhouettes and main card to 288 dp, and the
    // GraphicsLayer would record a 288 × 300 capture without the silhouettes. The sheet's
    // .scale(240 / 420) on the capture host shrinks the on-screen preview.
    Box(
        modifier = modifier.requiredSize(width = 420.dp, height = 525.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Stacked-card silhouettes peek past the main card's rounded corners. They are smaller
        // than the capture region so the GraphicsLayer doesn't clip their rotated corners.
        Box(
            modifier = Modifier
                .size(width = 340.dp, height = 425.dp)
                .graphicsLayer {
                    rotationZ = -4f
                    translationX = -size.width / 14f
                    translationY = size.height / 30f
                    alpha = 0.6f
                }
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.brandBlue),
        )
        Box(
            modifier = Modifier
                .size(width = 340.dp, height = 425.dp)
                .graphicsLayer {
                    rotationZ = 4f
                    translationX = size.width / 14f
                    translationY = -size.height / 30f
                    alpha = 0.6f
                }
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.brandYellow),
        )

        // The main card, slightly smaller than the silhouettes so their corners show past it.
        Box(
            modifier = Modifier
                .size(width = 336.dp, height = 420.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Palette.brandTeal,
                            0.55f to Palette.brandTealMid,
                            1.0f to Palette.brandTealDark,
                        ),
                    ),
                ),
        ) {
            // Celebration only: a gold glow behind the title and a fixed scatter of stars,
            // streamers, dots and sparkles, drawn before the content Column so text sits on top.
            if (isCelebration) {
                CelebrationGlow(modifier = Modifier.fillMaxSize())
                CelebrationScatter(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ShareCardTags.TAG_CONFETTI),
                )
            }

            // Three zones: the header at the top so the date chip never moves, the attribution
            // at the bottom so it never gets clipped, and the body (title, time, location,
            // stripe) between them, however dense it is.
            //
            // The header is about 50dp: the date row (~36dp at fontScale=1), a 12dp spacer and
            // a 1dp divider. With the column's 24dp top padding the divider sits about 74dp
            // from the card's top.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PaddingValues(start = 26.dp, top = 24.dp, end = 26.dp, bottom = 22.dp)),
            ) {
                // ---- Header: date chip, dot, divider ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DateChip(text = dateChip, monthColor = monthAccent)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isCelebration) {
                            // The party glyph next to the pink dot is the celebration signal
                            // that stays readable in a chat thumbnail.
                            Text(
                                text = "🎉",
                                style = TextStyle(fontSize = 14.sp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Palette.brandPink),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Palette.brandCream.copy(alpha = 0.15f),
                )

                // ---- Body ----
                // Clustered by meaning:
                //   - title cluster (what and when): the title with the time text below it;
                //   - flexible space;
                //   - context cluster (where): location and day stripe, above the attribution.
                //
                // Without a location or stripe the title cluster centers vertically. Keeping the
                // time with the title avoids a title stranded at the top over a 100+dp gap.

                // The caller composes the time text, all-day and multi-day wording included.
                val timeText = timeRangeText
                val hasContextCluster = !location.isNullOrEmpty() || stripe.visible

                // Without a context cluster, center the title cluster; otherwise all the slack
                // would fall between the title and the attribution.
                if (!hasContextCluster) {
                    Spacer(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.height(18.dp))
                }

                // ---- Title cluster ----
                val titleMaxLines = if (location.isNullOrEmpty()) 3 else 2
                if (!title.isNullOrEmpty()) {
                    Text(
                        text = title,
                        style = TextStyle(
                            brush = titleGradient,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 36.sp,
                            letterSpacing = (-0.5).sp,
                        ),
                        maxLines = titleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (timeText != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = timeText,
                        style = TextStyle(
                            color = Palette.brandCream.copy(alpha = 0.85f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.2.sp,
                        ),
                    )
                }

                // Space between the clusters.
                Spacer(Modifier.weight(1f))

                // ---- Context cluster ----
                if (!location.isNullOrEmpty()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Palette.brandCream.copy(alpha = 0.85f),
                            modifier = Modifier
                                .size(14.dp)
                                .padding(top = 2.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            // Locations arrive multi-part: the location picker joins name
                            // and address with commas, and ICS import turns escaped \N into
                            // newlines. One logical line lets the 2-line budget hold address
                            // text and wrap on commas; hard breaks would use it up and
                            // truncate mid-address.
                            text = normalizeShareAddress(location),
                            style = TextStyle(
                                color = Palette.brandCream,
                                fontSize = 14.sp,
                                lineHeight = 17.sp,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (stripe.visible) {
                    if (!location.isNullOrEmpty()) Spacer(Modifier.height(12.dp))
                    DayStripe(
                        stripe = stripe,
                        accent = timeAccent,
                        labels = stripeLabels,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ShareCardTags.TAG_DAY_STRIPE),
                    )
                }

                // ---- Attribution, at the card's bottom ----
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Text(
                        text = attribution,
                        style = TextStyle(
                            color = Palette.brandCream.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.4.sp,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Flattens a location into one line for the share card: every run of whitespace becomes one
 * space and the ends are trimmed, so a clean single line comes back unchanged. The card's
 * 2-line ellipsis still handles addresses that stay long.
 *
 * The class adds the non-breaking spaces that `\s` skips (U+00A0, figure space U+2007, narrow
 * NBSP U+202F); geocoded addresses, especially European ones, put them between street number
 * and name.
 */
internal fun normalizeShareAddress(raw: String): String =
    raw.replace(Regex("[\\s\\u00A0\\u2007\\u202F]+"), " ").trim()

@Composable
private fun DateChip(text: DateChipText, monthColor: Color) {
    when (text) {
        is DateChipText.Single -> DateChipSingle(text, monthColor)
        is DateChipText.Range -> DateChipRange(text, monthColor)
    }
}

@Composable
private fun DateChipSingle(text: DateChipText.Single, monthColor: Color) {
    // Sized so the row stays about 36dp tall at fontScale=1: a 32sp numeral with 32sp line
    // height beside a 2-line month and weekday column that fits within it. A 38sp numeral
    // silently pushes the row past 50dp on some devices and the attribution off the card.
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = text.numeral,
            style = TextStyle(
                color = Palette.brandCream,
                fontSize = 32.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 32.sp,
                letterSpacing = (-1).sp,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = text.monthLabel,
                style = TextStyle(
                    color = monthColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                ),
            )
            Text(
                text = text.dayOfWeekLabel,
                style = TextStyle(
                    color = Palette.brandCream.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.0.sp,
                ),
            )
        }
    }
}

/**
 * Shows the multi-day chip, one label such as "MAY 31 – JUN 3" or "MAY 5 – 8". Bold, 18sp and
 * letter-spaced, it weighs about as much as the single-day chip's 32sp numeral and still fits
 * beside the pink dot.
 */
@Composable
private fun DateChipRange(text: DateChipText.Range, monthColor: Color) {
    Text(
        text = text.label,
        style = TextStyle(
            color = monthColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            lineHeight = 22.sp,
        ),
    )
}

@Composable
private fun DayStripe(
    stripe: StripePosition,
    accent: Color,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp), // Includes the glow margin.
        ) {
            val barTop = 1.dp.toPx()
            val barHeight = 5.dp.toPx()
            val w = size.width

            // Background bar.
            drawRect(
                color = Palette.brandCream.copy(alpha = 0.15f),
                topLeft = Offset(0f, barTop),
                size = androidx.compose.ui.geometry.Size(w, barHeight),
            )

            // Hour ticks at 6 AM, noon and 6 PM.
            val tickColor = Palette.brandCream.copy(alpha = 0.6f)
            val tickHeight = 9.dp.toPx()
            for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                drawRect(
                    color = tickColor,
                    topLeft = Offset(w * frac, 0f),
                    size = androidx.compose.ui.geometry.Size(1.dp.toPx(), tickHeight),
                )
            }

            // The event's range, with a soft glow.
            val rangeStart = w * stripe.startFraction
            val rangeWidth = (w * stripe.widthFraction).coerceAtLeast(2.dp.toPx())
            // Glow.
            drawRect(
                color = accent.copy(alpha = 0.3f),
                topLeft = Offset(rangeStart - 4.dp.toPx(), barTop - 2.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(rangeWidth + 8.dp.toPx(), barHeight + 4.dp.toPx()),
            )
            // Solid range.
            drawRect(
                color = accent,
                topLeft = Offset(rangeStart, barTop),
                size = androidx.compose.ui.geometry.Size(rangeWidth, barHeight),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (label in labels) {
                Text(
                    text = label,
                    style = TextStyle(
                        color = Palette.brandCream.copy(alpha = 0.6f),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.6.sp,
                    ),
                )
            }
        }
    }
}

/**
 * Draws Celebration's soft radial glow, centered 38% from the top so it sits behind the title,
 * warming the card without changing its background.
 */
@Composable
private fun CelebrationGlow(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Palette.brandYellowBright.copy(alpha = 0.14f),
                    Color.Transparent,
                ),
                center = Offset(w * 0.5f, h * 0.38f),
                radius = w * 0.55f,
            ),
            radius = w * 0.55f,
            center = Offset(w * 0.5f, h * 0.38f),
        )
    }
}

/**
 * Draws Celebration's background scatter at fixed positions, so every render is the same and
 * tests need no random seed. Bottom to top:
 *  - stars: cream ★
 *  - streamers: 1.5dp × 16dp rotated rects in pink or yellow
 *  - confetti dots: 3-5dp circles in pink, yellow or cream
 *  - sparkles: gold ✦ at 7, 10 or 16sp with a drop shadow
 *
 * Everything runs at low alpha so the scatter doesn't compete with the text.
 */
@Composable
private fun CelebrationScatter(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val sparklePaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(140, 0xFF, 0xD6, 0x6B)
            // A soft glow like a CSS drop-shadow.
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.argb(120, 0xFF, 0xD6, 0x6B))
        }
    }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Stars, the lowest layer.
        val starPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(64, 0xF5, 0xEF, 0xDC)
            textSize = with(density) { 8.sp.toPx() }
        }
        listOf(
            0.08f to 0.06f, 0.90f to 0.05f, 0.15f to 0.88f,
            0.78f to 0.92f, 0.50f to 0.50f,
        ).forEach { (x, y) ->
            drawContext.canvas.nativeCanvas.drawText(
                "★", w * x, h * y, starPaint,
            )
        }

        // Streamers.
        data class Streamer(val xFrac: Float, val yFrac: Float, val deg: Float, val pink: Boolean)
        listOf(
            Streamer(0.12f, 0.22f, 20f, true),
            Streamer(0.84f, 0.30f, -25f, false),
            Streamer(0.86f, 0.78f, 15f, true),
            Streamer(0.08f, 0.60f, -30f, false),
        ).forEach { s ->
            val color = if (s.pink) Palette.brandPink else Palette.brandYellowBright
            withTransform({
                rotate(s.deg, pivot = Offset(w * s.xFrac, h * s.yFrac))
            }) {
                drawRect(
                    color = color.copy(alpha = 0.4f),
                    topLeft = Offset(w * s.xFrac, h * s.yFrac),
                    size = androidx.compose.ui.geometry.Size(1.5.dp.toPx(), 16.dp.toPx()),
                )
            }
        }

        // Confetti dots in mixed sizes.
        data class Dot(val xFrac: Float, val yFrac: Float, val color: Color, val rDp: Float)
        listOf(
            Dot(0.20f, 0.08f, Palette.brandYellow, 2.5f),
            Dot(0.42f, 0.06f, Palette.brandPink, 2f),
            Dot(0.65f, 0.09f, Palette.brandCream, 1.5f),
            Dot(0.80f, 0.18f, Palette.brandCream, 1.5f),
            Dot(0.06f, 0.38f, Palette.brandYellow, 2f),
            Dot(0.92f, 0.48f, Palette.brandPink, 2.5f),
            Dot(0.32f, 0.52f, Palette.brandYellow, 1.5f),
            Dot(0.74f, 0.66f, Palette.brandYellow, 2f),
            Dot(0.18f, 0.74f, Palette.brandCream, 1.5f),
            Dot(0.38f, 0.90f, Palette.brandPink, 2f),
            Dot(0.60f, 0.86f, Palette.brandCream, 1.5f),
        ).forEach { d ->
            drawCircle(
                color = d.color.copy(alpha = 0.4f),
                radius = d.rDp.dp.toPx(),
                center = Offset(w * d.xFrac, h * d.yFrac),
            )
        }

        // Sparkles, the top layer, with the drop shadow.
        data class Sparkle(val xFrac: Float, val yFrac: Float, val sizeSp: Float, val alpha: Int)
        listOf(
            Sparkle(0.75f, 0.08f, 16f, 180),
            Sparkle(0.14f, 0.14f, 10f, 140),
            Sparkle(0.48f, 0.20f, 7f, 100),
            Sparkle(0.90f, 0.88f, 10f, 140),
            Sparkle(0.10f, 0.82f, 16f, 180),
            Sparkle(0.54f, 0.74f, 7f, 100),
            Sparkle(0.88f, 0.36f, 7f, 100),
            Sparkle(0.24f, 0.48f, 7f, 100),
        ).forEach { s ->
            sparklePaint.alpha = s.alpha
            sparklePaint.textSize = with(density) { s.sizeSp.sp.toPx() }
            drawContext.canvas.nativeCanvas.drawText(
                "✦", w * s.xFrac, h * s.yFrac, sparklePaint,
            )
        }
    }
}

private object Palette {
    val brandTeal = Color(0xFF1F8A78)
    val brandTealMid = Color(0xFF196F60)
    val brandTealDark = Color(0xFF0E4A40)
    val brandBlue = Color(0xFF5BA3D0)
    val brandYellow = Color(0xFFF0C14B)
    val brandYellowBright = Color(0xFFFFD66B)
    val brandYellowPale = Color(0xFFFFE9B0)
    val brandPink = Color(0xFFE94B6E)
    val brandCream = Color(0xFFF5EFDC)
    val brandWarmCream = Color(0xFFE6DFC4)
}

/** Holds the test tags `ShareCardComposableTest` finds nodes by. */
object ShareCardTags {
    /** Tags the day stripe; absent when the stripe is hidden. */
    const val TAG_DAY_STRIPE = "share_card_day_stripe"

    /** Tags the confetti scatter; present only for Celebration. */
    const val TAG_CONFETTI = "share_card_confetti"
}

// =================== Previews ===================

@Preview(name = "Standard timed", widthDp = 420, heightDp = 525)
@Composable
private fun ShareCardPreview_StandardTimed() {
    MaterialTheme {
        ShareCardComposable(
            title = "Brunch at Sam's",
            location = "Sam's Café · 1234 Mission St",
            timeRangeText = "11:30 AM – 1:00 PM",
            style = ShareCardStyle.Standard,
            dateChip = DateChipText.Single("31", "MAY", "SUN"),
            stripe = StripePosition(0.479f, 0.0625f, true),
            stripeLabels = listOf("12a", "6a", "12p", "6p", "12a"),
            isAllDay = false,
            isMultiDay = false,
            multiDayRangeText = null,
            attribution = "Made with KashCal",
        )
    }
}

@Preview(name = "Celebration timed", widthDp = 420, heightDp = 525)
@Composable
private fun ShareCardPreview_CelebrationTimed() {
    MaterialTheme {
        ShareCardComposable(
            title = "🎂 Maya turns 5",
            location = "Dolores Park · upper meadow",
            timeRangeText = "2:00 – 5:00 PM",
            style = ShareCardStyle.Celebration,
            dateChip = DateChipText.Single("14", "JUN", "SAT"),
            stripe = StripePosition(0.583f, 0.125f, true),
            stripeLabels = listOf("12a", "6a", "12p", "6p", "12a"),
            isAllDay = false,
            isMultiDay = false,
            multiDayRangeText = null,
            attribution = "Made with KashCal",
        )
    }
}

@Preview(name = "All day", widthDp = 420, heightDp = 525)
@Composable
private fun ShareCardPreview_AllDay() {
    MaterialTheme {
        ShareCardComposable(
            title = "Vacation",
            location = null,
            timeRangeText = null,
            style = ShareCardStyle.Standard,
            dateChip = DateChipText.Single("31", "MAY", "SUN"),
            stripe = StripePosition.Hidden,
            stripeLabels = listOf("12a", "6a", "12p", "6p", "12a"),
            isAllDay = true,
            isMultiDay = false,
            multiDayRangeText = null,
            attribution = "Made with KashCal",
        )
    }
}

@Preview(name = "Multi-day", widthDp = 420, heightDp = 525)
@Composable
private fun ShareCardPreview_MultiDay() {
    MaterialTheme {
        ShareCardComposable(
            title = "Memorial weekend",
            location = "Tahoe",
            // A multi-day all-day event: the chip carries the dates, the time text adds the
            // weekdays and the all-day status.
            timeRangeText = "Sun – Wed · All day",
            style = ShareCardStyle.Standard,
            dateChip = DateChipText.Range("MAY 31 – JUN 3"),
            stripe = StripePosition.Hidden,
            stripeLabels = listOf("12a", "6a", "12p", "6p", "12a"),
            // The composable doesn't read these flags; the time text above already says it.
            isAllDay = false,
            isMultiDay = false,
            multiDayRangeText = null,
            attribution = "Made with KashCal",
        )
    }
}

@Preview(name = "Long title (3-line)", widthDp = 420, heightDp = 525)
@Composable
private fun ShareCardPreview_LongTitle() {
    MaterialTheme {
        ShareCardComposable(
            title = "Quarterly product committee review meeting kickoff",
            location = "Conference Room B",
            timeRangeText = "9:00 – 10:30 AM",
            style = ShareCardStyle.Standard,
            dateChip = DateChipText.Single("31", "MAY", "SUN"),
            stripe = StripePosition(0.375f, 0.0625f, true),
            stripeLabels = listOf("12a", "6a", "12p", "6p", "12a"),
            isAllDay = false,
            isMultiDay = false,
            multiDayRangeText = null,
            attribution = "Made with KashCal",
        )
    }
}

@Preview(name = "24h locale", widthDp = 420, heightDp = 525)
@Composable
private fun ShareCardPreview_24Hour() {
    MaterialTheme {
        ShareCardComposable(
            title = "Standup",
            location = null,
            timeRangeText = "10:00 – 10:15",
            style = ShareCardStyle.Standard,
            dateChip = DateChipText.Single("31", "MAY", "SUN"),
            stripe = StripePosition(0.417f, 0.0104f, true),
            stripeLabels = listOf("00", "06", "12", "18", "24"),
            isAllDay = false,
            isMultiDay = false,
            multiDayRangeText = null,
            attribution = "Made with KashCal",
        )
    }
}
