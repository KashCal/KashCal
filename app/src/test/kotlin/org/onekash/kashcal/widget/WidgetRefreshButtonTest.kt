package org.onekash.kashcal.widget

import android.content.Context
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasContentDescription
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [WidgetRefreshButton] — the header "refresh" control, sibling of
 * [WidgetAddButton]. Like the add button it is a plain glyph with no chip behind it, so its
 * accessibility rests on a content description and a click action; both must survive whether the
 * button is idle or showing the dimmed "syncing" cue.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRefreshButtonTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `refresh button exposes its content description for screen readers`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { WidgetRefreshButton(kind = WidgetKind.AGENDA, isRefreshing = false) }

        onNode(hasContentDescription(context.getString(R.string.cd_widget_refresh)))
            .assertExists()
    }

    @Test
    fun `refresh button is clickable`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { WidgetRefreshButton(kind = WidgetKind.WEEK, isRefreshing = false) }

        onNode(hasClickAction()).assertExists()
    }

    @Test
    fun `refresh button keeps its description and click action while showing the syncing cue`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { WidgetRefreshButton(kind = WidgetKind.UPCOMING, isRefreshing = true) }

            onNode(hasContentDescription(context.getString(R.string.cd_widget_refresh)))
                .assertExists()
            onNode(hasClickAction()).assertExists()
        }
}
