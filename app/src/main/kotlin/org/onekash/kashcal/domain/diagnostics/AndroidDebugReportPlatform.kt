package org.onekash.kashcal.domain.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Reads [DebugReportPlatform] state from Android system services. */
@Singleton
class AndroidDebugReportPlatform @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lineReader: ProcessLineReader,
) : DebugReportPlatform {

    override fun exitRecords(): List<ExitRecord> {
        val am = context.getSystemService(ActivityManager::class.java)
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 0).map { info ->
            ExitRecord(
                timestampMs = info.timestamp,
                reason = reasonName(info.reason),
                status = info.status,
                description = info.description,
                trace = trace(info),
            )
        }
    }

    override suspend fun readLog(args: List<String>, maxLines: Int, onLine: (String) -> Unit) {
        lineReader.read(listOf("logcat") + args, maxLines, onLine)
    }

    override fun permissionStates(): List<PermissionState> {
        val permissions = buildList {
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.WRITE_CONTACTS)
            // Below API 33 notifications need no runtime grant, so the check would read denied.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_MIN_SDK) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        return permissions.map { permission ->
            PermissionState(
                name = permission.substringAfterLast('.'),
                granted = ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    override fun powerState(): PowerState {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        return PowerState(
            canScheduleExactAlarms = alarms.canScheduleExactAlarms(),
            ignoringBatteryOptimizations = power.isIgnoringBatteryOptimizations(context.packageName),
            standbyBucket = bucketName(usage.appStandbyBucket),
            backgroundRestricted = am.isBackgroundRestricted,
        )
    }

    // An ANR trace is text. A native-crash trace is a binary protobuf tombstone, so only its
    // size goes into the report. An unreadable trace costs only its own record.
    private fun trace(info: ApplicationExitInfo): String? = try {
        info.traceInputStream?.use {
            when (info.reason) {
                ApplicationExitInfo.REASON_ANR -> readTextPrefix(it)
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "${countBytes(it)} bytes, binary tombstone, not included"
                else -> null
            }
        }
    } catch (e: IOException) {
        "trace unreadable (${e.javaClass.simpleName})"
    }

    // InputStream.readNBytes needs API 33; the app runs from 31.
    private fun readTextPrefix(stream: InputStream): String {
        val bytes = ByteArray(MAX_TRACE_BYTES)
        var filled = 0
        while (filled < bytes.size) {
            val n = stream.read(bytes, filled, bytes.size - filled)
            if (n < 0) break
            filled += n
        }
        return String(bytes, 0, filled, Charsets.UTF_8)
    }

    private fun countBytes(stream: InputStream): Long {
        val buffer = ByteArray(8_192)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) return total
            total += n
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        REASON_FREEZER -> "FREEZER"
        REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    private fun bucketName(bucket: Int): String = when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
        STANDBY_BUCKET_EXEMPTED -> "EXEMPTED"
        else -> "BUCKET_$bucket"
    }

    companion object {
        /** Largest ANR trace prefix the report includes. */
        const val MAX_TRACE_BYTES = 64 * 1024

        private const val LOCAL_NETWORK_MIN_SDK = 37

        // The bucket of an app exempt from battery optimisation; the SDK keeps the constant hidden.
        private const val STANDBY_BUCKET_EXEMPTED = 5

        // Reason codes added after API 31, the app's minimum.
        private const val REASON_FREEZER = 14
        private const val REASON_PACKAGE_STATE_CHANGE = 15
        private const val REASON_PACKAGE_UPDATED = 16
    }
}
