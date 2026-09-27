package org.onekash.kashcal.domain.backup

import kotlinx.serialization.json.Json

/**
 * JSON codec for reading and writing KashCal backup files.
 *
 * - prettyPrint: human-readable output for users who open the file.
 * - ignoreUnknownKeys: accepts backups with extra fields from newer app versions, as long as
 *   file_format_version is still supported.
 * - encodeDefaults: writes defaulted fields too (an empty `categories`, a null `username`), so
 *   every field is present in the file.
 * - classDiscriminator "type": matches the @SerialName tags on the [BackupPreferenceValue]
 *   subclasses (bool / int / long / string / string_set).
 */
val BackupJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}
