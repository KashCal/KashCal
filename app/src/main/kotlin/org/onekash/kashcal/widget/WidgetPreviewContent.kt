package org.onekash.kashcal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.SizeMode
import java.time.LocalDate

/**
 * The composable bodies rendered into the system widget picker.
 *
 * Each one calls the widget's real content composable with sample data, so a preview
 * cannot drift away from what the widget actually looks like. Unlike `provideGlance`,
 * `providePreview` is a single composition with no recomposition and no effects, so data
 * is passed in directly rather than produced asynchronously.
 *
 * These are separate top-level composables rather than lambdas inside `providePreview`
 * because the Glance unit-test harness can drive a composable but cannot invoke
 * `providePreview` itself.
 */

/**
 * The dp size a widget occupying [columns] x [rows] home-screen cells is estimated to get,
 * following the platform's own conversion: 70n - 30.
 */
internal fun previewCellSize(columns: Int, rows: Int): DpSize =
    DpSize((70 * columns - 30).dp, (70 * rows - 30).dp)

/**
 * The size to compose a preview at, for a widget occupying [columns] x [rows] cells whose
 * provider declares [minWidth] x [minHeight].
 *
 * Previews default to `SizeMode.Single`, which composes at the provider's declared minimum
 * and silently drops anything that doesn't fit, so each widget supplies a size explicitly.
 * That size is never smaller than the provider's declared minimum: the cell estimate falls
 * below it for a 1x1 widget and for any widget taller than its cells suggest, and composing
 * into a box smaller than the widget can ever actually be reintroduces the dropped-content
 * problem the explicit size exists to avoid.
 */
internal fun previewSize(columns: Int, rows: Int, minWidth: Dp, minHeight: Dp): DpSize {
    val cells = previewCellSize(columns, rows)
    return DpSize(max(cells.width, minWidth), max(cells.height, minHeight))
}

/**
 * Preview size modes. The minimums mirror each provider's declared `minWidth`/`minHeight`;
 * a test reads the descriptors and fails if either side drifts.
 */
internal object WidgetPreviewSizes {
    val AGENDA = SizeMode.Responsive(setOf(previewSize(4, 2, 250.dp, 110.dp)))
    val WEEK = SizeMode.Responsive(setOf(previewSize(4, 4, 250.dp, 250.dp)))
    val MONTH = SizeMode.Responsive(setOf(previewSize(4, 4, 250.dp, 304.dp)))
    val DATE = SizeMode.Responsive(setOf(previewSize(1, 1, 57.dp, 57.dp)))
    val UPCOMING = SizeMode.Responsive(setOf(previewSize(4, 4, 180.dp, 130.dp)))
}

@Composable
internal fun AgendaPreviewContent(context: Context) {
    GlanceTheme {
        AgendaWidgetContent(
            events = WidgetPreviewData.agendaEvents(context),
            currentDate = widgetHeaderDate(),
            showEventEmojis = true,
            timePattern = WidgetPreviewData.timePattern(context),
            maxEventsPerDay = WidgetPreviewData.MAX_EVENTS_PER_DAY
        )
    }
}

@Composable
internal fun WeekPreviewContent(context: Context) {
    GlanceTheme {
        WeekWidgetContent(
            weekEvents = WidgetPreviewData.weekEvents(context),
            showEventEmojis = true,
            timePattern = WidgetPreviewData.timePattern(context),
            maxEventsPerDay = WidgetPreviewData.MAX_EVENTS_PER_DAY
        )
    }
}

@Composable
internal fun MonthPreviewContent(context: Context) {
    // Year and month come off the grid itself so they can't disagree with it.
    val grid = WidgetPreviewData.monthGrid()
    GlanceTheme {
        MonthWidgetContent(
            monthGrid = grid,
            monthEvents = WidgetPreviewData.monthEvents(context),
            // Previews always show the current month, never a navigated one.
            monthOffset = 0,
            targetYear = grid.year,
            targetMonth0 = grid.month,
            firstDayOfWeek = WidgetPreviewData.LOCALE_FIRST_DAY_OF_WEEK,
            // Show the titles style: it is the richer day-cell look and matches what the
            // in-app month view advertises.
            showEventTitles = true
        )
    }
}

@Composable
internal fun UpcomingPreviewContent(context: Context) {
    GlanceTheme {
        UpcomingWidgetContent(
            eventsByDay = WidgetPreviewData.upcomingEvents(context),
            todayDayCode = WidgetPreviewData.dayCodeOf(LocalDate.now()),
            showEventEmojis = true,
            timePattern = WidgetPreviewData.timePattern(context)
        )
    }
}

@Composable
internal fun DatePreviewContent() {
    GlanceTheme {
        DateWidgetContent()
    }
}
