package org.onekash.kashcal.sync.session

import kotlinx.serialization.Serializable

/**
 * Identifies what started a sync. Sync History and its export show [icon]; nothing reads
 * [displayName], [isBackground] or [isForeground] today.
 */
@Serializable
enum class SyncTrigger(val displayName: String, val icon: String) {
    FOREGROUND_PULL_TO_REFRESH("Pull-to-refresh", "👆"),
    FOREGROUND_APP_OPEN("App open", "📱"),
    FOREGROUND_MANUAL("Manual sync", "🔄"),
    BACKGROUND_PERIODIC("Background", "⏰"),
    BACKGROUND_WIDGET("Widget", "📲");

    val isBackground: Boolean get() = name.startsWith("BACKGROUND")
    val isForeground: Boolean get() = name.startsWith("FOREGROUND")
}
