<div align="center">

<img src="images/kashcal-app.png" width="120" alt="KashCal">

# KashCal

**Your calendar. Your device. Your rules.**

Private by default. Syncs with iCloud, Nextcloud, and any CalDAV server — if you choose.

[![F-Droid](https://img.shields.io/f-droid/v/org.onekash.kashcal?logo=fdroid&label=F-Droid)](https://f-droid.org/packages/org.onekash.kashcal)
[![IzzyOnDroid](https://img.shields.io/endpoint?url=https://apt.izzysoft.de/fdroid/api/v1/shield/org.onekash.kashcal&label=IzzyOnDroid)](https://apt.izzysoft.de/fdroid/index/apk/org.onekash.kashcal)
[![GitHub Release](https://img.shields.io/github/v/release/KashCal/KashCal?logo=github&label=GitHub)](https://github.com/KashCal/KashCal/releases)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

<table>
<tr>
<td align="center" width="25%">

🔒

**Private**

Zero tracking.<br>Zero analytics.<br>Ever.

</td>
<td align="center" width="25%">

📴

**Offline**

No account needed.<br>No cloud required.<br>Works anywhere.

</td>
<td align="center" width="25%">

🔄

**Syncs**

iCloud, Nextcloud,<br>Baïkal, Radicale,<br>any CalDAV server.

</td>
<td align="center" width="25%">

🔓

**Open**

Open source.<br>Fully auditable.<br>Apache 2.0.

</td>
</tr>
</table>

---

<table>
<tr>
<td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/HomePage-with-AboutMe.png" width="180"></td>
<td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/NewEvent.png" width="180"></td>
<td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/NewEvent-Date-Time-Picker.png" width="180"></td>
<td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Settings.png" width="180"></td>
</tr>
<tr>
<td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Apple-Calendar-Connect.png" width="180"></td>
<td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Sync-with-iCloud.png" width="180"></td>
<td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/CalDAV-Account.png" width="180"></td>
<td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/ICS-Subscription.png" width="180"></td>
</tr>
</table>

---

</div>

## Why Calendar Privacy Matters

Your calendar reveals your life: doctor appointments, job interviews, who you meet, where you travel. When calendar apps connect to cloud AI systems, this data becomes an attack surface.

**[Recent research](https://www.miggo.io/post/weaponizing-calendar-invites-a-semantic-attack-on-google-gemini)** ([archived](https://web.archive.org/web/20260121052414/https://www.miggo.io/post/weaponizing-calendar-invites-a-semantic-attack-on-google-gemini)) demonstrated how attackers can weaponize calendar invites to extract private data through AI assistants. A single malicious invite can trigger silent exfiltration of your entire schedule.

## Features

- **Privacy-First** — Zero analytics, zero tracking. Your schedule stays private.
- **Offline-First** — Built-in local calendar that never leaves your device. No account, no cloud, no internet required.
- **CalDAV Sync** — Connect to iCloud, Nextcloud, Baïkal, Radicale, or any CalDAV server.
- **ICS Subscriptions** — Subscribe to external calendars (holidays, sports, etc.).
- **Multiple Widgets** — Agenda, date, and week widgets for your home screen.
- **Recurring Events** — Full RFC 5545 RRULE support with exceptions.
- **Full-Text Search** — Find events instantly across all calendars.
- **Material You** — Dynamic theming that matches your wallpaper.
- **Timezone Support** — Per-event timezone with smart display.

## Download

<div align="center">

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="80">](https://f-droid.org/packages/org.onekash.kashcal)
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" height="80">](https://apt.izzysoft.de/fdroid/index/apk/org.onekash.kashcal)

**[Download from GitHub Releases](https://github.com/KashCal/KashCal/releases)**

</div>

## Security

| | |
|---|---|
| **Encrypted Credentials** | AES-256-GCM via Android Keystore |
| **HTTPS Only** | Cleartext traffic blocked |
| **No WebViews** | Native UI only |
| **Minimal Permissions** | Only what's necessary |

## Tested CalDAV Providers

| Provider | Status |
|----------|--------|
| iCloud | ✓ |
| Nextcloud | ✓ |
| Baïkal | ✓ |
| Radicale | ✓ |

Found a CalDAV server that doesn't work? [Let us know](https://github.com/KashCal/KashCal/issues)!

---

<details>
<summary><strong>For Developers</strong></summary>

### Tech Stack

| Category | Technology |
|----------|------------|
| CalDAV/ICS | [iCalDAV](https://github.com/icaldav/icaldav) |
| UI | Jetpack Compose, Material 3 |
| Widgets | Jetpack Glance |
| Database | Room + FTS4 full-text search |
| Security | Android Keystore (AES-256-GCM) |
| DI | Hilt |
| Async | Kotlin Coroutines + Flow |
| Background | WorkManager |
| Network | OkHttp |

### Building from Source

```bash
git clone https://github.com/KashCal/KashCal.git
cd KashCal
./gradlew assembleDebug
```

### Requirements
- Android Studio (latest stable)
- JDK 17
- Android SDK 35

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

</details>

---

<div align="center">

**[Website](https://kashcal.github.io)** · **[Issues](https://github.com/KashCal/KashCal/issues)** · **[Releases](https://github.com/KashCal/KashCal/releases)**

Apache License 2.0 — see [LICENSE](LICENSE)

</div>
