package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
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
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import org.onekash.kashcal.MainActivity
import java.time.LocalDate

/**
 * Content for the date widget - shows today's date in an icon-like format.
 *
 * Layout:
 * ┌─────────┐
 * │   SUN   │  ← day name (small)
 * │   19    │  ← date number (large)
 * └─────────┘
 *
 * Locale: Glance widgets don't recompose on Configuration changes (they redraw
 * on their own schedule — see DateWidget.kt). The LocalContext read silences
 * the NonObservableLocale lint rule; correct locale is picked up at the next
 * scheduled update, not in response to Compose reactivity.
 */
@Composable
fun DateWidgetContent() {
    val locale = LocalContext.current.resources.configuration.locales[0]
    val today = LocalDate.now()
    val labels = WidgetDateFormatter.buildDateWidgetLabels(today, locale)
    val dayName = labels.dayName
    val dateNumber = labels.dateNumber

    // The visible day-name/date-number split reads as two disconnected fragments
    // to TalkBack; give the whole widget one full localized date label instead.
    val fullDateLabel = today.format(
        java.time.format.DateTimeFormatter
            .ofLocalizedDate(java.time.format.FormatStyle.FULL)
            .withLocale(locale)
    )

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(4.dp)
            .semantics { contentDescription = fullDateLabel }
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
                        fontSize = WidgetTypography.label,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                )

                // Date number (e.g., "19")
                Text(
                    text = dateNumber,
                    style = TextStyle(
                        color = WidgetTheme.primaryText,
                        fontSize = WidgetTypography.dateNumber,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}
