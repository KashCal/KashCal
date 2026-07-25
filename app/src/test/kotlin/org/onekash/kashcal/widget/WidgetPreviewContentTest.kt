package org.onekash.kashcal.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.testutil.resolveProjectRoot
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate

/**
 * Unit tests for the widget-picker preview bodies.
 *
 * Two distinct jobs here. The render tests drive each extracted preview composable
 * through the Glance harness and assert it shows sample content rather than an empty or
 * loading state. The wiring guard asserts each widget class actually declares
 * `providePreview` — the base implementation is a silent no-op, so without this guard
 * deleting every override would leave these render tests green while the shipped
 * feature disappeared.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetPreviewContentTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // ---- Wiring guard ----------------------------------------------------------------

    @Test
    fun `every widget declares its own providePreview override`() {
        val widgets = listOf(
            AgendaWidget::class.java,
            WeekWidget::class.java,
            MonthWidget::class.java,
            DateWidget::class.java,
            UpcomingWidget::class.java
        )

        widgets.forEach { clazz ->
            val declared = clazz.declaredMethods.any { it.name == "providePreview" }
            assertTrue(
                "${clazz.simpleName} does not declare providePreview — the picker would " +
                    "silently fall back to the placeholder",
                declared
            )
        }
    }

    @Test
    fun `every widget overrides previewSizeMode so previews are not composed at min size`() {
        val widgets = listOf(
            AgendaWidget(),
            WeekWidget(),
            MonthWidget(),
            DateWidget(),
            UpcomingWidget()
        )

        widgets.forEach { widget ->
            val mode = widget.previewSizeMode
            assertTrue(
                "${widget.javaClass.simpleName} left previewSizeMode at the default " +
                    "SizeMode.Single, which composes previews at the provider's minWidth " +
                    "and drops content: $mode",
                mode is SizeMode.Responsive
            )
        }
    }

    @Test
    fun `preview sizes match the cells and minimums each provider declares`() {
        // Preview sizes are authored independently of the provider descriptors, so a resize
        // in the XML would otherwise silently leave the preview at the old size. Composing
        // below a provider's own declared minimum is the failure this guards hardest: it
        // drops content out of the preview, which is why the size is overridden at all.
        listOf(
            "agenda_widget_info.xml" to WidgetPreviewSizes.AGENDA,
            "week_widget_info.xml" to WidgetPreviewSizes.WEEK,
            "month_widget_info.xml" to WidgetPreviewSizes.MONTH,
            "date_widget_info.xml" to WidgetPreviewSizes.DATE,
            "upcoming_widget_info.xml" to WidgetPreviewSizes.UPCOMING
        ).forEach { (descriptor, sizeMode) ->
            val xml = File(resolveProjectRoot(), "app/src/main/res/xml/$descriptor").readText()
            val expected = previewSize(
                columns = declaredDimension(xml, descriptor, "targetCellWidth"),
                rows = declaredDimension(xml, descriptor, "targetCellHeight"),
                minWidth = declaredDimension(xml, descriptor, "minWidth").dp,
                minHeight = declaredDimension(xml, descriptor, "minHeight").dp
            )

            assertEquals(
                "$descriptor declares cells/minimums its preview size does not match",
                SizeMode.Responsive(setOf(expected)),
                sizeMode
            )

            val size = (sizeMode as SizeMode.Responsive).sizes.single()
            assertTrue(
                "$descriptor previews at $size, narrower than its declared minWidth",
                size.width >= declaredDimension(xml, descriptor, "minWidth").dp
            )
            assertTrue(
                "$descriptor previews at $size, shorter than its declared minHeight",
                size.height >= declaredDimension(xml, descriptor, "minHeight").dp
            )
        }
    }

    /** Reads an integer attribute, tolerating a `dp` suffix on the dimension ones. */
    private fun declaredDimension(xml: String, descriptor: String, attr: String): Int {
        val match = Regex("""android:$attr="(\d+)(?:dp)?"""").find(xml)
        assertTrue("$descriptor declares no android:$attr", match != null)
        return match!!.groupValues[1].toInt()
    }

    // ---- Render tests ---------------------------------------------------------------

    @Test
    fun `agenda preview shows sample events and not the empty state`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { AgendaPreviewContent(context) }

        onNode(hasText(context.getString(R.string.widget_preview_event_standup)))
            .assertExists()
        onNode(hasText(context.getString(R.string.widget_no_events_today)))
            .assertDoesNotExist()
    }

    @Test
    fun `week preview shows day headers and at least one sample event`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { WeekPreviewContent(context) }

        // The week widget renders a header per day; assert today's header is present.
        val todayCode = LocalDate.now().let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }
        onNode(hasText(formatDayHeaderText(todayCode))).assertExists()
        onNode(hasText(context.getString(R.string.widget_preview_event_standup)))
            .assertExists()
    }

    @Test
    fun `month preview renders the current month header`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { MonthPreviewContent(context) }

        val today = LocalDate.now()
        onNode(hasText(formatMonthHeader(today.year, today.monthValue - 1))).assertExists()
    }

    @Test
    fun `upcoming preview shows sample content and never the loading or error state`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { UpcomingPreviewContent(context) }

            onNode(hasText(context.getString(R.string.widget_preview_event_standup)))
                .assertExists()
            onNode(hasText(context.getString(R.string.widget_loading_upcoming)))
                .assertDoesNotExist()
            onNode(hasText(context.getString(R.string.widget_no_upcoming_events)))
                .assertDoesNotExist()
        }

    @Test
    fun `date preview renders today's date number`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { DatePreviewContent() }

        onNode(hasText(LocalDate.now().dayOfMonth.toString())).assertExists()
    }
}
