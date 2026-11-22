package org.onekash.kashcal.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugReportMaskerTest {

    private fun mask(line: String) = DebugReportMasker.mask(line)

    // ==================== Emails ====================

    @Test
    fun `email becomes the log mask form`() {
        assertEquals("from joh***@***.com today", mask("from john.doe@icloud.com today"))
    }

    // ==================== URLs ====================

    @Test
    fun `https URL keeps only its scheme`() {
        assertEquals(
            "PUT https://*** returned 500",
            mask("PUT https://p42-caldav.icloud.com/123456789/calendars/home/a.ics returned 500")
        )
    }

    @Test
    fun `http and webcal URLs keep only their scheme`() {
        assertEquals("http://*** and webcal://***", mask("http://192.168.1.5:5232/user/ and webcal://example.org/feed.ics"))
    }

    @Test
    fun `principal URL loses the account number`() {
        val out = mask("principal https://caldav.icloud.com/123456789/principal/")
        assertFalse(out, out.contains("123456789"))
    }

    // ==================== Bare hosts ====================

    @Test
    fun `bare host with a listed TLD is masked`() {
        val out = mask("connecting to caldav.example.com now")
        assertFalse(out, out.contains("example"))
    }

    @Test
    fun `unresolved self-hosted name is masked`() {
        val out = mask("java.net.UnknownHostException: Unable to resolve host \"cloud.example.lan\"")
        assertFalse(out, out.contains("cloud.example.lan"))
        assertFalse(out, out.contains("example"))
        assertTrue(out, out.startsWith("java.net.UnknownHostException: Unable to resolve host \""))
    }

    @Test
    fun `host at the end of a sentence is masked`() {
        val out = mask("failed to connect to caldav.example.com.")
        assertFalse(out, out.contains("example"))
    }

    @Test
    fun `TLD match ignores case`() {
        val out = mask("host Cal.Example.COM refused")
        assertFalse(out, out.contains("Example"))
    }

    @Test
    fun `country-code host is masked`() {
        val out = mask("Unable to resolve host \"dav.example.se\"")
        assertFalse(out, out.contains("example"))
    }

    @Test
    fun `stack frames and class names stay readable`() {
        listOf(
            "\tat org.onekash.kashcal.sync.strategy.PushStrategy.push(PushStrategy.kt:426)",
            "\tat org.onekash.kashcal.network.NetworkMonitor.register(NetworkMonitor.kt:80)",
            "\tat android.media.MediaPlayer.start(MediaPlayer.java:1)",
            "\tat android.appwidget.AppWidgetManager.updateAppWidget(AppWidgetManager.java:1)",
            "\tat org.onekash.kashcal.sync.provider.caldav.CalDavClient.fetch(CalDavClient.kt:12)",
            "Caused by: java.net.SocketTimeoutException: timeout",
            "\tat java.io.FileInputStream.open(FileInputStream.java:219)",
        ).forEach { line -> assertEquals(line, mask(line)) }
    }

    @Test
    fun `hosts in connection errors are masked whatever their TLD`() {
        listOf(
            "Unable to resolve host \"nextcloud.smith.ovh\": No address associated with hostname",
            "failed to connect to cloud.smith.family/10.0.0.5 (port 443)",
            "Hostname nas.smith.box not verified",
        ).forEach { line ->
            val out = mask(line)
            assertFalse(out, out.contains("smith"))
        }
    }

    @Test
    fun `country-code and second-level hosts are masked`() {
        listOf("dav.smith.hu", "calendar.smith.com.cn", "cal.smith.co.uk").forEach { host ->
            val out = mask("sync with $host started")
            assertFalse(out, out.contains("smith"))
        }
    }

    @Test
    fun `source file names and obfuscated class names stay`() {
        listOf(
            "\tat org.onekash.kashcal.sync.strategy.PushStrategy.push(PushStrategy.kt:426)",
            "\tat p1.co\$a.invoke(SourceFile:3)",
            "Caused by: x3.ab: boom",
            "java.lang.ClassCastException: p1.in cannot be cast to java.lang.String",
        ).forEach { line -> assertEquals(line, mask(line)) }
    }

    // ==================== IP addresses ====================

    @Test
    fun `IPv4 address is masked`() {
        val out = mask("connect to 192.168.1.20 failed")
        assertFalse(out, out.contains("192.168.1.20"))
    }

    @Test
    fun `IPv6 address is masked`() {
        val out = mask("connect to 2001:db8::8a2e:370:7334 failed and fe80:0:0:0:200:f8ff:fe21:67cf")
        assertFalse(out, out.contains("2001:db8"))
        assertFalse(out, out.contains("fe21:67cf"))
    }

    @Test
    fun `IPv4-mapped IPv6 address is masked whole`() {
        val out = mask("connect to ::ffff:192.168.1.20 failed")
        assertFalse(out, out.contains("168"))
        assertFalse(out, out.contains("ffff"))
    }

    @Test
    fun `logcat timestamps and clock times stay`() {
        val line = "10-22 13:58:09.112  8121  8160 I CalDavSyncWorker: started at 13:58:09"
        assertEquals(line, mask(line))
    }

    // ==================== Scheme-less paths ====================

    @Test
    fun `long digit segments in a scheme-less path are masked`() {
        val out = mask("Deletion href matched no local event: /123456789/calendars/home/x.ics")
        assertFalse(out, out.contains("123456789"))
        assertTrue(out, out.contains("/calendars/home/x.ics"))
        val principal = mask("principal /123456789/principal/")
        assertFalse(principal, principal.contains("123456789"))
        listOf("ends at /123456789.", "json \"/123456789\\nnext\"", "list [/123456789]").forEach { line ->
            val out = mask(line)
            assertFalse(out, out.contains("123456789"))
        }
    }

    @Test
    fun `percent-encoded email in a path is masked`() {
        val out = mask("href /remote.php/dav/calendars/john.smith%40example.com/personal/")
        assertFalse(out, out.contains("john.smith"))
        assertFalse(out, out.contains("example"))
    }

    // ==================== UIDs and tokens ====================

    @Test
    fun `UUID becomes the uid mask form`() {
        assertEquals("uid 6f1c***c0d1 done", mask("uid 6f1cb0a2-3c4d-4e5f-8a9b-0c1d2e3fc0d1 done"))
    }

    @Test
    fun `long token-shaped value is masked`() {
        val token = "HwoQEgwAAAk4dXxz8u4Bq3cAGAEiFggCEN"
        assertEquals("sync token *** stored", mask("sync token $token stored"))
    }

    @Test
    fun `CamelCase class names with trailing digits stay`() {
        listOf(
            "android.app.Activity\$\$ExternalSyntheticLambda2@4c0d7b5",
            "\tat org.onekash.kashcal.ui.screens.AccountSettingsScreenKt2.invoke(Unknown Source:1)",
        ).forEach { line -> assertEquals(line, mask(line)) }
    }

    @Test
    fun `a bare word-shaped secret outside a class name is masked`() {
        assertEquals("passphrase ***", mask("passphrase CorrectHorseBatteryStaple2024"))
    }

    @Test
    fun `token-shaped runs that only look word-like are still masked`() {
        listOf("aBcDeFgHiJkLmNoPqRsTuVwX12", "QWERTYUIOPASDFGHJKLZXCVB12").forEach { token ->
            assertEquals("value ***", mask("value $token"))
        }
    }

    @Test
    fun `long word without digits stays`() {
        val line = "AccountSettingsScreenViewModel ready"
        assertEquals(line, mask(line))
    }

    // ==================== Credential keys ====================

    @Test
    fun `value after a credential key is masked to the end of the line`() {
        assertEquals("Authorization: ***", mask("Authorization: Bearer abc.def.ghi"))
        assertEquals("authorization=***", mask("authorization=Basic dXNlcjpwYXNz"))
        assertEquals("password: ***", mask("password: hunter2 and more"))
        assertEquals("PASSWD=***", mask("PASSWD=x"))
        assertEquals("syncToken=***", mask("syncToken=12345"))
    }

    @Test
    fun `every shape of credential value is masked to the end of the line`() {
        assertEquals(
            "Authorization: ***",
            mask("Authorization: Digest username=\"jsmith\", realm=\"x\", response=\"6629fae4\"")
        )
        assertEquals("Authorization: ***", mask("Authorization: Bearer \"abc\""))
        assertEquals("password: ***", mask("password: \"a b\""))
    }

    @Test
    fun `long credential and identifier runs do not overflow the stack`() {
        assertEquals("token=***", mask("token=" + "a\\\"".repeat(200_000)))
        mask("at x." + "Aa".repeat(200_000) + "1.run(Unknown Source:1)")
    }

    @Test
    fun `credential word without a separator stays`() {
        val line = "\"tokenAdvanced\": false,"
        assertEquals(line, mask(line))
    }

    // ==================== Kept on purpose ====================

    @Test
    fun `titles calendar names timestamps and short ids stay`() {
        listOf(
            "Dentist appointment with Dr Smith",
            "\"calendarName\": \"Work\",",
            "\"timestamp\": 1792634291000,",
            "#118  UPDATE   FAILED   retries 3",
        ).forEach { line -> assertEquals(line, mask(line)) }
    }

    // ==================== Time bound ====================

    // Backtracking shows as superlinear growth: 5x the input costing about 25x the time. Linear
    // patterns cost about 5x; the bound of 12x leaves room for GC noise on a shared host.
    @Test
    fun `adversarial lines cost linear time`() {
        val units = listOf("a1.b2@c3/d4:", "a.", "1:", "ab12", "x@y.", "/1234567", "a-b.c-d.", "a", "1", "x%40y.")
        // A first pass warms up the JIT, so the timings measure the patterns.
        units.forEach { mask(repeatTo(it, SMALL)) }
        units.forEach { unit ->
            val small = bestOf3Ms { mask(repeatTo(unit, SMALL)) }.coerceAtLeast(1)
            val large = bestOf3Ms { mask(repeatTo(unit, 5 * SMALL)) }
            assertTrue("unit '$unit': small $small ms, large $large ms", large < 12 * small)
            assertTrue("unit '$unit': large took $large ms", large < 5_000)
        }
    }

    private fun repeatTo(unit: String, size: Int) = buildString(size) { while (length < size) append(unit) }

    // The fastest of three runs, so one GC pause can't fail the bound.
    private fun bestOf3Ms(block: () -> Unit): Long = (1..3).minOf {
        val start = System.nanoTime()
        block()
        (System.nanoTime() - start) / 1_000_000
    }

    private companion object {
        const val SMALL = 256 * 1024
    }
}
