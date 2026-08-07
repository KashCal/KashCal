package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * CalDAV account entity.
 *
 * Stores account credentials and sync metadata for CalDAV providers (iCloud, local, etc.).
 * Credentials are stored securely via Android Keystore (referenced by credentialKey).
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

    /**
     * Provider type for this account.
     * Stored as lowercase string in database (e.g., "icloud", "local").
     */
    @ColumnInfo(name = "provider")
    val provider: AccountProvider,

    /**
     * User email associated with this account.
     */
    @ColumnInfo(name = "email")
    val email: String,

    /**
     * Display name for UI (e.g., "Work iCloud", "Personal").
     */
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    /**
     * CalDAV principal URL discovered during account setup.
     * Example: https://caldav.icloud.com/123456789/principal/
     */
    @ColumnInfo(name = "principal_url")
    val principalUrl: String? = null,

    /**
     * CalDAV calendar home set URL.
     * Example: https://caldav.icloud.com/123456789/calendars/
     */
    @ColumnInfo(name = "home_set_url")
    val homeSetUrl: String? = null,

    /**
     * Reference key to credentials stored in Android Keystore.
     * Never store actual passwords in database.
     */
    @ColumnInfo(name = "credential_key")
    val credentialKey: String? = null,

    /**
     * Whether this account is enabled for sync.
     */
    @ColumnInfo(name = "is_enabled", defaultValue = "1")
    val isEnabled: Boolean = true,

    /**
     * Timestamp of last sync attempt (success or failure).
     */
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long? = null,

    /**
     * Timestamp of last successful sync.
     * Used for sync diagnostics and "last synced X ago" UI.
     */
    @ColumnInfo(name = "last_successful_sync_at")
    val lastSuccessfulSyncAt: Long? = null,

    /**
     * Count of consecutive sync failures.
     * Used for exponential backoff decisions.
     * Reset to 0 on successful sync.
     */
    @ColumnInfo(name = "consecutive_sync_failures", defaultValue = "0")
    val consecutiveSyncFailures: Int = 0,

    /**
     * Account creation timestamp.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * CalDAV `calendar-user-address-set` per RFC 6638 §2.4.1 — the full
     * set of CAL-ADDRESS forms the server recognizes as this user.
     * Populated via two-step PROPFIND (current-user-principal then
     * calendar-user-address-set on the principal).
     *
     * Values are stored verbatim. CalDAV servers return mixed forms in
     * the same set: `mailto:` URIs, `urn:uuid:` URIs, principal-relative
     * paths, and full HTTP principal URIs may all appear together.
     * Identity matching canonicalizes at compare time, never at store.
     *
     * Convention: `addresses[0]` is the primary address used as ORGANIZER
     * when the user creates an invite. No `isPrimary` flag.
     */
    @ColumnInfo(name = "calendar_user_addresses", defaultValue = "[]")
    val calendarUserAddresses: List<String> = emptyList(),

    /**
     * URL of this principal's scheduling Outbox collection
     * (RFC 6638 §2.1.1 CALDAV:schedule-outbox-URL), discovered by a PROPFIND
     * on the principal. Null means either not yet discovered or the server
     * does not advertise an outbox — in both cases the account is not enabled
     * for client-side sending of scheduling messages.
     */
    @ColumnInfo(name = "schedule_outbox_url")
    val scheduleOutboxUrl: String? = null,

    /**
     * Whether this login syncs its CardDAV contacts onto the device. Off by
     * default: contact sync is opt-in per login (only CardDAV-capable providers
     * can turn it on), and enrolling registers a dedicated contacts system
     * account. Per-account state that FK-cascades away when the account is
     * deleted — the single source of truth the sync worker and settings both
     * read, rather than a separate DataStore key that could drift from the row.
     */
    @ColumnInfo(name = "contact_sync_enabled", defaultValue = "0")
    val contactSyncEnabled: Boolean = false
)
