package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.size
import androidx.glance.LocalContext
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R

/** Touch-target size for the header "add" button — Material's 48dp accessibility guidance. */
internal const val WIDGET_ADD_BUTTON_TOUCH_TARGET_DP = 48

/**
 * Drawn size of the header glyphs ("+" and refresh), centered in the 48dp touch target.
 * Shared with [WidgetRefreshButton] so the two header actions are visually identical in size.
 */
internal const val WIDGET_HEADER_GLYPH_SIZE_DP = 24

/**
 * Plain "add event" glyph for widget headers.
 *
 * A bare "+" tinted in [WidgetTheme.onHeaderBackground] — the same tone as the header's
 * title text, so it clears WCAG contrast against the accent-container header for every seed — with
 * no filled chip or shadow behind it. The visible glyph is small, but the clickable Box is
 * a full 48dp so the touch target still meets the accessibility guidance. The glyph is
 * centered in that box, giving equal inset on every side; a header that wants the glyph
 * symmetric with its top edge sets its own end padding equal to its top padding.
 */
@Composable
fun WidgetAddButton() {
    Box(
        modifier = GlanceModifier
            .size(WIDGET_ADD_BUTTON_TOUCH_TARGET_DP.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_CREATE_EVENT
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_add),
            contentDescription = LocalContext.current.getString(R.string.cd_widget_add_event),
            colorFilter = ColorFilter.tint(WidgetTheme.onHeaderBackground),
            modifier = GlanceModifier.size(WIDGET_HEADER_GLYPH_SIZE_DP.dp)
        )
    }
}
