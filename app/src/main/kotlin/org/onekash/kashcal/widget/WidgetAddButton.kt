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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.LocalContext
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R

/**
 * Filled, FAB-styled "add event" button for widget headers.
 *
 * A rounded-square (Material 3 FAB shape) chip filled with [WidgetTheme.onHeaderBackground] and an
 * add glyph tinted in the header tone, so it contrasts against the primaryContainer header it sits
 * on (a `primary` fill blends into that header) while still being accent-derived. The plain glyph
 * previously used a hardcoded blue in the drawable and ignored the theme.
 *
 * Glance has no elevation/shadow modifier, so a real Material FAB isn't possible. To still read
 * as "raised", a soft-shadow drawable ([R.drawable.widget_fab_shadow], corners matching the fill)
 * sits behind a slightly larger box — the accent fill stays a live [ColorProvider] (so it follows
 * the seed), and only the fixed shadow comes from the drawable.
 */
@Composable
fun WidgetAddButton() {
    Box(
        modifier = GlanceModifier
            .size(40.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_CREATE_EVENT
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Shadow layer (fixed drawable) — slightly larger than the fill so it peeks out below.
        Box(
            modifier = GlanceModifier
                .size(34.dp)
                .background(ImageProvider(R.drawable.widget_fab_shadow)),
            contentAlignment = Alignment.Center
        ) {
            // The button sits on the primaryContainer header, so a `primary` fill would blend into
            // it (≈1.4:1). Fill with onHeaderBackground (onPrimaryContainer) and draw the glyph in
            // the header tone: a contrasting chip (≥4.5:1 for every seed) that reads as raised.
            Box(
                modifier = GlanceModifier
                    .size(32.dp)
                    // Rounded square (M3 FAB shape), not a full circle: radius << half the size.
                    .cornerRadius(10.dp)
                    .background(WidgetTheme.onHeaderBackground)
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_add),
                    contentDescription = LocalContext.current.getString(R.string.cd_widget_add_event),
                    colorFilter = ColorFilter.tint(WidgetTheme.headerBackground),
                    modifier = GlanceModifier.size(20.dp)
                )
            }
        }
    }
}
