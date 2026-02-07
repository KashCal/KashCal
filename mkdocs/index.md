# KashCal

**Workflow-first calendar app with offline-first architecture and CalDAV sync.**

KashCal is an Android calendar application designed for users who want full control over their calendar data. It supports CalDAV synchronization with iCloud, Nextcloud, Radicale, Baikal, Stalwart, FastMail, and other CalDAV-compatible servers.

## Key Features

- **Offline-First**: Create and edit events instantly, sync happens in the background
- **CalDAV Sync**: Full RFC 4791/5545 compliance for calendar synchronization
- **Multiple Accounts**: Connect iCloud, Nextcloud, and self-hosted CalDAV servers
- **Recurring Events**: Complete RRULE support with exception handling
- **ICS Subscriptions**: Subscribe to public calendars (holidays, sports, etc.)
- **Contact Birthdays**: Import birthdays from your contacts
- **Home Screen Widgets**: Agenda, week view, and date widgets
- **Reminders**: Configurable notifications with snooze support
- **Full-Text Search**: Fast FTS4-powered event search
- **Privacy-Focused**: No analytics, credentials encrypted with Android Keystore

## Architecture Highlights

```
┌─────────────────────────────────────────────────────────┐
│                    UI Layer                             │
│         (Jetpack Compose, Material 3, ViewModels)       │
├─────────────────────────────────────────────────────────┤
│                  Domain Layer                           │
│    (EventCoordinator, EventReader, OccurrenceGenerator) │
├─────────────────────────────────────────────────────────┤
│                   Sync Layer                            │
│  (CalDavSyncWorker, PullStrategy, PushStrategy, Client) │
├─────────────────────────────────────────────────────────┤
│                   Data Layer                            │
│      (Room Database, EncryptedSharedPreferences)        │
└─────────────────────────────────────────────────────────┘
```

## Quick Start

1. Install KashCal from [F-Droid](https://f-droid.org) or [GitHub Releases](https://github.com/KashCal/KashCal/releases)
2. Open the app - a local calendar is created automatically
3. Add your CalDAV account in Settings
4. Start creating events!

## Supported CalDAV Providers

| Provider | Status | Notes |
|----------|--------|-------|
| iCloud | Tested | Requires app-specific password |
| Nextcloud | Tested | Full support |
| Radicale | Tested | Self-hosted |
| Baikal | Tested | Self-hosted |
| Stalwart | Tested | Self-hosted |
| FastMail | Tested | Full support |
| Any CalDAV | Should work | RFC 4791 compliant servers |

## Documentation

- [Getting Started](getting-started/overview.md) - Installation and setup
- [Architecture](architecture/overview.md) - How KashCal is built
- [CalDAV Sync](caldav/how-it-works.md) - Synchronization details
- [Development](development/build.md) - Build and contribute

## License

KashCal is open source software. See the [LICENSE](https://github.com/KashCal/KashCal/blob/main/LICENSE) file for details.
