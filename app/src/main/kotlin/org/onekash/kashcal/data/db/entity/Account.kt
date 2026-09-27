package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Stores one account of any [AccountProvider] (local, iCloud, ICS, contacts, CalDAV) with its
 * discovery URLs and sync metadata.
 *
 * Credentials are never stored here; [org.onekash.kashcal.data.credential.UnifiedCredentialManager]
 * keeps them encrypted, keyed by [id].
 */
@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["provider", "email", "home_set_url"], unique = true),
        Index(value = ["is_enabled"])
    ]
)
data class Account(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Stored as a lowercase string ("icloud", "local"). */
    @ColumnInfo(name = "provider")
    val provider: AccountProvider,

    /** The login; also names the per-login contacts system account. */
    @ColumnInfo(name = "email")
    val email: String,

    /** Display name for UI, e.g. "Work iCloud". */
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    /** Principal URL from discovery, e.g. `https://caldav.icloud.com/123456789/principal/`. */
    @ColumnInfo(name = "principal_url")
    val principalUrl: String? = null,

    /** CalDAV calendar home set URL, e.g. `https://caldav.icloud.com/123456789/calendars/`. */
    @ColumnInfo(name = "home_set_url")
    val homeSetUrl: String? = null,

    /**
     * Unused: nothing in the app sets or reads it. Never store a password in the database.
     */
    @ColumnInfo(name = "credential_key")
    val credentialKey: String? = null,

    /** Whether this account syncs at all; contact sync also needs [contactSyncEnabled]. */
    @ColumnInfo(name = "is_enabled", defaultValue = "1")
    val isEnabled: Boolean = true,

    /** Time of the last sync attempt, successful or not. */
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long? = null,

    /** Time of the last successful sync; the account sheet shows it as "last synced". */
    @ColumnInfo(name = "last_successful_sync_at")
    val lastSuccessfulSyncAt: Long? = null,

    /**
     * Consecutive failed syncs, reset to 0 on success. The accounts screen shows a warning at
     * [org.onekash.kashcal.ui.screens.settings.SyncWarningConstants.SYNC_FAILURE_THRESHOLD]
     * failures; the account sheet shows the count.
     */
    @ColumnInfo(name = "consecutive_sync_failures", defaultValue = "0")
    val consecutiveSyncFailures: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * The CalDAV `calendar-user-address-set` (RFC 6638 §2.4.1): every CAL-ADDRESS the server
     * recognizes as this user. Populated by a two-step PROPFIND (current-user-principal, then
     * calendar-user-address-set on the principal).
     *
     * Values are stored verbatim. Servers mix `mailto:` URIs, `urn:uuid:` URIs,
     * principal-relative paths and full HTTP principal URIs in one set, so identity matching
     * canonicalizes at compare time, never at store.
     *
     * Index 0 is the primary address, emitted as ORGANIZER on invites the user creates
     * ([org.onekash.kashcal.domain.identity.effectiveAddresses]). There is no `isPrimary` flag.
     */
    @ColumnInfo(name = "calendar_user_addresses", defaultValue = "[]")
    val calendarUserAddresses: List<String> = emptyList(),

    /**
     * URL of the principal's scheduling Outbox (RFC 6638 §2.1.1 CALDAV:schedule-outbox-URL),
     * discovered by a PROPFIND on the principal. Null when not yet discovered or the server
     * advertises none; either way the app sends no scheduling messages through an outbox.
     */
    @ColumnInfo(name = "schedule_outbox_url")
    val scheduleOutboxUrl: String? = null,

    /**
     * Whether this login syncs its CardDAV contacts onto the device. Off by default: contact
     * sync is opt-in per login (settings offers it only for CardDAV-capable providers), and
     * enabling registers a contacts system account. The single source of truth for the sync
     * worker, the scheduler and settings; it goes away with the row, which a separate
     * DataStore key wouldn't.
     */
    @ColumnInfo(name = "contact_sync_enabled", defaultValue = "0")
    val contactSyncEnabled: Boolean = false
)
