package org.onekash.kashcal.domain.backup

import kotlinx.serialization.json.Json

/**
 * JSON codec used for reading and writing KashCal backup files.
 *
 * - prettyPrint: human-readable output for users who open the file.
 * - ignoreUnknownKeys: forward-compatible with backups that carry extra fields
 *   from newer app versions (as long as file_format_version is still supported).
 * - encodeDefaults: emit every envelope field so file_format_version is always
 *   present in the output even when construction would leave it at the default.
 * - classDiscriminator "type": matches the @SerialName tags on
 *   BackupPreferenceValue subclasses (bool / int / long / string / string_set).
 */
val BackupJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}
