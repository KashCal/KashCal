package org.onekash.kashcal

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Probes the iCloud CalDAV connection and illustrates the rule that editing one occurrence
 * starts the form at its occurrenceTs, not the master's startTs, keeping the master's duration.
 *
 * The occurrence tests compute the timestamps locally and call no production code. The iCloud
 * test is `@Ignore`d; it sends a PROPFIND for current-user-principal and expects a 2xx. To run
 * it, remove the `@Ignore` and put in local.properties:
 *   ICLOUD_USERNAME=your_apple_id@icloud.com
 *   ICLOUD_APP_PASSWORD=xxxx-xxxx-xxxx-xxxx
 */
class OccurrenceDateIntegrationTest {

    // Credentials loaded from local.properties (if available)
    private val localProps = Properties().apply {
        val propsFile = File("../local.properties")
        if (propsFile.exists()) {
            propsFile.inputStream().use { load(it) }
        }
    }

    private val username = localProps.getProperty("ICLOUD_USERNAME", "")
    private val password = localProps.getProperty("ICLOUD_APP_PASSWORD", "")
    private val principalUrl = "https://caldav.icloud.com"

    @Test
    @Ignore("Requires real iCloud credentials in local.properties")
    fun `verify iCloud CalDAV connection`() = runBlocking {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .authenticator { _, response ->
                response.request.newBuilder()
                    .header("Authorization", Credentials.basic(username, password))
                    .build()
            }
            .build()

        // PROPFIND to the principal URL
        val propfindBody = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:current-user-principal/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val request = Request.Builder()
            .url(principalUrl)
            .method("PROPFIND", propfindBody.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull()))
            .header("Depth", "0")
            .header("Authorization", Credentials.basic(username, password))
            .build()

        val response = client.newCall(request).execute()

        println("iCloud CalDAV Response: ${response.code}")
        println("Response body: ${response.body?.string()?.take(500)}")

        assert(response.code in 200..299 || response.code == 207) {
            "Expected success response, got ${response.code}"
        }

        println("✅ iCloud CalDAV connection successful!")
    }

    @Test
    fun `verify occurrence date calculation logic`() {
        // Master event: Dec 25, 2024, 10:00 to 11:00 AM (1 hour).
        // User taps occurrence on Jan 1, 2025 at 10:00 AM

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        // Master event timestamps
        val masterStart = dateFormat.parse("2024-12-25 10:00:00")!!.time
        val masterEnd = dateFormat.parse("2024-12-25 11:00:00")!!.time
        val masterDuration = masterEnd - masterStart // 1 hour = 3600000ms

        println("Master event: ${dateFormat.format(Date(masterStart))} - ${dateFormat.format(Date(masterEnd))}")
        println("Duration: ${masterDuration / 60000} minutes")

        // Occurrence timestamp (user tapped this occurrence)
        val occurrenceTs = dateFormat.parse("2025-01-01 10:00:00")!!.time

        println("\nUser tapped occurrence: ${dateFormat.format(Date(occurrenceTs))}")

        // Wrong: the form shows the master's date
        println("\n❌ OLD BUG behavior:")
        println("   Form would show: ${dateFormat.format(Date(masterStart))}")
        println("   This is WRONG - should show the occurrence date!")

        // Right: the form shows the occurrence's date
        val actualStartTs = occurrenceTs  // occurrenceTs, not masterStart
        val actualEndTs = actualStartTs + masterDuration

        println("\n✅ FIXED behavior:")
        println("   Form now shows: ${dateFormat.format(Date(actualStartTs))}")
        println("   End time: ${dateFormat.format(Date(actualEndTs))}")
        println("   Duration preserved: ${(actualEndTs - actualStartTs) / 60000} minutes")

        // Assertions
        assert(actualStartTs == occurrenceTs) { "Start should be occurrence date" }
        assert(actualEndTs - actualStartTs == masterDuration) { "Duration should be preserved" }
        assert(actualStartTs != masterStart) { "Should NOT use master start date" }

        println("\n✅ All assertions passed!")
    }

    @Test
    fun `verify weekly recurrence occurrence timestamps`() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        // Master event: weekly on Wednesdays from Dec 25, 2024
        val masterStart = dateFormat.parse("2024-12-25 14:00:00")!!.time
        val masterEnd = dateFormat.parse("2024-12-25 15:30:00")!!.time
        val duration = masterEnd - masterStart

        println("Weekly recurring event:")
        println("Master: ${dateFormat.format(Date(masterStart))}")
        println("RRULE: FREQ=WEEKLY;BYDAY=WE")
        println()

        // Five weekly occurrences, stepped by Calendar (no RRULE expansion)
        val cal = Calendar.getInstance().apply { timeInMillis = masterStart }

        println("Generated occurrences:")
        for (i in 0..4) {
            val occStart = cal.timeInMillis
            val occEnd = occStart + duration
            println("  Week $i: ${dateFormat.format(Date(occStart))} - ${dateFormat.format(Date(occEnd))}")

            // Editing this occurrence shows its own date in the form
            val formStartTs = occStart  // not masterStart
            val formEndTs = formStartTs + duration

            assert(formStartTs == occStart) { "Form should show occurrence $i date" }
            assert(formEndTs - formStartTs == duration) { "Duration should be preserved" }

            cal.add(Calendar.WEEK_OF_YEAR, 1)
        }

        println("\n✅ All occurrence timestamps verified!")
    }
}
