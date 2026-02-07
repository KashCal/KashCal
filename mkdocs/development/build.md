# Build Guide

## Prerequisites

- JDK 17+
- Android SDK (API 35)
- Android Studio (optional, for IDE features)

## Quick Build

```bash
# Debug build
./gradlew assembleDebug

# Run tests
./gradlew test

# Specific test
./gradlew test --tests "*EventsDao*"

# Clean build
./gradlew clean assembleDebug
```

## Project Structure

```
KashCal/
├── app/
│   ├── src/main/kotlin/org/onekash/kashcal/
│   │   ├── data/           # Room database, credentials
│   │   ├── domain/         # Business logic
│   │   ├── sync/           # CalDAV synchronization
│   │   ├── ui/             # Compose UI
│   │   ├── widget/         # Home screen widgets
│   │   ├── reminder/       # Notification scheduling
│   │   └── di/             # Hilt modules
│   ├── src/test/           # Unit tests
│   └── src/androidTest/    # Instrumented tests
├── icaldav/                # CalDAV parsing library
├── gradle/                 # Version catalog
└── releases/               # Signed APKs
```

## Version Management

Versions are managed in `version.properties`:

```properties
VERSION_CODE=244
VERSION_NAME=21.5.8
```

## Signing Configuration

### Local Development

Create `local.properties`:

```properties
KEYSTORE_FILE=/path/to/keystore.jks
KEYSTORE_PASSWORD=your_password
KEY_ALIAS=release
KEY_PASSWORD=your_key_password
```

### CI/GitHub Actions

Set GitHub Secrets:

- `KEYSTORE_BASE64` - Base64-encoded keystore
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## Release Process

### Private Testing

```bash
# 1. Update version.properties
# 2. Build release APK
./gradlew clean assembleRelease

# 3. Copy to releases/
cp app/build/outputs/apk/release/app-release.apk releases/KashCal-X.Y.Z-release.apk

# 4. Verify signature
apksigner verify -v releases/KashCal-X.Y.Z-release.apk
```

### Public Release

**CRITICAL:** Use CI for public releases (F-Droid reproducibility).

```bash
# 1. Cherry-pick to public repo
cd /path/to/public-KashCal
git cherry-pick <commit>
git push origin main

# 2. Create tag
git tag vX.Y.Z
git push origin vX.Y.Z

# 3. Create release WITHOUT APK
gh release create vX.Y.Z --repo KashCal/KashCal \
  --title "vX.Y.Z" --notes "Release notes"

# 4. CI automatically builds and attaches APK
```

## Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.1.0 | Language |
| Compose BOM | 2024.12.01 | UI framework |
| Room | 2.7.0 | Database |
| Hilt | 2.53.1 | Dependency injection |
| OkHttp | 4.12.0 | HTTP client |
| WorkManager | 2.10.0 | Background jobs |
| Glance | 1.1.1 | Widgets |

## Testing

### Unit Tests (Robolectric)

```bash
./gradlew testDebugUnitTest
```

Tests run with Robolectric for Android framework mocking.

### Test Coverage

```bash
./gradlew koverHtmlReport
```

Report generated at `app/build/reports/kover/html/`.

## Database Schema

Schema exported to `app/schemas/` for migration tracking:

```bash
ls app/schemas/org.onekash.kashcal.data.db.KashCalDatabase/
# 1.json, 2.json, ..., 12.json
```

## Code Generation

KSP generates Room DAOs and Hilt components:

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}
```

## ProGuard

Release builds use ProGuard for optimization:

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

## Troubleshooting

### Robolectric JIT Crash

If tests crash with JIT errors:

```kotlin
testOptions {
    unitTests {
        it.jvmArgs("-XX:TieredStopAtLevel=1")  // Disable C2 JIT
    }
}
```

### ical4j Service Loader Conflicts

Handled via `pickFirsts`:

```kotlin
packagingOptions {
    pickFirsts += "META-INF/services/net.fortuna.ical4j.model.ComponentFactory"
    pickFirsts += "META-INF/services/net.fortuna.ical4j.model.PropertyFactory"
}
```
