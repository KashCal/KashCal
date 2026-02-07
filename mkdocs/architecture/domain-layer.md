# Domain Layer

The domain layer provides business logic through EventCoordinator, EventReader, and related components.

See [Architecture Overview](overview.md) for the complete layer diagram.

## EventCoordinator

Central orchestrator for all event write operations.

**Key responsibilities:**
- Create/update/delete events with transaction boundaries
- Schedule reminders after modifications
- Trigger sync after CalDAV changes
- Update widgets after data changes

## EventReader

Read-only queries with Flow support for reactive UI.

**Key patterns:**
- Batch loading (3 queries instead of N+1)
- Flow with distinctUntilChanged() for deduplication
- Uses `exceptionEventId ?: eventId` for exception events

## OccurrenceGenerator

RRULE expansion using lib-recur library.

**Key features:**
- 2-year default expansion window
- On-demand extension for far-future navigation
- Exception link preservation during regeneration
- UTC timezone for all-day events

## RruleBuilder

Type-safe RRULE construction for UI layer.

**Never expose lib-recur directly to UI.**
