# KashCal

**Your calendar. Your device. Your rules.**

[![Build](https://github.com/KashCal/KashCal/actions/workflows/build.yml/badge.svg)](https://github.com/KashCal/KashCal/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com/about/versions/oreo)

A privacy-first calendar that works offline and looks beautiful. Connect to iCloud — or don't. Your schedule, your choice.

---

<table align="center">
  <tr>
    <td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/HomePage-with-AboutMe.png" width="180"><br><sub>Home & Agenda</sub></td>
    <td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/NewEvent.png" width="180"><br><sub>Create Event</sub></td>
    <td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/NewEvent-Date-Time-Picker.png" width="180"><br><sub>Date & Time Picker</sub></td>
    <td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Settings.png" width="180"><br><sub>Settings</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Apple-Calendar-Connect.png" width="180"><br><sub>iCloud Connect</sub></td>
    <td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Sync-with-iCloud.png" width="180"><br><sub>iCloud Sync</sub></td>
    <td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/ICS-Subscription.png" width="180"><br><sub>ICS Subscriptions</sub></td>
    <td></td>
  </tr>
</table>

---

## Why KashCal?

| | KashCal | Others |
|---|---------|--------|
| Analytics | None | Often |
| Account Required | No | Usually |
| Works Offline | Full | Limited |
| Open Source | Yes | Rarely |
| iCloud on Android | Yes | Rare |

## Features

- **Privacy-First** — Zero analytics, zero tracking. Your schedule stays private.
- **Offline-First** — Works without internet. Sync when you want, not when the app wants.
- **iCloud Sync** — Native CalDAV support for Apple Calendar on Android.
- **Material You** — Beautiful, modern design with dynamic theming.
- **Home Widget** — Today's agenda at a glance.
- **Recurring Events** — Full RFC 5545 RRULE support with exceptions.
- **Progressive Sync** — Events appear in 2-5 seconds, not 30.
- **Search** — Full-text search across all your events.
- **Timezone Support** — Per-event timezone with smart display.

## How It Works


User Action → Local DB (instant) → Background Sync (only if you're using Apple Calendar)


All operations save locally first. Sync happens in the background — your calendar works even without internet or any external calendar service.

## Security & Privacy

### Your Data Stays Yours
- **No Analytics** — Zero tracking, telemetry, or data collection
- **No Accounts** — No KashCal account required
- **Local-First** — Calendar data stored on your device
- **Open Source** — Fully auditable codebase

### Secure by Design
- **Encrypted Credentials** — AES-256-GCM via Android Keystore
- **HTTPS Only** — Cleartext traffic blocked
- **No WebViews** — Native UI only, no embedded browsers
- **Minimal Permissions** — Only what's necessary

## Download

**[GitHub Releases](https://github.com/KashCal/KashCal/releases)** — Download the latest APK


## Building from Source

### Prerequisites
- Android Studio (latest stable)
- JDK 17
- Android SDK 35

### Debug Build
```bash
git clone https://github.com/KashCal/KashCal.git
cd KashCal
./gradlew assembleDebug
```

### Release Build
```bash
# Copy local.properties.example to local.properties
# Add your signing credentials
./gradlew assembleRelease
```

### Run Tests
```bash
./gradlew test
```

## Tech Stack

- **UI**: Jetpack Compose + Material 3
- **Widget**: Jetpack Glance
- **DI**: Hilt
- **Database**: Room
- **Async**: Kotlin Coroutines + Flow
- **Background**: WorkManager
- **Network**: OkHttp
- **iCal**: ical4j + lib-recur

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

Apache License 2.0 — see [LICENSE](LICENSE)

Third-party licenses — see [NOTICE](NOTICE)
