package org.onekash.kashcal.sync.session

/**
 * Identifies what triggered a sync operation.
 * Used for distinguishing foreground vs background syncs in diagnostics.
 */
enum class SyncTrigger(val displayName: String, val icon: String) {
    FOREGROUND_PULL_TO_REFRESH("Pull-to-refresh", "👆"),
    FOREGROUND_APP_OPEN("App open", "📱"),
    FOREGROUND_MANUAL("Manual sync", "🔄"),
    BACKGROUND_PERIODIC("Background", "⏰"),
    BACKGROUND_WIDGET("Widget", "📲");

    val isBackground: Boolean get() = name.startsWith("BACKGROUND")
    val isForeground: Boolean get() = name.startsWith("FOREGROUND")
}
