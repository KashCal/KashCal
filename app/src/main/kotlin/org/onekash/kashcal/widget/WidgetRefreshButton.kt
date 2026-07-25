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
 * "Refresh" glyph for widget headers — sibling of [WidgetAddButton].
 *
 * Sized and toned to match the add button exactly: the same 48dp
 * [WIDGET_ADD_BUTTON_TOUCH_TARGET_DP] touch target and [WIDGET_HEADER_GLYPH_SIZE_DP] glyph, tinted
 * [WidgetTheme.onHeaderBackground] so it clears WCAG contrast against the header for every accent
 * seed. The two buttons abut with no Spacer between them; the ~28dp of clear space between the
 * glyphs comes from each glyph being centered in its own 48dp box.
 *
 * Tapping fires [WidgetRefreshAction] for [kind], which kicks off a CalDAV sync and repaints.
 * While that sync is in flight the caller passes [isRefreshing] = true and the glyph dims
 * ([WidgetTheme.dimmedOnHeaderBackground]) as an immediate "syncing" cue. The cue is a token swap,
 * not a fade — Glance has no alpha modifier — and self-expires via [isRefreshCueActive], so it can
 * never get stuck on.
 *
 * @param kind Which widget this button lives in, so the action can repaint the right widget class.
 * @param isRefreshing Whether the syncing cue should show (glyph dimmed). Computed by the caller
 *   from [WIDGET_REFRESHING_UNTIL]; kept as a plain param so this composable stays render-test-pure.
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
