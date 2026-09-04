package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
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
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import org.onekash.kashcal.MainActivity
import java.time.LocalDate

/**
 * The minimum widget width/height (dp, at font scale 1) at which the date widget
 * switches from the small circular icon to the fuller rounded date-card. The card
 * needs horizontal room for the spelled-out weekday and month, so the decision is
 * width-led. Both floors scale with the system font scale (see [dateWidgetLayout]):
 * a larger font needs more room, so the card appears only once there is enough.
 *
 * The width floor sits comfortably between the ~57dp icon and the ~110dp two-cell
 * card so the two sizes classify cleanly at font scale 1.
 */
internal const val DATE_CARD_MIN_WIDTH_DP = 85f
internal const val DATE_CARD_MIN_HEIGHT_DP = 44f

/** Inset applied on every edge inside the widget, before either face is drawn. */
private const val PADDING_DP = 4f

/** Which face the date widget renders, chosen purely from its size. */
internal enum class DateWidgetLayout { ICON, CARD }

/**
 * Picks the icon vs card face from the widget's size and the system font scale — no
 * user setting. CARD once the widget is at least [DATE_CARD_MIN_WIDTH_DP] wide and
 * [DATE_CARD_MIN_HEIGHT_DP] tall (both scaled by [fontScale]); ICON otherwise.
 */
internal fun dateWidgetLayout(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float = 1f
): DateWidgetLayout {
    val minWidth = DATE_CARD_MIN_WIDTH_DP * fontScale
    val minHeight = DATE_CARD_MIN_HEIGHT_DP * fontScale
    return if (widthDp >= minWidth && heightDp >= minHeight) {
        DateWidgetLayout.CARD
    } else {
        DateWidgetLayout.ICON
    }
}

/**
 * Content for the date widget — today's date, size-responsive.
 *
 * Small (ICON): a circular icon-like face, short uppercase weekday over the day number.
 * ┌─────────┐
 * │   SAT   │  ← short day name
 * │   19    │  ← date number (large)
 * └─────────┘
 *
 * Larger (CARD): a rounded date-card with the full weekday and full month + day.
 * ┌───────────────┐
 * │ Saturday      │  ← full weekday
 * │ September 19  │  ← full month + day
 * └───────────────┘
 *
 * The face is chosen from [LocalSize] via [dateWidgetLayout]; the widget shows only the
 * date at every size (no event content).
 *
 * Locale: Glance widgets don't recompose on Configuration changes (they redraw on their
 * own schedule — see DateWidget.kt). The LocalContext read silences the NonObservableLocale
 * lint rule; correct locale is picked up at the next scheduled update, not in response to
 * Compose reactivity.
 */
@Composable
fun DateWidgetContent() {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val fontScale = context.resources.configuration.fontScale
    val today = LocalDate.now()

    // The visible label splits into fragments that read as disconnected to TalkBack;
    // give the whole widget one full localized date label at every size instead.
    val fullDateLabel = today.format(
        java.time.format.DateTimeFormatter
            .ofLocalizedDate(java.time.format.FormatStyle.FULL)
            .withLocale(locale)
    )

    val size = LocalSize.current
    val layout = dateWidgetLayout(size.width.value, size.height.value, fontScale)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(PADDING_DP.dp)
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
        when (layout) {
            // Keep the icon a true circle: the resize envelope now allows non-square
            // sizes below the card threshold (e.g. a 1x2 placement), so size the face
            // to the largest square that fits the padded area rather than fillMaxSize
            // (which would stretch the circle into a pill).
            DateWidgetLayout.ICON -> {
                val side = minOf(size.width.value, size.height.value) - PADDING_DP * 2
                DateIconFace(today, locale, side.coerceAtLeast(0f))
            }
            DateWidgetLayout.CARD -> DateCardFace(today, locale)
        }
    }
}

/**
 * Small circular icon: short uppercase weekday over the day-of-month number.
 * Sized to a square of [sideDp] so the circle holds its shape at non-square sizes.
 */
@Composable
private fun DateIconFace(today: LocalDate, locale: java.util.Locale, sideDp: Float) {
    val labels = WidgetDateFormatter.buildDateWidgetLabels(today, locale)
    Box(
        modifier = GlanceModifier
            .size(sideDp.dp)
            .cornerRadius(100.dp)  // Large radius for circle
            .background(WidgetTheme.headerBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Day name (e.g., "SAT")
            Text(
                text = labels.dayName,
                style = TextStyle(
                    color = WidgetTheme.onHeaderBackground,
                    fontSize = WidgetTypography.label,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            )

            // Date number (e.g., "19")
            Text(
                text = labels.dateNumber,
                style = TextStyle(
                    color = WidgetTheme.onHeaderBackground,
                    fontSize = WidgetTypography.dateNumber,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

/** Rounded date-card: full weekday over the full localized month + day. */
@Composable
private fun DateCardFace(today: LocalDate, locale: java.util.Locale) {
    val labels = WidgetDateFormatter.buildFullDateWidgetLabels(today, locale)
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .background(WidgetTheme.headerBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Full weekday (e.g., "Saturday")
            Text(
                text = labels.weekdayFull,
                style = TextStyle(
                    color = WidgetTheme.onHeaderBackground,
                    fontSize = WidgetTypography.secondary,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            )

            // Full month + day (e.g., "September 19")
            Text(
                text = labels.monthDay,
                style = TextStyle(
                    color = WidgetTheme.onHeaderBackground,
                    fontSize = WidgetTypography.headerTitle,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}
