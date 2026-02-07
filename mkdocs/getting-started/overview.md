# Overview

KashCal is a workflow-first calendar app designed for users who want control over their calendar data.

## Philosophy

### Offline-First

KashCal works without an internet connection. Events are saved locally first, then synchronized in the background when connectivity is available.

### Privacy-Focused

- No analytics or tracking
- Credentials encrypted with Android Keystore
- No cloud services required (self-hosted CalDAV supported)

### Standards-Based

Built on open standards:

- **RFC 4791** - CalDAV protocol
- **RFC 5545** - iCalendar format
- **RFC 6578** - WebDAV Sync

## Features

### Core Calendar

- Create, edit, delete events
- Recurring events with full RRULE support
- Exception handling (edit/delete single occurrences)
- Multi-day events
- All-day events with timezone awareness
- Location with map integration
- Full-text search (FTS4)

### CalDAV Sync

- iCloud (with app-specific password)
- Nextcloud
- Self-hosted (Radicale, Baikal, Stalwart)
- FastMail
- Any RFC 4791 compliant server

### Additional Features

- ICS subscriptions (holidays, sports, etc.)
- Contact birthday import
- Home screen widgets (Agenda, Week, Date)
- Configurable reminders with snooze
- Calendar color customization
- First day of week preference

## System Requirements

- Android 8.0 (API 26) or higher
- ~50MB storage
- Internet connection for sync (offline mode available)

## Getting Started

1. **Install** - Download from F-Droid or GitHub Releases
2. **Create Events** - Local calendar is ready immediately
3. **Add Account** - Connect iCloud, Nextcloud, or CalDAV server
4. **Sync** - Events sync automatically in background
