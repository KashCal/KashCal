package org.onekash.kashcal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
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
 * cards (yellow back, blue middle, teal front with red dot) with today's
 * day-of-month painted on the front card. Tapping invokes [onClick] — the
 * caller wires this to "navigate to today" so the logo doubles as the today
 * affordance.
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

        // Three equal-size cards (36×42) fanned around a shared center at
        // (44,44) so the icon reads as a deck of dated pages rather than a
        // loose pile of mismatched cards. The back cards peek as uniform
        // edges; the tight ±7–8° fan keeps overall width in check. Gold and
        // blue behind, teal front with the header bar, today-dot, and the
        // day-of-month numeral.
        translate(left = 44f * scale, top = 44f * scale) {
            rotate(degrees = 8f, pivot = Offset.Zero) {
                drawRoundRect(
                    color = Color(0xFFF2C14E),
                    topLeft = Offset(-18f * scale, -21f * scale),
                    size = Size(36f * scale, 42f * scale),
                    cornerRadius = CornerRadius(6f * scale, 6f * scale),
                )
            }
        }

        translate(left = 44f * scale, top = 44f * scale) {
            rotate(degrees = -7f, pivot = Offset.Zero) {
                drawRoundRect(
                    color = Color(0xFF4A9BDC),
                    topLeft = Offset(-18f * scale, -21f * scale),
                    size = Size(36f * scale, 42f * scale),
                    cornerRadius = CornerRadius(6f * scale, 6f * scale),
                )
            }
        }

        translate(left = 44f * scale, top = 44f * scale) {
            rotate(degrees = -2f, pivot = Offset.Zero) {
                drawRoundRect(
                    color = Color(0xFF2A8A7A),
                    topLeft = Offset(-18f * scale, -21f * scale),
                    size = Size(36f * scale, 42f * scale),
                    cornerRadius = CornerRadius(6f * scale, 6f * scale),
                )
                // Header bar: rounded top corners, squared bottom edge
                // (the filler rect flattens the rounded lower corners).
                drawRoundRect(
                    color = Color(0xFF0D9488),
                    topLeft = Offset(-18f * scale, -21f * scale),
                    size = Size(36f * scale, 9f * scale),
                    cornerRadius = CornerRadius(6f * scale, 6f * scale),
                )
                drawRect(
                    color = Color(0xFF2A8A7A),
                    topLeft = Offset(-18f * scale, -16f * scale),
                    size = Size(36f * scale, 5f * scale),
                )
                drawCircle(
                    color = Color(0xFFE85D75),
                    radius = 4f * scale,
                    center = Offset(13f * scale, -17f * scale),
                )
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.parseColor("#FDF8EC")
                        textSize = 22f * scale
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.create(
                            android.graphics.Typeface.DEFAULT,
                            android.graphics.Typeface.BOLD,
                        )
                    }
                    canvas.nativeCanvas.drawText(day.toString(), 0f, 9f * scale, paint)
                }
            }
        }
    }
}
