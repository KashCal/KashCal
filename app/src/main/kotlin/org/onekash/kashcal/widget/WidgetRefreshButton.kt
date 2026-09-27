package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.size
import org.onekash.kashcal.R

/**
 * Draws the header refresh glyph, sized and tinted like its neighbor [WidgetAddButton].
 *
 * It uses the same [WIDGET_ADD_BUTTON_TOUCH_TARGET_DP] (48dp) touch target and
 * [WIDGET_HEADER_GLYPH_SIZE_DP] glyph, tinted [WidgetTheme.onHeaderBackground] so it clears WCAG
 * contrast against the header for every accent seed. The two buttons abut with no Spacer; the
 * ~28dp of clear space between the glyphs comes from centering each in its own 48dp box.
 *
 * A tap fires [WidgetRefreshAction] for [kind], which requests a sync and repaints. While the cue
 * is on, the glyph dims to [WidgetTheme.dimmedOnHeaderBackground]; the cue expires by itself
 * ([isRefreshCueActive]).
 *
 * @param kind the widget this button lives in, so the action repaints that widget class.
 * @param isRefreshing whether to dim the glyph. The caller computes it from
 *   [WIDGET_REFRESHING_UNTIL], so this composable stays pure for render tests.
 */
@Composable
fun WidgetRefreshButton(kind: WidgetKind, isRefreshing: Boolean) {
    val tint = if (isRefreshing) WidgetTheme.dimmedOnHeaderBackground else WidgetTheme.onHeaderBackground
    Box(
        modifier = GlanceModifier
            .size(WIDGET_ADD_BUTTON_TOUCH_TARGET_DP.dp)
            .clickable(
                actionRunCallback<WidgetRefreshAction>(
                    parameters = actionParametersOf(WidgetRefreshAction.KIND to kind.name)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_refresh),
            contentDescription = LocalContext.current.getString(R.string.cd_widget_refresh),
            colorFilter = ColorFilter.tint(tint),
            modifier = GlanceModifier.size(WIDGET_HEADER_GLYPH_SIZE_DP.dp)
        )
    }
}
