# How CalDAV Sync Works

KashCal implements full CalDAV synchronization following RFC 4791 (CalDAV) and RFC 5545 (iCalendar).

## Sync Flow

```
┌─────────────────────────────────────────────────────────────┐
│                    CalDavSyncWorker                          │
│              (WorkManager background job)                    │
└─────────────────────────┬───────────────────────────────────┘
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                   CalDavSyncEngine                           │
│                                                              │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────────┐   │
│  │ PushStrategy│ → │ConflictRes. │ → │  PullStrategy   │   │
│  │ (local→srv) │   │  (412 fix)  │   │   (srv→local)   │   │
│  └─────────────┘   └─────────────┘   └─────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

**Order matters:** Push → Conflict Resolution → Pull (reduces conflict window)

## Push Strategy

Local changes are queued in `pending_operations` and pushed to the server.

### Operation Types

| Operation | HTTP Method | Condition |
|-----------|-------------|-----------|
| CREATE | PUT | If-None-Match: * |
| UPDATE | PUT | If-Match: etag |
| DELETE | DELETE | If-Match: etag |
| MOVE | DELETE + PUT | Two-phase operation |

### ICS Generation

Events are serialized to ICS format using `IcsPatcher`:

```kotlin
// For updates: merge changes with original rawIcal
val ics = IcsPatcher.patch(event, rawIcal)

// For new events: generate fresh ICS
val ics = IcsPatcher.generateFresh(event)

// Exception events bundled with master
val ics = IcsPatcher.serializeWithExceptions(master, exceptions)
```

**Alarm merge strategy:**

- User's reminder edits update alarm TRIGGER times
- Original ACTION types preserved (AUDIO, EMAIL, DISPLAY)
- Properties beyond first 3 alarms preserved via rawIcal

### Retry Logic

```
Retry 1:  30s delay
Retry 2:  60s delay
Retry 3:  2m delay
...
Retry 10: 5h delay (cap)

After 10 retries: FAILED status
After 24h in FAILED: auto-reset to PENDING
After 30 days without user interaction: abandoned
```

## Pull Strategy

Server changes are fetched and merged into the local database.

### Change Detection

```
┌─────────────────────────────────────────┐
│           Quick ctag check              │
│   (single PROPFIND, ~100 bytes)         │
└──────────────────┬──────────────────────┘
                   │
         ctag unchanged? → Return (no changes)
                   │
                   ▼
┌─────────────────────────────────────────┐
│        sync-collection REPORT           │
│    (incremental since sync-token)       │
└──────────────────┬──────────────────────┘
                   │
         Token expired (403/410)?
                   │
         ┌────────┴────────┐
         ▼                 ▼
┌─────────────────┐  ┌─────────────────┐
│  Etag fallback  │  │   Full sync     │
│   (~33KB data)  │  │  (~834KB data)  │
└─────────────────┘  └─────────────────┘
```

### Etag Comparison (Bandwidth Optimization)

When sync-token expires, KashCal fetches only etags instead of full events:

```kotlin
// 1. Fetch etags only (~2% of bandwidth)
val serverEtags = client.fetchEtagsInRange(calendarUrl, startMs, endMs)

// 2. Compare with local
val changed = serverEtags.filter { localEtags[it.href] != it.etag }
val newEvents = serverEtags.filter { it.href !in localEtags }
val deleted = localEtags.keys - serverEtags.map { it.href }

// 3. Fetch only changed events
val events = client.fetchEventsByHref(calendarUrl, changed + newEvents)

// Saves ~96% bandwidth (33KB vs 834KB for 231 events)
```

### Multiget Fallback

When batch multiget fails, individual fetches are used:

```
Stage 1: Batch multiget (all hrefs at once)
    ↓ Failed
Stage 2: Retry after 2000ms delay
    ↓ Failed
Stage 3: Individual fetches in batches of 10
```

### Local-First Semantics

Events with pending local changes are NEVER overwritten by server data:

```kotlin
// Skip if event has pending changes
if (pendingOpsDao.hasPendingForEvent(event.id)) {
    continue  // Don't overwrite local edits
}
```

## Conflict Resolution

When an event is modified on both client and server (412 Precondition Failed):

### Server Wins (Default)

```kotlin
// 1. Fetch server version
val serverEvent = client.fetchEvent(caldavUrl)

// 2. Parse and validate
val parsed = ICalEventMapper.map(serverEvent)

// 3. Overwrite local
eventsDao.update(parsed)

// 4. Remove pending operation
pendingOpsDao.delete(operation.id)

// 5. Regenerate occurrences
occurrenceGenerator.regenerateOccurrences(parsed)
```

### Conflict Abandonment (After 3 Cycles)

If conflict resolution fails repeatedly:

```kotlin
if (operation.retryCount >= MAX_CONFLICT_SYNC_CYCLES) {
    // 1. Reset event to SYNCED (accept server version)
    eventsDao.updateSyncStatus(eventId, SyncStatus.SYNCED)

    // 2. Clear ctag to force pull
    calendarsDao.updateCtag(calendarId, null)

    // 3. Delete stuck operation
    pendingOpsDao.delete(operation.id)

    // 4. Notify user
    notificationManager.showConflictAbandonedNotification(event)
}
```

## Exception Events (Recurring Event Modifications)

When a single occurrence of a recurring event is modified:

### RFC 5545 Requirements

- Exception events share the master's UID
- Distinguished by RECURRENCE-ID (originalInstanceTime)
- Bundled with master in single .ics file

### Model B (Unified)

```
Master Event (id=100, rrule="FREQ=WEEKLY")
  └── Occurrence: eventId=100, startTs=Jan 7 14:00, exceptionEventId=101

Exception Event (id=101, originalEventId=100)
  └── (no separate occurrence - linked to master's)
```

**Key pattern:**

```kotlin
// Load correct event for display
val eventId = occurrence.exceptionEventId ?: occurrence.eventId
val event = eventReader.getEventById(eventId)
```

## Provider Quirks

Different CalDAV servers have implementation differences handled by `CalDavQuirks`:

### iCloud Quirks

- Non-prefixed XML namespaces (`xmlns="DAV:"` vs `d:`)
- CDATA-wrapped calendar-data
- Regional redirect handling (p180-caldav.icloud.com)
- App-specific password required
- Skips inbox/outbox/notification/tasks/reminders

### Detection Logic

```kotlin
// Check inside <resourcetype> only, not anywhere in response
private fun hasCalendarResourceType(xml: String): Boolean {
    val resourceTypeRegex = Regex(
        """<(?:d:)?resourcetype[^>]*>(.*?)</(?:d:)?resourcetype>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    val match = resourceTypeRegex.find(xml) ?: return false
    val content = match.groupValues[1]
    return content.contains("<calendar", ignoreCase = true)
}
```

## Sync Session Tracking

Each sync operation creates a `SyncSession` for diagnostics:

```kotlin
data class SyncSession(
    val calendarId: Long,
    val syncType: SyncType,
    val trigger: SyncTrigger,
    val durationMs: Long,

    // Pull statistics
    val hrefsReported: Int,
    val eventsFetched: Int,
    val eventsWritten: Int,

    // Push statistics
    val eventsPushedCreated: Int,
    val eventsPushedUpdated: Int,
    val eventsPushedDeleted: Int,

    // Issues
    val skippedParseError: Int,
    val abandonedParseErrors: Int,
    val fallbackUsed: Boolean
)
```
