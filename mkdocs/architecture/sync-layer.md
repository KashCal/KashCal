# Sync Layer

See [How CalDAV Sync Works](../caldav/how-it-works.md) for detailed sync documentation.

## Components

- **CalDavSyncWorker** - WorkManager entry point
- **CalDavSyncEngine** - Orchestrates push/conflict/pull
- **PushStrategy** - Local → server sync
- **PullStrategy** - Server → local sync
- **ConflictResolver** - 412 error handling
- **CalDavClient** - HTTP operations

## Key Files

```
sync/
├── worker/CalDavSyncWorker.kt      # WorkManager job
├── engine/CalDavSyncEngine.kt      # Orchestration
├── strategy/PullStrategy.kt        # ~1,100 lines
├── strategy/PushStrategy.kt        # ~650 lines
├── client/OkHttpCalDavClient.kt    # ~1,300 lines
└── parser/ICalEventMapper.kt       # iCal parsing
```
