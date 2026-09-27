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
 * Draws the app logo for the top app bar: three tilted calendar cards with [today]'s day of
 * month on the front card. The caller passes "go to today" as [onClick], so the logo doubles as
 * the today button.
 *
 * The mark is a monochrome line drawing in the theme's on-surface color, so it follows light,
 * dark and dynamic color. Geometry mirrors images/icon-transparent.svg (viewBox 88x88); mirror
 * any change to that SVG here.
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
    // The outlines are dimmed so the deck sits below the toolbar title; the numeral stays at
    // full on-surface so the date is the focal point.
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

        // Three equal 36 by 42 cards fanned around the shared center (44,44), back to front; the
        // last one carries the numeral.
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
                    // Baseline that centers the glyphs vertically on the card center, which is
                    // (0,0) after the translate.
                    val fm = paint.fontMetrics
                    val baselineY = -(fm.ascent + fm.descent) / 2f
                    canvas.nativeCanvas.drawText(day.toString(), 0f, baselineY, paint)
                }
            }
        }
    }
}
