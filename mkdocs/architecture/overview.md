# Architecture Overview

KashCal follows a layered architecture with clear separation of concerns. All data flows through well-defined interfaces, making the codebase testable and maintainable.

## Layer Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                       UI Layer                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ HomeScreen  │  │ Settings    │  │ EventForm/QuickView │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│         │                │                     │             │
│  ┌──────┴──────┐  ┌──────┴──────┐  ┌──────────┴──────────┐  │
│  │HomeViewModel│  │AccountVM    │  │ Compose Components  │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
├─────────┼────────────────┼────────────────────┼─────────────┤
│         │         Domain Layer                │             │
│  ┌──────┴────────────────┴────────────────────┴──────────┐  │
│  │              EventCoordinator (writes)                 │  │
│  │              EventReader (reads)                       │  │
│  │              OccurrenceGenerator (RRULE expansion)     │  │
│  └──────────────────────────┬────────────────────────────┘  │
├─────────────────────────────┼───────────────────────────────┤
│                      Sync Layer                              │
│  ┌──────────────────────────┴────────────────────────────┐  │
│  │ CalDavSyncWorker → CalDavSyncEngine                   │  │
│  │     ├── PushStrategy (local → server)                 │  │
│  │     ├── PullStrategy (server → local)                 │  │
│  │     └── ConflictResolver (412 handling)               │  │
│  └──────────────────────────┬────────────────────────────┘  │
├─────────────────────────────┼───────────────────────────────┤
│                      Data Layer                              │
│  ┌──────────────────────────┴────────────────────────────┐  │
│  │ Room Database (9 entities, 12 migrations)             │  │
│  │ EncryptedSharedPreferences (credentials)              │  │
│  │ DataStore (preferences)                               │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Key Principles

### 1. Offline-First

All user operations complete instantly against the local database. Synchronization happens asynchronously in the background via WorkManager.

```kotlin
// User creates event → instant local write
eventCoordinator.createEvent(event)
    // 1. Insert into Room DB
    // 2. Queue PendingOperation
    // 3. Trigger WorkManager sync
    // User sees event immediately
```

### 2. Single Entry Point (EventCoordinator)

ViewModels never access DAOs directly. All event operations flow through `EventCoordinator`:

```kotlin
// CORRECT
class HomeViewModel @Inject constructor(
    private val coordinator: EventCoordinator,  // Writes
    private val eventReader: EventReader        // Reads
)

// WRONG - violates architecture
class HomeViewModel @Inject constructor(
    private val eventsDao: EventsDao  // Never do this
)
```

### 3. Materialized Occurrences

Recurring events are expanded into the `occurrences` table for O(1) range queries:

```
Event (master data)
    ↓ RRULE expansion
Occurrences table (materialized)
    ↓ indexed queries
Calendar views (instant rendering)
```

### 4. Flow for Reactive UI

All read queries return `Flow` for progressive updates during sync:

```kotlin
fun getEventsForDay(dayCode: Int): Flow<List<OccurrenceWithEvent>> =
    occurrencesDao.getForDayFlow(dayCode)
        .distinctUntilChanged()
        .map { occurrences -> loadEventsForOccurrences(occurrences) }
```

## Component Responsibilities

| Component | Responsibility |
|-----------|---------------|
| `EventCoordinator` | Orchestrates all event writes, triggers sync, schedules reminders |
| `EventReader` | Read-only queries with Flow support, batch loading |
| `EventWriter` | Transactional writes with occurrence generation |
| `OccurrenceGenerator` | RRULE expansion using lib-recur |
| `CalDavSyncWorker` | WorkManager entry point for background sync |
| `PullStrategy` | Server → local sync with etag comparison |
| `PushStrategy` | Local → server sync with conflict detection |
| `AccountRepository` | Account CRUD with cascade deletion |
| `CredentialManager` | Encrypted credential storage |

## Data Flow Examples

### Creating an Event

```
User taps Save
    ↓
HomeViewModel.createEvent()
    ↓
EventCoordinator.createEvent()
    ├── EventWriter.createEvent() [transaction]
    │   ├── Generate UID
    │   ├── Insert into events table
    │   ├── Generate occurrences (2-year window)
    │   └── Queue PendingOperation (OPERATION_CREATE)
    ├── Schedule reminders
    ├── Update widgets
    └── Trigger expedited sync
    ↓
CalDavSyncWorker fires
    ↓
PushStrategy processes queue
    ├── Serialize to ICS
    ├── PUT to CalDAV server
    └── Mark as SYNCED
```

### Syncing from Server

```
CalDavSyncWorker.doWork()
    ↓
CalDavSyncEngine.syncCalendar()
    ├── PushStrategy (drain pending queue first)
    ├── ConflictResolver (handle 412 errors)
    └── PullStrategy
        ├── Check ctag (quick change detection)
        ├── sync-collection REPORT
        ├── calendar-multiget (fetch changed events)
        ├── ICalEventMapper (parse iCal → Event)
        ├── linkException() for modified occurrences
        └── Bulk insert/update via Room
    ↓
Room emits Flow updates
    ↓
UI recomposes with new data
```

## Directory Structure

```
app/src/main/kotlin/org/onekash/kashcal/
├── data/
│   ├── db/entity/      # Room entities (Event, Calendar, etc.)
│   ├── db/dao/         # Data Access Objects
│   ├── credential/     # CredentialManager, UnifiedCredentialManager
│   ├── repository/     # AccountRepository, CalendarRepository
│   └── preferences/    # KashCalDataStore
├── domain/
│   ├── coordinator/    # EventCoordinator
│   ├── reader/         # EventReader, SyncLogReader
│   ├── writer/         # EventWriter
│   ├── generator/      # OccurrenceGenerator
│   ├── initializer/    # LocalCalendarInitializer
│   └── rrule/          # RruleBuilder, RruleModels
├── sync/
│   ├── worker/         # CalDavSyncWorker
│   ├── engine/         # CalDavSyncEngine
│   ├── strategy/       # PullStrategy, PushStrategy
│   ├── client/         # CalDavClient, OkHttpCalDavClient
│   ├── parser/         # ICalEventMapper, IcsPatcher
│   └── provider/       # ICloudQuirks, CalDavCredentialProvider
├── ui/
│   ├── viewmodels/     # HomeViewModel, AccountSettingsViewModel
│   ├── screens/        # HomeScreen, AccountSettingsScreen
│   ├── components/     # EventFormSheet, pickers, etc.
│   └── theme/          # Material 3 theme
├── widget/             # Glance widgets (Agenda, Week, Date)
├── reminder/           # ReminderScheduler, notifications
└── di/                 # Hilt modules
```

## Technology Stack

| Layer | Technology |
|-------|------------|
| UI | Jetpack Compose, Material 3 |
| State Management | StateFlow, Flow |
| Database | Room 2.7 with FTS4 |
| DI | Hilt 2.53 |
| Background | WorkManager 2.10 |
| Networking | OkHttp 4.12 |
| CalDAV | icaldav-core 2.8, lib-recur 0.17 |
| Security | EncryptedSharedPreferences, Android Keystore |
| Widgets | Jetpack Glance 1.1 |
