# Recurring Events

KashCal provides full RFC 5545 RRULE support for recurring events with proper exception handling.

## Creating Recurring Events

### Supported Patterns

| Pattern | RRULE | Example |
|---------|-------|---------|
| Daily | `FREQ=DAILY` | Every day |
| Every N days | `FREQ=DAILY;INTERVAL=3` | Every 3 days |
| Weekly | `FREQ=WEEKLY` | Every week |
| Specific days | `FREQ=WEEKLY;BYDAY=MO,WE,FR` | Mon, Wed, Fri |
| Biweekly | `FREQ=WEEKLY;INTERVAL=2` | Every 2 weeks |
| Monthly (same day) | `FREQ=MONTHLY;BYMONTHDAY=15` | 15th of each month |
| Monthly (last day) | `FREQ=MONTHLY;BYMONTHDAY=-1` | Last day of month |
| Monthly (Nth weekday) | `FREQ=MONTHLY;BYDAY=2TU` | 2nd Tuesday |
| Yearly | `FREQ=YEARLY` | Same date each year |

### End Conditions

| Condition | RRULE | Description |
|-----------|-------|-------------|
| Forever | (none) | No end date |
| Count | `COUNT=10` | After 10 occurrences |
| Until date | `UNTIL=20261231` | Until Dec 31, 2026 |

## Occurrence Materialization

Recurring events are expanded into the `occurrences` table for efficient queries.

### Expansion Window

- **Default:** Current date ± 2 years
- **On-demand:** Extended when user navigates beyond window

```kotlin
// Expansion triggered by navigation
fun triggerOccurrenceExtension(targetDateMs: Long) {
    viewModelScope.launch {
        delay(500)  // Debounce
        eventCoordinator.extendOccurrencesIfNeeded(targetDateMs)
    }
}
```

### RRULE Formula (RFC 5545)

```
RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
```

- **DTSTART:** First occurrence
- **RRULE:** Recurrence rule
- **RDATE:** Additional specific dates
- **EXDATE:** Excluded dates

## Editing Recurring Events

### Edit Options

When editing a recurring event occurrence, users choose:

1. **This Event Only** - Creates an exception
2. **This and All Future** - Splits the series
3. **All Events** - Modifies the master

### Exception Events (Edit Single Occurrence)

```kotlin
// User modifies single occurrence
eventCoordinator.editSingleOccurrence(
    masterEventId = event.originalEventId ?: event.id,
    occurrenceTimeMs = selectedOccurrenceTs,
    changes = { it.copy(title = "Modified Title") }
)
```

**What happens:**

1. Create exception event with same UID as master
2. Set `originalEventId` pointing to master
3. Set `originalInstanceTime` (RECURRENCE-ID)
4. Link occurrence to exception via `exceptionEventId`
5. Queue master for UPDATE (exception bundled in .ics)

### Split Series (Edit This and All Future)

```kotlin
eventCoordinator.editThisAndFuture(
    masterEventId = masterEvent.id,
    splitTimeMs = selectedOccurrenceTs,
    changes = { it.copy(title = "New Title") }
)
```

**What happens:**

1. Add UNTIL to master's RRULE (truncate)
2. Delete occurrences at/after split point
3. Create NEW event with modifications
4. Generate occurrences for new series
5. Queue both events for sync

## Deleting Recurring Events

### Delete Options

| Option | Effect |
|--------|--------|
| This Event Only | Add to EXDATE, cancel occurrence |
| This and All Future | Truncate RRULE with UNTIL |
| All Events | Delete entire series |

### Delete Single Occurrence

```kotlin
eventCoordinator.deleteSingleOccurrence(
    masterEventId = masterEvent.id,
    occurrenceTimeMs = selectedOccurrenceTs
)
```

**What happens:**

1. Add timestamp to master's `exdate` field
2. Mark occurrence as `isCancelled = true`
3. Delete exception event if one exists
4. Queue master for UPDATE

### Delete This and All Future

```kotlin
eventCoordinator.deleteThisAndFuture(
    masterEventId = masterEvent.id,
    fromTimeMs = selectedOccurrenceTs
)
```

**What happens:**

1. Add UNTIL to RRULE (before fromTimeMs)
2. Delete occurrences at/after fromTimeMs
3. Delete exception events for deleted occurrences
4. Queue master for UPDATE

## Exception Event Model

KashCal uses "Model B" for exception events:

```
Master Event (id=100, rrule="FREQ=WEEKLY")
  └── Occurrence: eventId=100, exceptionEventId=101

Exception Event (id=101, originalEventId=100)
  └── (no separate occurrence - linked to master's)
```

### Key Properties

- Exception shares master's UID (RFC 5545)
- Distinguished by `originalInstanceTime` (RECURRENCE-ID)
- `syncStatus = SYNCED` (bundled with master)
- No RRULE, EXDATE, RDATE on exceptions

### Loading for Display

```kotlin
// CRITICAL: Use exceptionEventId if present
val eventId = occurrence.exceptionEventId ?: occurrence.eventId
val event = eventReader.getEventById(eventId)
```

## RruleBuilder API

Type-safe RRULE construction for UI:

```kotlin
// Build RRULE strings
val rrule = RruleBuilder.weekly(interval = 1, days = setOf(MONDAY, WEDNESDAY))
// → "FREQ=WEEKLY;BYDAY=MO,WE"

val rrule = RruleBuilder.monthlyNthWeekday(ordinal = 2, weekday = TUESDAY)
// → "FREQ=MONTHLY;BYDAY=2TU"

val rrule = RruleBuilder.withCount(baseRrule, count = 10)
// → "FREQ=WEEKLY;COUNT=10"

// Parse for display
val display = RruleBuilder.formatForDisplay(rrule)
// → "Weekly on Mon, Wed"

// Parse for UI state
val parsed = RruleBuilder.parseRrule(rrule, ...)
// → ParsedRecurrence(frequency=WEEKLY, weekdays=[MONDAY, WEDNESDAY])
```

## Timezone Handling

### All-Day Events

All-day events are stored as UTC midnight. Occurrences use day codes (YYYYMMDD) for timezone-agnostic display.

```kotlin
// Force UTC for all-day expansion
val tz = if (event.isAllDay) {
    TimeZone.getTimeZone("UTC")
} else {
    TimeZone.getTimeZone(event.timezone ?: TimeZone.getDefault().id)
}
```

### Timed Events

Timed events preserve the original timezone in the `timezone` field and display correctly across DST transitions.
