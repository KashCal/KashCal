package org.onekash.kashcal.domain.diagnostics

import kotlinx.coroutines.awaitCancellation

/** In-memory [DebugReportPlatform]; set a field to a failure to make that read throw. */
class FakeDebugReportPlatform : DebugReportPlatform {

    var exits: List<ExitRecord> = emptyList()
    var exitFailure: Exception? = null

    /** Lines returned for each `logcat` call, keyed by whether the args name the crash buffer. */
    var appLogLines: List<String> = emptyList()
    var crashLogLines: List<String> = emptyList()
    var logFailure: Exception? = null

    /** When true, the app-log read suspends until cancelled, to test cancellation mid-section. */
    var appLogHangs: Boolean = false

    var permissions: List<PermissionState> = listOf(PermissionState("READ_CALENDAR", true))
    var power: PowerState = PowerState(
        canScheduleExactAlarms = true,
        ignoringBatteryOptimizations = false,
        standbyBucket = "ACTIVE",
        backgroundRestricted = false,
    )

    val logCalls = mutableListOf<List<String>>()

    override fun exitRecords(): List<ExitRecord> {
        exitFailure?.let { throw it }
        return exits
    }

    override suspend fun readLog(args: List<String>, maxLines: Int, onLine: (String) -> Unit) {
        logCalls += args
        logFailure?.let { throw it }
        val crash = args.contains("crash")
        if (!crash && appLogHangs) awaitCancellation()
        (if (crash) crashLogLines else appLogLines).take(maxLines).forEach(onLine)
    }

    override fun permissionStates(): List<PermissionState> = permissions

    override fun powerState(): PowerState = power
}
