# Data Layer

The data layer provides persistent storage using Room database with encrypted credential storage via Android Keystore.

## Database Schema

KashCal uses Room with 9 entities across 12 migrations (current version: 12).

### Entity Relationship Diagram

```
┌─────────────┐     1:N     ┌─────────────┐     1:N     ┌─────────────┐
│   Account   │────────────▶│  Calendar   │────────────▶│    Event    │
└─────────────┘             └─────────────┘             └──────┬──────┘
                                   │                          │
                                   │ 1:N                      │ 1:N
                                   ▼                          ▼
                            ┌─────────────┐            ┌─────────────┐
                            │IcsSubscript.│            │ Occurrence  │
                            └─────────────┘            └─────────────┘
                                                              │
                                                              │ link
                                                              ▼
                                                       ┌─────────────┐
                                                       │ Scheduled   │
                                                       │  Reminder   │
                                                       └─────────────┘
```

### Core Entities

#### Event (73 columns)

The primary entity storing calendar events with full RFC 5545 support.

| Section | Fields |
|---------|--------|
| Identity | `id`, `uid`, `importId`, `calendarId` |
| Content | `title`, `location`, `description`, `startTs`, `endTs`, `timezone`, `isAllDay` |
| Recurrence | `rrule`, `rdate`, `exdate`, `duration` |
| Exception | `originalEventId`, `originalInstanceTime`, `originalSyncId` |
| Reminders | `reminders` (JSON), `alarmCount`, `rawIcal` |
| RFC 5545/7986 | `priority`, `geoLat`, `geoLon`, `color`, `url`, `categories` |
| Sync | `caldavUrl`, `etag`, `sequence`, `syncStatus` |
| Timestamps | `dtstamp`, `localModifiedAt`, `serverModifiedAt` |

**Key indices:**

- `(calendar_id, uid, original_instance_time)` - Exception lookup
- `(original_event_id, original_instance_time)` - Unique exception
- `caldav_url` - Server URL lookup
- `import_id` - ICS import deduplication

#### Occurrence

Materialized RRULE expansions for O(1) range queries.

```kotlin
@Entity(
    indices = [
        Index("event_id"),
        Index("calendar_id"),
        Index("start_day", "end_day"),
        Index(value = ["event_id", "start_ts"], unique = true)
    ]
)
data class Occurrence(
    val eventId: Long,           // Master event
    val calendarId: Long,        // Denormalized for performance
    val startTs: Long,
    val endTs: Long,
    val startDay: Int,           // YYYYMMDD format
    val endDay: Int,
    val isCancelled: Boolean,    // EXDATE flag
    val exceptionEventId: Long?  // Links to exception event (Model B)
)
```

**Why materialized?**

- Calendar views need O(1) range queries
- RRULE expansion on-the-fly is O(n) per event
- FTS search needs indexed occurrence times

#### PendingOperation

Offline-first sync queue with retry logic.

```kotlin
data class PendingOperation(
    val eventId: Long,
    val operation: String,       // CREATE, UPDATE, DELETE, MOVE
    val status: String,          // PENDING, IN_PROGRESS, FAILED
    val retryCount: Int,
    val maxRetries: Int = 10,    // ~13.5h total backoff
    val nextRetryAt: Long?,
    val targetUrl: String?,      // Captured at queue time (critical!)
    val targetCalendarId: Long?,
    val sourceCalendarId: Long?, // For cross-account moves
    val movePhase: Int = 0,      // 0=DELETE, 1=CREATE
    val lifetimeResetAt: Long,   // 30-day lifetime
    val failedAt: Long?          // 24h auto-reset trigger
)
```

**Retry lifecycle:**

- Exponential backoff: 30s → 5h cap (10 retries)
- Auto-reset after 24h in FAILED state
- Abandoned after 30 days without user interaction

### Sync Status Flow

```
New Event (CalDAV) → PENDING_CREATE → SYNCED
                           ↓
                    (push fails)
                           ↓
                    retry with backoff
                           ↓
                    SYNCED (success)

Existing Event → PENDING_UPDATE → SYNCED

Delete Event → PENDING_DELETE → (removed from DB)

Local Only → SYNCED (never synced)
```

## Credential Storage

Credentials are encrypted using Android Keystore with AES-256-GCM.

### UnifiedCredentialManager

```kotlin
// Storage format: account_{id}_{field}
// File: unified_credentials (EncryptedSharedPreferences)

data class AccountCredentials(
    val username: String,
    val password: String,
    val serverUrl: String,
    val trustInsecure: Boolean = false,
    val principalUrl: String? = null,
    val calendarHomeSet: String? = null
)
```

**Security features:**

- Keys encrypted with AES256-SIV
- Values encrypted with AES256-GCM
- Master key stored in Android Keystore
- Excluded from Android backup (`backup_rules.xml`)
- Credentials masked in logs (first 3 chars only)

## Repository Layer

Repositories provide the single source of truth for Account and Calendar operations.

### AccountRepository

```kotlin
interface AccountRepository {
    // Reactive queries
    fun getAllAccountsFlow(): Flow<List<Account>>

    // One-shot queries
    suspend fun getAccountById(id: Long): Account?

    // Write operations
    suspend fun createAccount(account: Account): Long
    suspend fun deleteAccount(accountId: Long)  // Full cleanup

    // Credentials (delegated to CredentialManager)
    suspend fun saveCredentials(accountId: Long, credentials: AccountCredentials): Boolean
    suspend fun getCredentials(accountId: Long): AccountCredentials?
}
```

**deleteAccount() cleanup order:**

1. Cancel WorkManager sync jobs
2. Cancel reminders (need event IDs before cascade)
3. Delete pending operations
4. Delete credentials
5. Cascade delete via Room FK

### CalendarRepository

```kotlin
interface CalendarRepository {
    fun getVisibleCalendarsFlow(): Flow<List<Calendar>>
    suspend fun setVisibility(calendarId: Long, visible: Boolean)
    suspend fun updateSyncToken(calendarId: Long, syncToken: String?, ctag: String?)
}
```

## DAOs

### Key DAO Methods

| DAO | Method | Purpose |
|-----|--------|---------|
| EventsDao | `getExceptionByUidAndInstanceTime()` | RFC 5545 exception lookup |
| EventsDao | `search()` | FTS4 full-text search |
| OccurrencesDao | `linkException()` | Model B exception linking |
| OccurrencesDao | `getOccurrencesWithEventsInRange()` | JOIN for calendar views |
| PendingOperationsDao | `getReadyOperations()` | Queue processing |
| PendingOperationsDao | `autoResetOldFailed()` | 24h retry reset |

### FTS4 Search

KashCal uses FTS4 (not FTS5) for Room integration:

```kotlin
@Fts4(contentEntity = Event::class)
@Entity(tableName = "events_fts")
data class EventFts(
    val title: String,
    val location: String?,
    val description: String?
)
```

**Search query:**

```sql
SELECT events.* FROM events
JOIN events_fts ON events.id = events_fts.rowid
WHERE events_fts MATCH :query
LIMIT 100
```

## Migrations

| Version | Change |
|---------|--------|
| 1→2 | Add ics_subscriptions table |
| 2→3 | Add scheduled_reminders table |
| 4→5 | Add pending_operations.target_url, target_calendar_id |
| 5→6 | Add pending_operations.move_phase |
| 6→7 | Add Event.raw_ical, import_id, alarm_count |
| 7→8 | Add RFC 5545/7986 fields (priority, geo, color, url, categories) |
| 8→9 | Add composite exception lookup index |
| 9→10 | Add unique (event_id, start_ts) on occurrences |
| 10→11 | Add pending_operations.lifetime_reset_at, failed_at |
| 11→12 | Add pending_operations.sourceCalendarId |

All migrations are idempotent and check existence before creating.
