package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import org.onekash.kashcal.MainActivity
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * Content for the date widget - shows today's date in an icon-like format.
 *
 * Layout:
 * ┌─────────┐
 * │   SUN   │  ← day name (small)
 * │   19    │  ← date number (large)
 * └─────────┘
 */
@Composable
fun DateWidgetContent() {
    val today = LocalDate.now()
    val dayName = today.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
        .uppercase(Locale.getDefault())
    val dateNumber = today.dayOfMonth.toString()

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(4.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_TODAY
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Circular background
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(100.dp)  // Large radius for circle
                .background(WidgetTheme.headerBackground),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Day name (e.g., "SUN")
                Text(
                    text = dayName,
                    style = TextStyle(
                        color = WidgetTheme.secondaryText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                )

                // Date number (e.g., "19")
                Text(
                    text = dateNumber,
                    style = TextStyle(
                        color = WidgetTheme.primaryText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}
