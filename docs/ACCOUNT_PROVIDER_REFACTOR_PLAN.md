# AccountProvider Enum Refactor: Full Analysis

## 1. Current State (After Phase 2)

### Data Model
```
Account.provider: AccountProvider enum = LOCAL | ICLOUD | ICS | CONTACTS | CALDAV
Calendar.accountId: Long (FK to Account)
Calendar.caldavUrl: String
```

### Detection Logic (Unified)
```kotlin
// Single detection pattern via enum:
when (account.provider) {
    AccountProvider.LOCAL -> ...
    AccountProvider.ICLOUD -> ...
    AccountProvider.ICS -> ...
    AccountProvider.CONTACTS -> ...
    AccountProvider.CALDAV -> ...
}
// Compiler enforces exhaustive handling
```

### Multi-Account Support Status

| Provider | Current | After Refactor | Reason |
|----------|---------|----------------|--------|
| LOCAL | 1 | 1 | By design (offline-first default) |
| ICLOUD | 1 | 1 | `ICloudAuthManager` uses single SharedPreferences (future: multi) |
| ICS | ∞ | ∞ | Each subscription is independent |
| CALDAV | N/A | ∞ | New - account-keyed credentials from start |

**Note:** Multi-iCloud support is a future enhancement. When needed, refactor `ICloudAuthManager` to use account-keyed credential storage like `CalDavCredentialManager`.

---

## 2. Architecture Decision: All Room

**All calendars and events are stored in Room.** No Android CalendarProvider.

```
Room DB:
  Account (provider: AccountProvider enum)
      └── Calendar (accountId)
             └── Event (calendarId, color?, url?, categories?)
                    └── Occurrence (eventId)

CalendarProvider: NOT USED

Detection: Unified (account.provider enum with exhaustive when)
```

### Why All Room?

| Factor | CalendarProvider | All Room |
|--------|------------------|----------|
| Complexity | Two storage systems | One storage system |
| Code reuse | Separate sync paths | Same sync engine |
| FTS Search | CalDAV events not indexed | All events searchable |
| Interop with other apps | Yes | No (acceptable for KashCal users) |
| Maintenance | Double the bugs | Single codebase |

### Key Insight: iCloud IS CalDAV

```
iCloud = CalDavClient + ICloudQuirks
CalDAV = CalDavClient + DefaultQuirks

Same sync engine, different quirks.
```

---

## 3. icaldav Library Integration

### Current Usage (icaldav-core v2.7.2)

KashCal uses `icaldav-core` for ICS parsing/generation:

```kotlin
// Currently imported from icaldav-core
import org.onekash.icaldav.parser.ICalParser      // Parse ICS
import org.onekash.icaldav.parser.ICalGenerator   // Generate ICS
import org.onekash.icaldav.model.ICalEvent        // Event model
import org.onekash.icaldav.model.ICalAlarm        // Alarm model
import org.onekash.icaldav.model.ParseResult      // Parse result
import org.onekash.icaldav.util.DurationUtils     // Duration parsing
```

### Available in icaldav-client (NOT currently used)

```kotlin
// icaldav-client provides (can add dependency):
import org.onekash.icaldav.quirks.DefaultQuirks   // Generic CalDAV quirks
import org.onekash.icaldav.quirks.ICloudQuirks    // iCloud quirks
import org.onekash.icaldav.client.CalDavClient    // High-level CalDAV client
import org.onekash.icaldav.discovery.CalDavDiscovery  // Server discovery
import org.onekash.icaldav.client.IcsSubscriptionClient  // ICS feed fetching
```

### DefaultQuirks Fix (v2.7.2)

Fixed `hasCalendarResourceType()` to handle any namespace prefix:

```kotlin
// Before (missed Nextcloud's <cal:calendar/>)
return resourceTypeContent.contains("<calendar") &&
    (resourceTypeContent.contains("caldav") || resourceTypeContent.contains("<c:calendar"))

// After (handles any prefix: c:, cal:, CAL:, etc.)
val calendarRegex = Regex("""<(?:\w+:)?calendar[\s/>]""", RegexOption.IGNORE_CASE)
return calendarRegex.containsMatchIn(resourceTypeContent)
```

Also fixed `extractCalendarColor()` to handle `x1:calendar-color` (Nextcloud format).

### ICalEvent Model Coverage

The `ICalEvent` model in icaldav-core already has:
- ✅ `categories: List<String>` - RFC 5545 CATEGORIES
- ✅ `color: String?` - RFC 7986 COLOR
- ✅ `url: String?` - RFC 5545 URL

**Missing (but preserved in `rawProperties`):**
- ❌ `priority: Int` - RFC 5545 PRIORITY (parsed for VTODO, not VEVENT)
- ❌ `geo: Pair<Double, Double>?` - RFC 5545 GEO (parsed for VTODO, not VEVENT)

**Approach for PRIORITY/GEO:**
1. Extract from `rawProperties` in `ICalEventMapper.kt`
2. Write via `IcsPatcher.kt` (already preserves rawProperties)
3. Future: Add to `ICalEvent` model in icaldav-core v2.8.0

### Composite Build (No Dependency Change Needed)

KashCal uses composite build - icaldav is included directly from `../icaldav`:

```kotlin
// settings.gradle.kts
if (file("../icaldav").exists()) {
    includeBuild("../icaldav") {
        dependencySubstitution {
            substitute(module("org.onekash:icaldav-core")).using(project(":icaldav-core"))
        }
    }
}
```

**For CalDAV provider support**, add `icaldav-client` to substitution:

```kotlin
// settings.gradle.kts (updated)
if (file("../icaldav").exists()) {
    includeBuild("../icaldav") {
        dependencySubstitution {
            substitute(module("org.onekash:icaldav-core")).using(project(":icaldav-core"))
            substitute(module("org.onekash:icaldav-client")).using(project(":icaldav-client"))
        }
    }
}

// app/build.gradle.kts
implementation(libs.icaldav.client)  // Add to dependencies
```

### Why Use icaldav-client's DefaultQuirks?

| Aspect | KashCal's ICloudQuirks | icaldav's DefaultQuirks |
|--------|------------------------|------------------------|
| Maintained in | KashCal repo | icaldav repo (shared) |
| Tested with | iCloud | Nextcloud, Baikal, Radicale |
| Namespace handling | iCloud-specific | Generic (any prefix) |
| Updates | Manual copy | Automatic via composite build |

**Decision:** Use `DefaultQuirks` from icaldav-client for generic CalDAV, keep `ICloudQuirks` in KashCal for iCloud-specific handling.

---

## 4. Proposed Changes

### AccountProvider Enum (Ideal Approach - Enum Column)

**Store enum directly in Account entity with TypeConverter for full type safety.**

```kotlin
// domain/model/AccountProvider.kt
enum class AccountProvider(
    val displayName: String,
    val requiresNetwork: Boolean
) {
    LOCAL(
        displayName = "Local",
        requiresNetwork = false
    ),
    ICLOUD(
        displayName = "iCloud",
        requiresNetwork = true
    ),
    ICS(
        displayName = "ICS Subscription",
        requiresNetwork = true
    ),
    CALDAV(
        displayName = "CalDAV",
        requiresNetwork = true
    );

    /** Whether this provider syncs to a remote server */
    val requiresSync: Boolean get() = this != LOCAL

    /** Whether this provider is pull-only (no push) */
    val pullOnly: Boolean get() = this == ICS

    /** Whether this provider uses CalDAV protocol */
    val supportsCalDAV: Boolean get() = this == ICLOUD || this == CALDAV

    companion object {
        /** Convert database string to enum */
        fun fromString(value: String): AccountProvider = when (value.lowercase()) {
            "local" -> LOCAL
            "icloud" -> ICLOUD
            "ics" -> ICS
            "caldav" -> CALDAV
            else -> throw IllegalArgumentException("Unknown provider: $value")
        }
    }
}
```

### Account Entity with Enum

```kotlin
// data/db/entity/Account.kt
@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["provider", "email"], unique = true),
        Index(value = ["is_enabled"])
    ]
)
data class Account(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "provider")
    val provider: AccountProvider,  // Enum, not String

    // ... rest of fields unchanged
)
```

### TypeConverter for Room

```kotlin
// data/db/converter/Converters.kt
@TypeConverter
fun fromAccountProvider(provider: AccountProvider): String =
    provider.name.lowercase()

@TypeConverter
fun toAccountProvider(value: String): AccountProvider =
    AccountProvider.fromString(value)
```

**Why enum column (ideal) vs computed property (minimal)?**

| Aspect | Computed Property | Enum Column |
|--------|-------------------|-------------|
| Type safety | At call sites only | Everywhere |
| Null handling | `fromString()` throws | TypeConverter handles |
| IDE support | Partial | Full autocomplete |
| Exhaustive when | Manual | Compiler-enforced |
| Refactoring | Find/replace needed | Automatic |
| DB storage | Same (`"icloud"`) | Same (`"icloud"`) |
| Migration | None | TypeConverter only |

### Exhaustive When in Code

```kotlin
// EventCoordinator.kt - Compiler ensures all cases handled
suspend fun createEvent(event: Event, calendarId: Long): Event {
    val calendar = calendarsDao.getById(calendarId)
    val account = accountsDao.getById(calendar.accountId)

    return when (account.provider) {
        AccountProvider.LOCAL -> createLocalEvent(event, calendar)
        AccountProvider.ICLOUD -> createSyncedEvent(event, calendar)
        AccountProvider.ICS -> throw IllegalArgumentException("ICS calendars are read-only")
        AccountProvider.CALDAV -> createSyncedEvent(event, calendar)
    }  // Compiler error if new provider added without handling
}
```

### Schema Changes: Already Complete (v21.1.0)

RFC 5545/7986 fields and indexes were added in Migration 7→8 and 8→9.

**Current database version: 9**

Already implemented:
- ✅ `events.priority` - RFC 5545 PRIORITY
- ✅ `events.geo_lat`, `events.geo_lon` - RFC 5545 GEO
- ✅ `events.color` - RFC 7986 COLOR
- ✅ `events.url` - RFC 5545 URL
- ✅ `events.categories` - RFC 5545 CATEGORIES
- ✅ Boolean indexes (is_visible, is_enabled, enabled, is_cancelled)
- ✅ Composite index for exception lookup

### Credential Storage Architecture

```kotlin
// ICloudAuthManager.kt - UNCHANGED (single account)
class ICloudAuthManager {
    fun saveCredentials(appleId: String, appPassword: String)
    fun getCredentials(): Credentials?
}

// CalDavCredentialManager.kt - NEW (multi-account from start)
class CalDavCredentialManager {
    fun saveCredentials(accountId: Long, username: String, password: String)
    fun getCredentials(accountId: Long): Credentials?
    fun deleteCredentials(accountId: Long)
}
```

### Entity Relationships (All Room)

```
Account (id: 1, provider: LOCAL)
    └── Calendar (id: 1, accountId: 1, "Local Calendar")

Account (id: 2, provider: ICLOUD)
    ├── Calendar (id: 2, accountId: 2, "Home")
    └── Calendar (id: 3, accountId: 2, "Family")

Account (id: 3, provider: CALDAV, email: "user@nextcloud.com")
    ├── Calendar (id: 4, accountId: 3, "Work")
    └── Calendar (id: 5, accountId: 3, "Personal")

Account (id: 4, provider: CALDAV, email: "user@fastmail.com")
    └── Calendar (id: 6, accountId: 4, "Fastmail")
```

---

## 5. GitHub Issues Analysis

| Issue | Category | Schema Impact | Status |
|-------|----------|---------------|--------|
| **#14** Event colors | Feature | `Event.color: Int?` | ✅ Complete |
| **#4** More than 2 reminders | UI only | None (backend supports unlimited) | UI change only |
| **#15** Native CalDAV | Feature | None (All Room) | This refactor |
| #22 Time is wrong | Bug | None | Separate fix |
| #21 Agenda view default | UI | None | Separate feature |
| #28 Multi-month view | UI | None | Separate feature |
| #1 Localization | UI | None | Separate feature |

### Issue #14: Event Colors (RFC 7986)

✅ **Complete** - Implemented in v21.1.0

### Issue #4: More Than 2 Reminders

**Current State:**
- `Event.reminders: List<String>?` - already supports unlimited
- `Event.alarmCount: Int` - tracks total for >3 case
- `Event.rawIcal: String?` - preserves all alarms for round-trip

**Change Needed:** UI only - add 3rd reminder picker in `EventFormSheet`

---

## 6. Database Optimization Audit

### Current Indexes (Complete)

| Table | Index | Purpose |
|-------|-------|---------|
| `events` | `(calendar_id, start_ts)` | Range queries by calendar |
| `events` | `(calendar_id, import_id)` | Sync lookup |
| `events` | `sync_status` | Find pending sync |
| `events` | `(calendar_id, uid, original_instance_time)` | Exception lookup |
| `occurrences` | `(start_ts, end_ts)` | Range queries |
| `occurrences` | `start_day` | Day view queries |
| `occurrences` | `is_cancelled` | Filter cancelled |
| `scheduled_reminders` | `trigger_time` | Alarm scheduling |
| `calendars` | `is_visible` | Visibility filter |
| `accounts` | `is_enabled` | Enabled filter |
| `ics_subscriptions` | `enabled` | Enabled filter |

---

## 7. Future Schema Extensions (Not Now)

These are common CalDAV features. Schema is designed to add them later without breaking changes.

### Multi-iCloud Support (Future)

When needed, refactor `ICloudAuthManager` to use account-keyed storage:

```kotlin
// Future: ICloudAuthManager with multi-account
class ICloudAuthManager {
    fun saveCredentials(accountId: Long, appleId: String, appPassword: String)
    fun getCredentials(accountId: Long): Credentials?
    fun deleteCredentials(accountId: Long)
}
```

### Attendees Table (RFC 5545 ATTENDEE)

```kotlin
// Future: When meeting invitations are needed
@Entity(
    tableName = "attendees",
    foreignKeys = [ForeignKey(entity = Event::class, ...)]
)
data class Attendee(
    val id: Long = 0,
    val eventId: Long,           // FK → events
    val email: String,
    val name: String?,
    val role: String?,           // REQ-PARTICIPANT, OPT-PARTICIPANT
    val partstat: String?,       // ACCEPTED, DECLINED, TENTATIVE
    val rsvp: Boolean = false
)
```

### Attachments Table (RFC 5545 ATTACH)

```kotlin
// Future: When file attachments are needed
@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(entity = Event::class, ...)]
)
data class Attachment(
    val id: Long = 0,
    val eventId: Long,           // FK → events
    val uri: String?,            // Remote URL
    val localPath: String?,      // Local file path
    val mimeType: String?,
    val filename: String?,
    val size: Long?
)
```

---

## 8. Risk vs Reward

### Rewards

| Benefit | Impact |
|---------|--------|
| Single source of truth | High - no more scattered detection |
| Full type safety | High - enum throughout, compiler-enforced |
| Exhaustive when | High - compiler catches missing cases |
| Unified calendar list | High - Room has all calendars |
| Simpler routing logic | High - `when (account.provider)` everywhere |
| FTS search for all events | High - CalDAV events searchable |
| Multi-CalDAV from start | High - proper credential architecture |
| IDE autocomplete | Medium - better developer experience |

### Risks

| Risk | Severity | Likelihood | Mitigation |
|------|----------|------------|------------|
| TypeConverter bugs | Low | Low | Simple string↔enum conversion |
| CalDAV sync breaks | Medium | Low | Same engine as iCloud |
| Existing tests fail | Low | Medium | Update to use enum |

### Risk Score
```
Total Risk = Low (10%)

Why low?
- No actual schema change (same strings stored)
- TypeConverter is trivial
- Same sync engine as proven iCloud
- Enum provides compile-time safety
```

---

## 9. Routing Flow (After)

```
EventCoordinator.createEvent(event, calendarId)
         │
         ▼
    calendarDao.getById(calendarId)
         │
         ▼
    when (account.provider)          ← Enum, exhaustive
         │
    ┌────┴────┬────────┬────────┐
    ▼         ▼        ▼        ▼
  LOCAL    ICLOUD     ICS    CALDAV
    │         │        │        │
    ▼         ▼        ▼        ▼
  Room      Room     Room     Room     ← ALL ROOM
 (no sync) (queue)  (pull)  (queue)
            ICloud    ICS    Default
            Quirks         Quirks
```

---

## 10. Test Strategy

### Unit Tests

| Test | Purpose |
|------|---------|
| `AccountProviderTest` | Enum properties, fromString(), TypeConverter |
| `AccountProviderConverterTest` | Room TypeConverter round-trip |

### Integration Tests

| Test | Purpose |
|------|---------|
| `EventCoordinatorRoutingTest` | Create/Update/Delete routes by provider |
| `CalDavSyncTest` | CalDAV sync works like iCloud |
| `MultiCalDavAccountTest` | Multiple CalDAV accounts work |

### Golden Tests

```kotlin
@Test
fun `golden - create event in each provider type`() {
    val localCal = createCalendar(AccountProvider.LOCAL)
    val icloudCal = createCalendar(AccountProvider.ICLOUD)
    val caldavCal = createCalendar(AccountProvider.CALDAV)

    val event = Event(title = "Test", startTs = NOW, endTs = NOW + 1.hour)

    val localEvent = coordinator.createEvent(event, localCal.id)
    val icloudEvent = coordinator.createEvent(event, icloudCal.id)
    val caldavEvent = coordinator.createEvent(event, caldavCal.id)

    // All in Room
    assertThat(eventsDao.getById(localEvent.id)).isNotNull()
    assertThat(eventsDao.getById(icloudEvent.id)).isNotNull()
    assertThat(eventsDao.getById(caldavEvent.id)).isNotNull()

    // Sync status
    assertThat(localEvent.syncStatus).isEqualTo(SYNCED)  // No sync
    assertThat(icloudEvent.syncStatus).isEqualTo(PENDING_CREATE)
    assertThat(caldavEvent.syncStatus).isEqualTo(PENDING_CREATE)
}

@Test
fun `golden - multiple caldav accounts`() {
    // Create two CalDAV accounts
    val nextcloudAccount = createAccount(AccountProvider.CALDAV, "user@nextcloud.com")
    val fastmailAccount = createAccount(AccountProvider.CALDAV, "user@fastmail.com")

    // Each has its own credentials
    caldavCredentialManager.saveCredentials(nextcloudAccount.id, "user", "pass1")
    caldavCredentialManager.saveCredentials(fastmailAccount.id, "user", "pass2")

    // Each can have calendars
    val nextcloudCal = createCalendar(nextcloudAccount.id, "Work")
    val fastmailCal = createCalendar(fastmailAccount.id, "Personal")

    // Both work independently
    assertThat(calendarsDao.getByAccountId(nextcloudAccount.id)).hasSize(1)
    assertThat(calendarsDao.getByAccountId(fastmailAccount.id)).hasSize(1)
}
```

---

## 11. Build Plan

### Phase 1: Foundation ✅ COMPLETE (v21.1.6)

```
✅ Add Event columns (priority, geo_lat, geo_lon, color, url, categories)
✅ Add missing boolean indexes (is_visible, is_enabled, enabled, is_cancelled)
✅ ICalEventMapper: Parse PRIORITY, GEO, COLOR, URL, CATEGORIES
✅ IcsPatcher: Write PRIORITY, GEO, COLOR, URL, CATEGORIES to ICS
✅ Integration test with real iCloud (RealICloudRfc5545RoundTripTest)
✅ Verified iCloud preserves COLOR property (RFC 7986)
✅ Migration 8→9: Composite index for exception lookup
```

### Phase 2: AccountProvider Enum ✅ COMPLETE (v21.1.8)

```
✅ Create AccountProvider enum with all metadata (displayName, requiresNetwork, supportsCalDAV, etc.)
✅ Add TypeConverter for Room (AccountProvider ↔ String)
✅ Update Account entity to use enum type
✅ Consolidate ProviderRegistry (enum-based quirks + credentials lookup)
✅ Delete CalendarProvider.kt, ICloudProvider.kt, LocalProvider.kt
✅ Update all provider == "string" comparisons to use enum
✅ Update 54+ tests to use enum
✅ CalDavSyncWorker.syncAccount() updated to use ProviderRegistry pattern
✅ Added CONTACTS provider for ContactBirthdayRepository
✅ All tests passing
```

### Phase 3: CalDAV Provider Support ✅ COMPLETE (v21.2.0)

```
✅ Create CalDavCredentialManager (account-keyed encrypted storage)
✅ Create DefaultQuirks for generic CalDAV servers
✅ Create CalDavCredentialProvider implementing CredentialProvider interface
✅ Update ProviderRegistry with getQuirksForAccount() for CALDAV support
✅ Create CalDavAccountDiscoveryService with two-phase discovery flow
✅ Create CalDavSignInSheet UI (server URL, credentials, calendar selection)
✅ Wire up Settings UI with CalDAV row and sign-in flow
✅ Implement trustInsecure SSL support for self-signed certificates
✅ All unit tests passing (4044 tests)
```

### Phase 4: Polish

```
├── Add event color picker to EventFormSheet
├── Add 3rd reminder picker (Issue #4)
├── Test against Baikal/Radicale
└── UI polish
```

---

## 12. Implementation Details

### Current State (v21.2.0)

```
Database Version: 9
Branch: main
```

**What's done (Phase 3 Complete):**
- icaldav library integrated (icaldav-core v2.8.0)
- CalDavSyncEngine, PullStrategy, PushStrategy working for iCloud and generic CalDAV
- ICloudQuirks calendar detection fix
- DefaultQuirks for generic CalDAV servers (Nextcloud, Baikal, Radicale, FastMail)
- CalDavCredentialManager with encrypted account-keyed storage
- CalDavCredentialProvider implementing CredentialProvider interface
- ProviderRegistry.getQuirksForAccount() for CALDAV support
- CalDavAccountDiscoveryService with two-phase discovery (discover → select → create)
- CalDavSignInSheet UI with Material3 ModalBottomSheet
- Settings integration with CalDAV account management
- trustInsecure SSL support for self-signed certificates
- RFC 5545/7986 fields + all indexes
- ICalEventMapper parsing new fields
- IcsPatcher generating new fields
- Integration test verified with real iCloud

### Reference Files (Saved)

Location: `/onekash/caldav-reference/`

| File | Size | Usage |
|------|------|-------|
| `CalDavAuthManager.kt` | 7.7KB | **ADAPT** - base for CalDavCredentialManager |
| `CalDavSignInSheet.kt` | 12KB | **COPY** - sign-in UI |
| `CalDavProviderAccountManager.kt` | 11KB | **ADAPT** - discovery logic |
| `ProviderType.kt` | 2.7KB | **REFERENCE** - enum concept |
| `SyncOrchestrator.kt` | 9.2KB | **REFERENCE** - multi-provider |

### What Exists (Reusable)

```
app/src/main/kotlin/org/onekash/kashcal/sync/
├── client/
│   ├── CalDavClient.kt              ← Reusable
│   ├── CalDavClientFactory.kt       ← Reusable
│   └── OkHttpCalDavClient.kt        ← Reusable
├── engine/
│   └── CalDavSyncEngine.kt          ← Reusable (parameterize by quirks)
├── parser/icaldav/
│   ├── ICalEventMapper.kt           ← Reusable
│   ├── IcsPatcher.kt                ← Reusable
│   └── RawIcsParser.kt              ← Reusable
├── quirks/
│   └── CalDavQuirks.kt              ← Extensible (add DefaultQuirks)
├── provider/
│   ├── CalendarProvider.kt          ← DELETE (consolidate into ProviderRegistry)
│   ├── ProviderRegistry.kt          ← MODIFY (consolidate quirks/credentials lookup)
│   ├── icloud/
│   │   ├── ICloudQuirks.kt          ← Keep
│   │   ├── ICloudAuthManager.kt     ← Keep (single account)
│   │   └── ICloudProvider.kt        ← DELETE (consolidate into ProviderRegistry)
│   └── local/
│       └── LocalProvider.kt         ← DELETE (consolidate into ProviderRegistry)
├── strategy/
│   ├── PullStrategy.kt              ← Reusable
│   └── PushStrategy.kt              ← Reusable
└── worker/
    └── CalDavSyncWorker.kt          ← Reusable
```

---

## 13. Summary

### Approach: Ideal (Enum Column)

Store `AccountProvider` enum directly in `Account.provider` with TypeConverter.

**Benefits over computed property:**
- Full type safety everywhere (not just call sites)
- Compiler-enforced exhaustive `when`
- IDE autocomplete and refactoring support
- Same database storage (`"icloud"`, `"local"`, etc.)

### Multi-Account Strategy

| Provider | Accounts | Credential Storage | Status |
|----------|----------|-------------------|--------|
| LOCAL | 1 | N/A | Done |
| ICLOUD | 1 | Single key (SharedPrefs) | Done, multi later |
| ICS | ∞ | N/A | Done |
| CALDAV | ∞ | Account-keyed (new) | This refactor |

### Files to Create

1. `domain/model/AccountProvider.kt` - Enum with metadata
2. `sync/provider/caldav/CalDavCredentialManager.kt` - Account-keyed credentials
3. `sync/provider/caldav/CalDavAccountManager.kt` - Account CRUD
4. `sync/quirks/DefaultQuirks.kt` - Generic CalDAV quirks
5. `ui/screens/settings/CalDavSignInSheet.kt` - Sign-in UI

### Files to Modify

1. `data/db/entity/Account.kt` - Change `provider: String` to `provider: AccountProvider`
2. `data/db/converter/Converters.kt` - Add AccountProvider TypeConverter
3. `sync/provider/ProviderRegistry.kt` - Consolidate quirks/credentials lookup (see below)
4. `sync/worker/CalDavSyncWorker.kt` - Use `when (account.provider)`
5. `domain/coordinator/EventCoordinator.kt` - Use `when (account.provider)`
6. `domain/initializer/LocalCalendarInitializer.kt` - Use enum

### ProviderRegistry Consolidation

`ProviderRegistry` becomes the single lookup point for quirks and credentials:

```kotlin
@Singleton
class ProviderRegistry @Inject constructor(
    private val icloudQuirks: ICloudQuirks,
    private val icloudCredentials: ICloudCredentialProvider,
    private val caldavCredentials: CalDavCredentialManager  // NEW
) {
    fun getQuirks(provider: AccountProvider): CalDavQuirks? = when (provider) {
        AccountProvider.LOCAL -> null
        AccountProvider.ICLOUD -> icloudQuirks
        AccountProvider.ICS -> null
        AccountProvider.CALDAV -> DefaultQuirks()
    }

    fun getCredentialProvider(provider: AccountProvider): CredentialProvider? = when (provider) {
        AccountProvider.LOCAL -> null
        AccountProvider.ICLOUD -> icloudCredentials
        AccountProvider.ICS -> null
        AccountProvider.CALDAV -> caldavCredentials
    }
}
```

### Files to Delete (Only Thin Wrappers)

| File | Lines | Content | Why Delete |
|------|-------|---------|------------|
| `CalendarProvider.kt` | ~80 | Interface definition | Replaced by enum + ProviderRegistry |
| `ICloudProvider.kt` | ~60 | DI wiring only | Consolidated into ProviderRegistry |
| `LocalProvider.kt` | ~60 | DI wiring only | Consolidated into ProviderRegistry |

### iCloud Logic That STAYS (All Core Functionality)

**Zero iCloud logic is lost.** All tested functionality remains:

#### Main Source Files (All Keep)
| File | Purpose |
|------|---------|
| `ICloudQuirks.kt` | XML parsing for iCloud responses |
| `ICloudAuthManager.kt` | Credential storage (single account) |
| `ICloudCredentialProvider.kt` | Credential retrieval |
| `ICloudAccountDiscoveryService.kt` | Account discovery flow |
| `ICloudAccount.kt` | Data model |
| `ICloudSignInSheet.kt` | Sign-in UI |

#### Sync Engine (All Keep)
| File | Purpose |
|------|---------|
| `CalDavSyncEngine.kt` | Orchestrates sync |
| `PullStrategy.kt` | Pull from server (etag fallback, parse retry) |
| `PushStrategy.kt` | Push to server (conflict resolution) |
| `ICalEventMapper.kt` | ICS → Event mapping |
| `IcsPatcher.kt` | Event → ICS generation |

#### Real iCloud Integration Tests (All Keep)
| Test | Coverage |
|------|----------|
| `RealICloudClientTest.kt` | CalDAV client operations |
| `RealICloudPullStrategyTest.kt` | Pull sync with real iCloud |
| `RealICloudSyncEngineTest.kt` | Full sync engine |
| `RealICloudRfc5545RoundTripTest.kt` | RFC 5545 field round-trip |
| `ICloudEventualConsistencyTest.kt` | Eventual consistency handling |
| `ICloudResponseCaptureTest.kt` | Response capture |
| `ICloudCredentialIntegrationTest.kt` | Credential flow |

#### Unit Tests (All Keep)
| Test | Coverage |
|------|----------|
| `ICloudQuirksTest.kt` | XML parsing |
| `ICloudAuthManagerTest.kt` | Credential storage |
| `ICloudAccountDiscoveryServiceTest.kt` | Discovery flow |
| `PullStrategyTest.kt` | Pull logic |
| `PushStrategyTest.kt` | Push logic |
| `ICalEventMapperTest.kt` | ICS mapping |
| `IcsPatcherTest.kt` | ICS generation |
| + 50 more sync tests | Various edge cases |

#### Tests That Need Updating (3 files)
| Test | Change |
|------|--------|
| `ICloudProviderTest.kt` | Adapt to test `ProviderRegistry.getQuirks(ICLOUD)` |
| `LocalProviderTest.kt` | Adapt to test `ProviderRegistry.getQuirks(LOCAL)` |
| `ProviderRegistryTest.kt` | Update for new consolidated API |

### What's NOT Needed

| Item | Reason |
|------|--------|
| Schema migration for provider | TypeConverter handles string↔enum |
| `Calendar.systemCalendarId` | All Room - no CalendarProvider |
| Negative ID hack | All Room - normal Room IDs |
| `UnifiedEventRepository` | All queries go through Room |
| Multi-iCloud now | Future enhancement |
| Attendees table | Future - add when needed |
| Attachments table | Future - add when needed |
