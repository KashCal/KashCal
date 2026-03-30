# Contributing to KashCal

Thank you for your interest in contributing to KashCal! This document provides guidelines and instructions for contributing.

## Getting Started

### Prerequisites

- Android Studio (latest stable)
- JDK 17 or higher
- Android SDK 35

### Development Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/KashCal/KashCal.git
   cd KashCal
   ```

2. Build the project:
   ```bash
   ./gradlew assembleDebug
   ```

3. Run tests:
   ```bash
   ./gradlew test
   ```

## How to Contribute

### Reporting Bugs

- Use the [Bug Report](https://github.com/KashCal/KashCal/issues/new?template=bug_report.md) template
- Include your Android version, device, and KashCal version
- Include your sync provider (iCloud, Nextcloud, Radicale, etc.) if the bug involves sync
- Provide steps to reproduce the issue
- Include screenshots or sync logs if applicable

### Suggesting Features

- Use the [Feature Request](https://github.com/KashCal/KashCal/issues/new?template=feature_request.md) template
- Describe the problem you're trying to solve
- Explain your proposed solution

### Submitting Code

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Run tests (`./gradlew test`)
5. Commit your changes (`git commit -m 'Add amazing feature'`)
6. Push to the branch (`git push origin feature/amazing-feature`)
7. Open a Pull Request

## Code Style

- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Keep functions small and focused
- Add comments for non-obvious logic only (don't comment what the code already says)

### Architecture Guidelines

KashCal follows a layered architecture:

```
UI (Compose + ViewModels)
    → Domain (EventCoordinator, EventReader)
        → Data (Room DB, CalDAV sync, DataStore)
```

Key principles:

- All data operations go through the domain layer — never access DAOs from ViewModels
- Use Room's Flow for observable data so the UI updates progressively during sync
- Queue all sync mutations through PendingOperation (never fire-and-forget)
- Exception events (modified recurring occurrences) share the master event's UID per RFC 5545

## Testing

- Write unit tests for new functionality
- Ensure existing tests pass before submitting a PR
- If modifying sync code, test with at least one CalDAV server (iCloud, Nextcloud, Radicale, Baikal, etc.)

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew test --tests "*EventCoordinatorTest*"

# Run lint
./gradlew lint
```

## Pull Request Process

1. Update documentation if needed
2. Add tests for new functionality
3. Ensure CI passes
4. Disclose AI assistance (see below)
5. Request review from maintainers

## AI Assistance

We welcome the use of AI tools (Copilot, ChatGPT, Claude, etc.) in contributions. If you use AI assistance, please disclose it in your pull request so reviewers can calibrate their review accordingly.

Examples:

> This PR was written with GitHub Copilot assistance.

> I used ChatGPT to understand the sync architecture, but the implementation is my own.

Trivial fixes (typos, formatting) don't need disclosure.

Contributions that appear to be bulk AI-generated without human review or testing may be closed.

## CalDAV Server Testing

If you find a CalDAV server that doesn't work with KashCal, please [open an issue](https://github.com/KashCal/KashCal/issues) with the server software and version. We actively test against iCloud, Nextcloud, Radicale, Baikal, Stalwart, Zoho, SoGo, and FastMail.

KashCal also integrates with Android device calendars (Google Calendar, Samsung Calendar, etc.) via CalendarProvider. If you encounter issues with a specific device calendar app, please include the app name and Android version in your report.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
