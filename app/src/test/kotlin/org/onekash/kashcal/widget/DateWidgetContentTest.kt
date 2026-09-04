package org.onekash.kashcal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Unit tests for [DateWidgetContent]'s size-responsive face selection.
 *
 * Two layers: the pure [dateWidgetLayout] decision (icon vs card, font-scale aware) is
 * asserted directly; the rendered content is exercised through Glance's real RemoteViews
 * translation at a chosen [DpSize] (the layer that reads [androidx.glance.LocalSize]), so a
 * small size shows the short weekday + day number and a large size shows the full weekday +
 * month/day.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DateWidgetContentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ==================== dateWidgetLayout (pure) ====================

    @Test
    fun `icon-sized widget is ICON`() {
        assertEquals(DateWidgetLayout.ICON, dateWidgetLayout(57f, 57f, 1f))
        assertEquals(DateWidgetLayout.ICON, dateWidgetLayout(40f, 40f, 1f))
    }

    @Test
    fun `two-cell and larger widget is CARD`() {
        assertEquals(DateWidgetLayout.CARD, dateWidgetLayout(110f, 57f, 1f))
        assertEquals(DateWidgetLayout.CARD, dateWidgetLayout(200f, 110f, 1f))
    }

    @Test
    fun `a wide but very short widget stays ICON`() {
        // Below the height floor — a thin horizontal strip can't host the two-line card.
        assertEquals(DateWidgetLayout.ICON, dateWidgetLayout(200f, 40f, 1f))
    }

    @Test
    fun `a larger font scale raises the width needed for the card`() {
        // 100dp wide is a card at font scale 1 but not at 1.5 (85 * 1.5 = 127.5).
        assertEquals(DateWidgetLayout.CARD, dateWidgetLayout(100f, 57f, 1f))
        assertEquals(DateWidgetLayout.ICON, dateWidgetLayout(100f, 57f, 1.5f))
    }

    // ==================== rendered content ====================

    @Test
    fun `small size shows the short weekday and day number, not the full weekday`() {
        val locale = context.resources.configuration.locales[0]
        val today = LocalDate.now()
        val short = WidgetDateFormatter.buildDateWidgetLabels(today, locale)
        val full = WidgetDateFormatter.buildFullDateWidgetLabels(today, locale)

        val texts = renderTexts(DpSize(57.dp, 57.dp))

        assertTrue("expected short weekday '${short.dayName}' in $texts", texts.contains(short.dayName))
        assertTrue("expected day number '${short.dateNumber}' in $texts", texts.contains(short.dateNumber))
        assertFalse("full weekday '${full.weekdayFull}' should not render at icon size", texts.contains(full.weekdayFull))
    }

    @Test
    fun `large size shows the full weekday and full month and day`() {
        val locale = context.resources.configuration.locales[0]
        val today = LocalDate.now()
        val full = WidgetDateFormatter.buildFullDateWidgetLabels(today, locale)

        val texts = renderTexts(DpSize(200.dp, 110.dp))

        assertTrue("expected full weekday '${full.weekdayFull}' in $texts", texts.contains(full.weekdayFull))
        assertTrue("expected full month/day '${full.monthDay}' in $texts", texts.contains(full.monthDay))
    }

    /** Translate [DateWidgetContent] at [size] and collect every rendered TextView string. */
    private fun renderTexts(size: DpSize): List<String> {
        val result = translate(size) { GlanceTheme { DateWidgetContent() } }
        return collectTexts(result.remoteViews)
    }

    private fun translate(size: DpSize, content: @Composable () -> Unit) = runBlocking {
        GlanceRemoteViews().compose(context = context, size = size, content = content)
    }

    private fun collectTexts(rv: android.widget.RemoteViews): List<String> {
        val root = rv.apply(context, android.widget.FrameLayout(context))
        val out = mutableListOf<String>()
        fun walk(v: android.view.View) {
            if (v is android.widget.TextView) v.text?.toString()?.let { out += it }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }
}
