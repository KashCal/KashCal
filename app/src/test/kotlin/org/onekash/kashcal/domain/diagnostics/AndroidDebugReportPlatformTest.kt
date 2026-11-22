package org.onekash.kashcal.domain.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidDebugReportPlatformTest {

    private lateinit var context: Context
    private lateinit var platform: AndroidDebugReportPlatform

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        platform = AndroidDebugReportPlatform(context, ProcessLineReader(timeoutMs = 1_000))
    }

    private fun addExit(reason: Int, timestamp: Long, description: String?, trace: ByteArray? = null) {
        val builder = ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason)
            .setTimestamp(timestamp)
            .setStatus(EXIT_STATUS)
            .setDescription(description)
            .setProcessName(context.packageName)
        if (trace != null) builder.setTraceInputStream(trace.inputStream())
        val am = context.getSystemService(ActivityManager::class.java)
        shadowOf(am).addApplicationExitInfo(builder.build())
    }

    @Test
    fun `exit records carry time, reason name, status and description`() {
        addExit(ApplicationExitInfo.REASON_CRASH, 1_000L, "crash")
        addExit(ApplicationExitInfo.REASON_LOW_MEMORY, 2_000L, null)

        val records = platform.exitRecords()

        val crash = records.single { it.timestampMs == 1_000L }
        assertEquals("CRASH", crash.reason)
        assertEquals("crash", crash.description)
        assertEquals(EXIT_STATUS, crash.status)
        assertNull(crash.trace)
        assertEquals("LOW_MEMORY", records.single { it.timestampMs == 2_000L }.reason)
    }

    @Test
    fun `ANR trace is text cut at 64 KB`() {
        val trace = "\"main\" prio=5 tid=1 Blocked\n".repeat(4_000).toByteArray()
        addExit(ApplicationExitInfo.REASON_ANR, 3_000L, "anr", trace)

        val text = platform.exitRecords().single().trace!!

        assertTrue(text.startsWith("\"main\" prio=5 tid=1 Blocked"))
        assertTrue("length ${text.length}", text.length <= AndroidDebugReportPlatform.MAX_TRACE_BYTES)
        assertTrue(trace.size > AndroidDebugReportPlatform.MAX_TRACE_BYTES)
    }

    @Test
    fun `an unreadable trace costs only its own record`() {
        val broken = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("gone")
        }
        val builder = ApplicationExitInfoBuilder.newBuilder()
            .setReason(ApplicationExitInfo.REASON_ANR)
            .setTimestamp(5_000L)
            .setProcessName(context.packageName)
            .setTraceInputStream(broken)
        shadowOf(context.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(builder.build())
        addExit(ApplicationExitInfo.REASON_CRASH, 6_000L, "crash")

        val records = platform.exitRecords()

        assertEquals("trace unreadable (IOException)", records.single { it.timestampMs == 5_000L }.trace)
        assertEquals("CRASH", records.single { it.timestampMs == 6_000L }.reason)
    }

    @Test
    fun `native crash trace is reported by size only`() {
        val tombstone = ByteArray(5_000) { (it % 256).toByte() }
        addExit(ApplicationExitInfo.REASON_CRASH_NATIVE, 4_000L, null, tombstone)

        val trace = platform.exitRecords().single().trace!!

        assertEquals("5000 bytes, binary tombstone, not included", trace)
    }

    @Test
    fun `power state reads exact alarms, battery, standby bucket and background restriction`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, false)
        shadowOf(context.getSystemService(UsageStatsManager::class.java))
            .setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE)
        shadowOf(context.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)

        val power = platform.powerState()

        assertTrue(power.canScheduleExactAlarms)
        assertFalse(power.ignoringBatteryOptimizations)
        assertEquals("RARE", power.standbyBucket)
        assertTrue(power.backgroundRestricted)
    }

    @Test
    fun `the exempted standby bucket has a name`() {
        // 5 is the platform's exempted bucket, which a battery-exempt app gets; the SDK hides it.
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).setCurrentAppStandbyBucket(5)

        assertEquals("EXEMPTED", platform.powerState().standbyBucket)
    }

    @Test
    fun `permission states list calendar, contacts and notification permissions`() {
        val names = platform.permissionStates().map { it.name }
        listOf(
            "READ_CALENDAR", "WRITE_CALENDAR", "READ_CONTACTS", "WRITE_CONTACTS", "POST_NOTIFICATIONS"
        ).forEach { assertTrue("missing $it in $names", it in names) }
        // ACCESS_LOCAL_NETWORK exists only from API 37; this runs on 34, where
        // POST_NOTIFICATIONS is a runtime permission.
        assertFalse(names.contains("ACCESS_LOCAL_NETWORK"))
    }

    @Test
    fun `reading a log without logcat throws`() {
        // The JVM test host has no logcat binary; the exporter writes such a section as
        // unavailable.
        val result = runCatching {
            kotlinx.coroutines.runBlocking { platform.readLog(listOf("-d"), 10) { } }
        }
        assertTrue(result.isFailure)
    }

    private companion object {
        // Distinct from every reason code, so a status read from the wrong field fails.
        const val EXIT_STATUS = 137
    }
}
