# Critical Patterns

These patterns prevent bugs. Violating them will break functionality.

## 1. Use Domain Layer, Not DAOs

ViewModels must never access DAOs directly.

```kotlin
// CORRECT
class CalendarViewModel(
    private val coordinator: EventCoordinator,  // Writes
    private val eventReader: EventReader        // Reads
)

// WRONG
class CalendarViewModel(private val eventsDao: EventsDao)
```

## 2. Always Use @Transaction for Multi-Step Operations

```kotlin
@Transaction
suspend fun createException(master: Event, occurrence: Long): Event {
    val exception = eventsDao.insert(...)
    occurrencesDao.linkException(...)
    return exception
}
```

## 3. Time-Based Queries: Use Occurrences Table

**CRITICAL:** Never filter events by `Event.endTs` for time-based queries. This field is the **first occurrence's** end time, not the series end.

```kotlin
// CORRECT
@Query("""
    SELECT DISTINCT events.* FROM events
    JOIN occurrences ON events.id = occurrences.event_id
    WHERE occurrences.end_ts >= :now
""")
suspend fun getEventsWithFutureOccurrences(now: Long): List<Event>

// WRONG - breaks for recurring events!
events.filter { it.endTs >= now }
```

## 4. Self-Contained Sync Operations

PendingOperation must store ALL context needed at queue time.

```kotlin
// CORRECT: Store URL at queue time
pendingOpsDao.insert(PendingOperation(
    eventId = eventId,
    operation = OPERATION_MOVE,
    targetUrl = oldCaldavUrl,  // Captured BEFORE clearing
    targetCalendarId = newCalendarId
))

// WRONG: Read URL at process time - already null!
val url = event.caldavUrl
```

## 5. Exception Events Share Master UID

**CRITICAL:** Exception events MUST have the same UID as their master event (RFC 5545).

```kotlin
// CORRECT
val exceptionEvent = modifiedEvent.copy(
    uid = masterEvent.uid,  // Same UID
    originalEventId = masterEventId,
    syncStatus = SyncStatus.SYNCED  // Bundled with master
)
// Queue UPDATE on MASTER (not CREATE on exception)
queueOperation(masterEventId, OPERATION_UPDATE)

// WRONG: Different UID creates separate event
uid = "${masterEvent.uid}-${occurrenceTimeMs}"
```

## 6. Use Flow for Observable Data Sources

Use Room's Flow for time-sensitive queries so UI updates progressively during sync.

```kotlin
// CORRECT: UI updates as events are written
fun loadEventsForDay(dayCode: Int) {
    dayEventsJob?.cancel()
    dayEventsJob = viewModelScope.launch {
        eventReader.getVisibleOccurrencesForDay(dayCode)
            .distinctUntilChanged()
            .collect { occurrences ->
                _uiState.value = _uiState.value.copy(dayEvents = occurrences)
            }
    }
}

// WRONG: UI empty until sync completes
suspend fun loadEventsForDay(dayCode: Int) {
    val occurrences = eventReader.getOccurrencesForDayOnce(dayCode)
    _uiState.value = _uiState.value.copy(dayEvents = occurrences)
}
```

## 7. Use coerceIn() for Input Bounds

When using bit-shift operations, always bound both min AND max.

```kotlin
// CORRECT
val multiplier = 1L shl retryCount.coerceIn(0, 10)

// WRONG: Negative values cause undefined behavior
val multiplier = 1L shl retryCount.coerceAtMost(10)
```

## 8. Exception Events Cannot Be Manipulated Directly

Exception events must go through the master event.

```kotlin
// CORRECT
val masterEventId = event.originalEventId ?: event.id
eventCoordinator.deleteSingleOccurrence(masterEventId, occurrenceTs)

// WRONG: Throws IllegalArgumentException
eventCoordinator.deleteEvent(exceptionEvent.id)
```

## 9. Load Exception Events Using exceptionEventId

When loading events from occurrences, use `exceptionEventId ?: eventId`.

```kotlin
// CORRECT
val eventIds = occurrences.map { occ ->
    occ.exceptionEventId ?: occ.eventId
}.distinct()
val events = eventIds.mapNotNull { eventReader.getEventById(it) }

// WRONG: Always loads master instead of exception
val eventIds = occurrences.map { it.eventId }.distinct()
```

## 10. All-Day Event isPast: Use Day Code Comparison

For all-day events, use day code comparison instead of timestamp comparison.

```kotlin
// CORRECT
val isPast = DateTimeUtils.isEventPast(
    occurrence.endTs,
    occurrence.endDay,
    event.isAllDay
)

// WRONG: Fails for all-day events in negative UTC offsets
val isPast = occurrence.endTs < System.currentTimeMillis()
```

## 11. Token + ctag Coordination

When holding sync-token due to parse failures, ctag must also be held.

```kotlin
return PullResult.Success(
    newSyncToken = effectiveSyncToken,
    // Coordinate ctag with token decision
    newCtag = if (effectiveSyncToken == calendar.syncToken)
        calendar.ctag  // Hold ctag too
    else
        null  // Advance ctag
)
```

## 12. Use Explicit PendingIntents

Always use explicit intents when creating PendingIntents (CWE-927).

```kotlin
// CORRECT: Explicit intent with target class
val intent = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK
}
val pendingIntent = PendingIntent.getActivity(
    context, requestCode, intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

// WRONG: Implicit intent
val intent = Intent("com.example.ACTION")
```

## 13. Mask Sensitive Data in Logs

Never log full credentials, tokens, or PII.

```kotlin
// CORRECT
Log.d(TAG, "Saved account: ${appleId.take(3)}***")
Log.d(TAG, "Sync token: ${syncToken?.take(8)}...")

// WRONG
Log.d(TAG, "Saved account: $appleId")
```

## 14. Reminder Sorting at Sync Time

Reminders are sorted by absolute duration (ascending) before storage.

```kotlin
reminders.sortedBy { it.trigger?.abs() }
// -PT15M, PT30M, -PT1H → closest first
```

## 15. End Time >= Start Time Validation

Events must have `endTs >= startTs`. Validation at two layers:

**UI Layer:** Shows error, disables save button

**Domain Layer:**

```kotlin
suspend fun createEvent(event: Event, calendarId: Long? = null): Event {
    require(event.endTs >= event.startTs) {
        "End time must be >= start time"
    }
    // ...
}
```

## 16. Conflict Resolution Abandonment

After 3 sync cycles with unresolved conflict, abandon local changes.

```kotlin
if (op.retryCount >= MAX_CONFLICT_SYNC_CYCLES) {
    // 1. Reset to SYNCED so pull can fetch server version
    eventsDao.updateSyncStatus(eventId, SyncStatus.SYNCED)

    // 2. Clear ctag to force re-fetch
    calendarsDao.updateCtag(event.calendarId, null)

    // 3. Re-read calendar for pull (critical!)
    val calendarForPull = calendarsDao.getById(calendar.id)

    // 4. Notify user
    notificationManager.showConflictAbandonedNotification(event)
}
```
