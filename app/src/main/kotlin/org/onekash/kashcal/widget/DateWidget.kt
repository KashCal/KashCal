package org.onekash.kashcal.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent

/**
 * Date widget showing today's date in an icon-like format.
 *
 * Designed to look like an adaptive app icon but displays the current date.
 * Users can use this instead of the app icon for quick access with live date display.
 *
 * Features:
 * - 1x1 cell size (icon-like)
 * - Shows day name (e.g., "SUN") and date number (e.g., "19")
 * - Circular background matching system theme
 * - Tap anywhere opens app at today's view
 *
 * Updates:
 * - At midnight (new day)
 * - On event changes (via WidgetUpdateManager)
 * - Periodically (every 30 minutes)
 */
class DateWidget : GlanceAppWidget() {

    /**
     * Use exact size mode for consistent rendering.
     */
    override val sizeMode = SizeMode.Exact

    /**
     * Provide widget content.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                DateWidgetContent()
            }
        }
    }
}
