package org.onekash.kashcal.domain.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object BackupFilename {

    private val LOCAL_FILENAME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm")

    fun generate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = instant.atZone(zone).format(LOCAL_FILENAME_FORMATTER)
        return "kashcal-backup-$stamp.json"
    }

    fun generateIsoUtc(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))
}
