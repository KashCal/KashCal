# Installation

## Download Options

### F-Droid (Recommended)

KashCal is available on F-Droid, the open-source Android app store.

1. Install [F-Droid](https://f-droid.org)
2. Search for "KashCal"
3. Tap Install

### GitHub Releases

Download the latest APK from [GitHub Releases](https://github.com/KashCal/KashCal/releases).

1. Download the `.apk` file
2. Enable "Install from unknown sources" if prompted
3. Open the APK to install

### Build from Source

See the [Build Guide](../development/build.md) for instructions.

## First Launch

On first launch, KashCal:

1. Creates a local calendar ("On This Device")
2. Sets up notification channels
3. Schedules background sync worker

You can start creating events immediately - no account required.

## Permissions

KashCal requests the following permissions:

| Permission | Purpose | Required |
|------------|---------|----------|
| Internet | CalDAV sync | For sync only |
| Notifications | Event reminders | Optional |
| Contacts | Birthday import | Optional |
| Exact Alarms | Precise reminders | Auto-granted for calendar apps |

## Storage

KashCal stores data in:

- **Room Database** - Events, calendars, sync state
- **EncryptedSharedPreferences** - Credentials (AES-256)
- **DataStore** - User preferences

All data is excluded from Android backup by default.
