package org.onekash.kashcal.widget

import android.content.Context
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasContentDescription
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner

/**
 * Tests [WidgetAddButton], the shared "add event" control in the agenda, week, month and
 * upcoming widget headers.
 *
 * The button is a plain glyph with no filled chip behind it, so its accessibility rests on a
 * content description for screen readers, a click action and a touch target at Material's 48dp
 * guidance, the three things asserted here.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetAddButtonTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `add button exposes its content description for screen readers`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { WidgetAddButton() }

        onNode(hasContentDescription(context.getString(R.string.cd_widget_add_event)))
            .assertExists()
    }

    @Test
    fun `add button is clickable`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { WidgetAddButton() }

        onNode(hasClickAction()).assertExists()
    }

    @Test
    fun `touch target meets the 48dp accessibility guidance`() {
        assertEquals(48, WIDGET_ADD_BUTTON_TOUCH_TARGET_DP)
    }
}
