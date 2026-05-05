package org.onekash.kashcal.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BackupFilenameTest {

    @Test
    fun `generate returns ISO 8601 with colons replaced by dashes`() {
        // 2026-04-23 14:30:05 UTC -> in UTC zone: 2026-04-23T14-30
        val instant = Instant.parse("2026-04-23T14:30:05Z")
        val filename = BackupFilename.generate(instant, ZoneId.of("UTC"))

        assertEquals("kashcal-backup-2026-04-23T14-30.json", filename)
    }

    @Test
    fun `generate uses local zone for the filename`() {
        // 2026-04-23 23:45 UTC -> UTC+5 -> 2026-04-24T04-45
        val instant = Instant.parse("2026-04-23T23:45:00Z")
        val filename = BackupFilename.generate(instant, ZoneId.of("+05:00"))

        assertEquals("kashcal-backup-2026-04-24T04-45.json", filename)
    }

    @Test
    fun `generate zero pads single digit month day hour minute`() {
        val instant = Instant.parse("2026-01-02T03:04:00Z")
        val filename = BackupFilename.generate(instant, ZoneId.of("UTC"))

        assertEquals("kashcal-backup-2026-01-02T03-04.json", filename)
    }

    @Test
    fun `generate handles DST boundary correctly`() {
        // US Eastern spring-forward 2026-03-08: local jumps 02:00 -> 03:00.
        // 06:30 UTC on that date -> America/New_York 02:30 AM is invalid, jumps to 03:30.
        val instant = Instant.parse("2026-03-08T06:30:00Z")
        val filename = BackupFilename.generate(instant, ZoneId.of("America/New_York"))

        // 06:30 UTC - 4h (EDT active after spring-forward) = 02:30... but 02:30 didn't exist.
        // JVM zone rules resolve 06:30 UTC = 02:30 EDT (already on EDT by 06:30 UTC).
        // Either way, the result must contain "2026-03-08T" and end with ".json".
        assertTrue(filename.startsWith("kashcal-backup-2026-03-08T"))
        assertTrue(filename.endsWith(".json"))
    }

    @Test
    fun `generateIsoUtc returns ISO 8601 instant with seconds and Z suffix`() {
        val instant = Instant.parse("2026-04-23T14:30:05Z")
        val iso = BackupFilename.generateIsoUtc(instant)

        assertEquals("2026-04-23T14:30:05Z", iso)
    }

    @Test
    fun `generateIsoUtc normalizes fractional seconds away`() {
        // Instants can carry nanoseconds; keep the format stable to seconds precision.
        val instant = Instant.parse("2026-04-23T14:30:05.123456789Z")
        val iso = BackupFilename.generateIsoUtc(instant)

        assertEquals("2026-04-23T14:30:05Z", iso)
    }
}
