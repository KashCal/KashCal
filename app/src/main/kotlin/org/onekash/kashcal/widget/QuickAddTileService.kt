package org.onekash.kashcal.widget

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.onekash.kashcal.R

/**
 * Quick Settings tile for fast event capture. Tapping it opens KashCal's Quick
 * Add flow from anywhere (including over other apps), reusing the existing
 * widget/shortcut launch route ([buildQuickAddCaptureIntent]).
 *
 * This is a stateless launch tile, not a toggle: it has no persisted on/off
 * state, so it deliberately omits the sample's DataStore + ACTIVE_TILE /
 * TOGGLEABLE_TILE machinery. [onStartListening] just refreshes a static
 * label + icon.
 */
class QuickAddTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(R.string.qs_tile_label)
            // Standard full-frame calendar symbol (matches the system alarm/airplane
            // tiles' visual weight). The launcher's monochrome "31" mark is sized for
            // the adaptive-icon safe zone, so it renders tiny + illegible in a tile.
            icon = Icon.createWithResource(this@QuickAddTileService, R.drawable.ic_qs_tile_calendar)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = buildQuickAddCaptureIntent(this)
        // CWE-927: explicit target + immutable PendingIntent.
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: the Intent overload is deprecated; use the PendingIntent one.
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
