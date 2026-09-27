package org.onekash.kashcal.domain.backup

/**
 * Backup file format version this build writes and the newest it accepts.
 *
 * Bump it when the envelope schema changes in a way that requires a migration.
 */
const val BACKUP_FILE_FORMAT_VERSION: Int = 1

/**
 * Maximum accepted backup size, checked against the file's text length in characters. Real
 * backups are a few kilobytes; anything larger is almost certainly the wrong file or a
 * malicious input.
 */
const val MAX_BACKUP_FILE_BYTES: Long = 10L * 1024 * 1024
