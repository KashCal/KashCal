# Reminders

KashCal supports configurable event reminders with notification actions.

## Setting Reminders

Each event supports up to 3 reminders:
- First Alert (default: 15 minutes before)
- Second Alert (default: off)

### Options for Timed Events
- At time of event
- 5, 10, 15, 30 minutes before
- 1, 2 hours before
- 1 day, 2 days, 1 week before

### Options for All-Day Events
- On day of event (9 AM)
- 1 day, 2 days, 1 week before (9 AM)

## Notification Actions

When a reminder fires:
- **Snooze** - Reschedule 15 minutes later
- **Dismiss** - Cancel notification

Tap notification to open event details.

## Technical Details

- Uses `AlarmManager` for precise timing
- Reminders survive device reboot
- All-day reminders fire at 9 AM local time
- Timezone-aware for travel

## Permissions

Android 13+ requires notification permission. KashCal requests this when creating events with reminders.
