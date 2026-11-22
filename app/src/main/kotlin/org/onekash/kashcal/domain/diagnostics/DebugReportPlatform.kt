package org.onekash.kashcal.domain.diagnostics

/** Reads the platform state a debug report needs: process exits, logcat, permissions, power. */
interface DebugReportPlatform {

    /** Returns the app's process exits that Android still keeps, newest first. */
    fun exitRecords(): List<ExitRecord>

    /**
     * Runs `logcat` with [args] and passes each line to [onLine], at most [maxLines] lines.
     * Android returns only this app's own lines. Throws when logcat is missing or times out.
     */
    suspend fun readLog(args: List<String>, maxLines: Int, onLine: (String) -> Unit)

    /** Returns whether each runtime permission the app uses is granted. */
    fun permissionStates(): List<PermissionState>

    /** Returns the alarm, battery and background limits Android applies to the app. */
    fun powerState(): PowerState
}

/** One process exit; [trace] is ANR trace text, a size note for a native crash, or null. */
data class ExitRecord(
    val timestampMs: Long,
    val reason: String,
    val status: Int,
    val description: String?,
    val trace: String?,
)

/** A runtime permission by its `Manifest.permission` name without the package prefix. */
data class PermissionState(val name: String, val granted: Boolean)

/** Limits that stop syncs and reminders from running on time. */
data class PowerState(
    val canScheduleExactAlarms: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val standbyBucket: String,
    val backgroundRestricted: Boolean,
)
