package org.onekash.kashcal.domain.backup

/**
 * Current supported backup file format version.
 *
 * Bump when the envelope schema changes in a way that requires a migration.
 */
const val BACKUP_FILE_FORMAT_VERSION: Int = 1

/**
 * Maximum accepted backup file size in bytes. A defensive upper bound — real backups are
 * a few kilobytes. Anything larger is almost certainly the wrong file or a malicious input.
 */
const val MAX_BACKUP_FILE_BYTES: Long = 10L * 1024 * 1024
