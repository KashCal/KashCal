package org.onekash.kashcal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import java.time.LocalDate

/**
 * Logo button for the top app bar. Renders the three stacked tilted calendar
 * cards with today's day-of-month painted on the front card. Tapping invokes
 * [onClick] — the caller wires this to "navigate to today" so the logo doubles
 * as the today affordance.
 *
 * The mark is a monochrome line drawing: the three cards are stroked outlines
 * (transparent fill) in the theme's on-surface color at reduced opacity, so it
 * sits a step below the toolbar title in emphasis while still adapting to
 * light/dark and dynamic color. The day-of-month numeral is drawn in the same
 * color, centered on the front card.
 *
 * Geometry mirrors images/icon-transparent.svg (viewBox 88x88): three
 * equal-size cards fanned around a shared center so the mark reads as a
 * deck. Any future tweak to the static SVG should be mirrored here.
 */
@Composable
fun TopBarLogoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    today: LocalDate = LocalDate.now(),
) {
    val description = stringResource(R.string.shortcut_today_long)
    val scheme = MaterialTheme.colorScheme
    // Monochrome line drawing: the outlined deck is dimmed below full
    // on-surface so it reads as secondary to the toolbar title, while the
    // day-of-month numeral stays at full on-surface so the date is the
    // focal point of the mark.
    val deckColor = scheme.onSurface.copy(alpha = 0.7f)
    val numeralColor = scheme.onSurface.toArgb()
    Canvas(
        modifier = modifier
            .size(size)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            }
    ) {
        val day = today.dayOfMonth
        val scale = this.size.width / 88f
        val stroke = Stroke(width = 3.5f * scale)

        // Three equal-size cards (36×42) fanned around a shared center at
        // (44,44) so the icon reads as a deck of dated pages rather than a
        // loose pile of mismatched cards. The back cards peek as uniform
        // edges; the tight fan keeps overall width in check. The front card
        // carries the day-of-month numeral.
        translate(left = 44f * scale, top = 44f * scale) {
            rotate(degrees = 8f, pivot = Offset.Zero) {
                drawRoundRect(
                    color = deckColor,
                    topLeft = Offset(-18f * scale, -21f * scale),
                    size = Size(36f * scale, 42f * scale),
                    cornerRadius = CornerRadius(6f * scale, 6f * scale),
                    style = stroke,
                )
            }
        }

        translate(left = 44f * scale, top = 44f * scale) {
            rotate(degrees = -7f, pivot = Offset.Zero) {
                drawRoundRect(
                    color = deckColor,
                    topLeft = Offset(-18f * scale, -21f * scale),
                    size = Size(36f * scale, 42f * scale),
                    cornerRadius = CornerRadius(6f * scale, 6f * scale),
                    style = stroke,
                )
            }
        }

        translate(left = 44f * scale, top = 44f * scale) {
            rotate(degrees = -2f, pivot = Offset.Zero) {
                drawRoundRect(
                    color = deckColor,
                    topLeft = Offset(-18f * scale, -21f * scale),
                    size = Size(36f * scale, 42f * scale),
                    cornerRadius = CornerRadius(6f * scale, 6f * scale),
                    style = stroke,
                )
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = numeralColor
                        textSize = 26f * scale
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.create(
                            android.graphics.Typeface.DEFAULT,
                            android.graphics.Typeface.BOLD,
                        )
                    }
                    // Baseline offset that vertically centers the glyphs on the
                    // card center (0,0 here after the translate), so the number
                    // sits in the middle of the outlined front card.
                    val fm = paint.fontMetrics
                    val baselineY = -(fm.ascent + fm.descent) / 2f
                    canvas.nativeCanvas.drawText(day.toString(), 0f, baselineY, paint)
                }
            }
        }
    }
}
