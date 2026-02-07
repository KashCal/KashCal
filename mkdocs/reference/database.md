# Database Schema

## Entities

| Entity | Purpose |
|--------|---------|
| Account | CalDAV account credentials and sync state |
| Calendar | Calendar metadata and visibility |
| Event | Calendar events (73 columns) |
| Occurrence | Materialized RRULE expansions |
| PendingOperation | Offline sync queue |
| ScheduledReminder | Alarm scheduling |
| SyncLog | Sync audit trail |
| IcsSubscription | ICS feed subscriptions |
| EventFts | FTS4 search index |

## Current Version

Database version: **12**

See [Data Layer](../architecture/data-layer.md) for migration history.

## Key Relationships

```
Account (1) → (N) Calendar
Calendar (1) → (N) Event
Event (1) → (N) Occurrence
Event (master) → (N) Event (exceptions)
```

## Important Indices

- `events(calendar_id, uid, original_instance_time)` - Exception lookup
- `occurrences(start_day, end_day)` - Range queries
- `events_fts` - Full-text search
