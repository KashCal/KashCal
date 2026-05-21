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
 * Geometry mirrors images/icon-transparent.svg (viewBox 88x88) so any future
 * tweak to the static SVG should be mirrored here.
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

        translate(left = 56f * scale, top = 36f * scale) {
            rotate(degrees = 14f, pivot = Offset.Zero) {
                drawRoundRect(
                    color = Color(0xFFF2C14E),
                    topLeft = Offset(-12f * scale, -13f * scale),
                    size = Size(24f * scale, 26f * scale),
                    cornerRadius = CornerRadius(4f * scale, 4f * scale),
                )
            }
        }

        translate(left = 32f * scale, top = 38f * scale) {
            rotate(degrees = -12f, pivot = Offset.Zero) {
                drawRoundRect(
                    color = Color(0xFF4A9BDC),
                    topLeft = Offset(-13f * scale, -15f * scale),
                    size = Size(26f * scale, 30f * scale),
                    cornerRadius = CornerRadius(4.5f * scale, 4.5f * scale),
                )
            }
        }

        translate(left = 44f * scale, top = 46f * scale) {
            rotate(degrees = -2f, pivot = Offset.Zero) {
                drawRoundRect(
                    color = Color(0xFF2A8A7A),
                    topLeft = Offset(-18f * scale, -20f * scale),
                    size = Size(36f * scale, 40f * scale),
                    cornerRadius = CornerRadius(6f * scale, 6f * scale),
                )
                drawRoundRect(
                    color = Color(0xFF0D9488),
                    topLeft = Offset(-18f * scale, -20f * scale),
                    size = Size(36f * scale, 8f * scale),
                    cornerRadius = CornerRadius(6f * scale, 6f * scale),
                )
                drawCircle(
                    color = Color(0xFFE85D75),
                    radius = 4f * scale,
                    center = Offset(14f * scale, -16f * scale),
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
                    canvas.nativeCanvas.drawText(day.toString(), 0f, 10f * scale, paint)
                }
            }
        }
    }
}
