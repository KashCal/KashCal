# Contributing to KashCal

This guide covers how to report bugs, propose changes and open pull requests.

## Code of Conduct

This project and everyone participating in it is governed by the [KashCal Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold it.

## Getting Started

### Prerequisites

- Android Studio
- JDK 21
- Android SDK 37

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
   ./gradlew testDebugUnitTest :icaldav-core:test :vcard-core:test
   ```

### Understanding the Codebase

New to the project? [DeepWiki](https://deepwiki.com/KashCal/KashCal) gives you a browsable overview of KashCal's architecture, and you can ask it questions. For the layered architecture in short, see [Architecture Guidelines](#architecture-guidelines) below.

## How to Contribute

### Reporting Bugs

> Found a security vulnerability? Do not open a public issue. Report it privately following our [Security Policy](SECURITY.md).

- Use the [Bug Report](https://github.com/KashCal/KashCal/issues/new?template=bug_report.yml) template
- Include your Android version, device, and KashCal version
- Include your sync provider (iCloud, Nextcloud, Radicale, etc.) if the bug involves sync
- Provide steps to reproduce the issue
- Include screenshots or sync logs if applicable

### Suggesting Features

- Use the [Feature Request](https://github.com/KashCal/KashCal/issues/new?template=feature_request.yml) template
- Describe the problem you're trying to solve
- Explain your proposed solution

Open an issue to discuss a feature or enhancement before writing code, so no one spends effort on a change that doesn't fit the roadmap. Every pull request, bug fixes included, must link an issue (`Fixes #123` in the description). Pull requests without one are closed.

### Improving Translations

New user-facing strings are added in English only, and maintainers batch-generate the 67 translations before release, so don't hand-edit locale files in a PR. If you spot a wrong or awkward translation, [open an issue](https://github.com/KashCal/KashCal/issues) with the language, the current text, and your suggested wording. The correction goes into the next translation pass.

### Submitting Code

Keep pull requests focused: one logical change per PR. Small, self-contained PRs are easier to review and land faster than large ones.

1. Find or [open an issue](https://github.com/KashCal/KashCal/issues/new/choose) for the change
2. Fork the repository and create a branch (`git checkout -b fix/month-grid-clipping`)
3. Make your changes and run the tests (`./gradlew testDebugUnitTest :icaldav-core:test :vcard-core:test`)
4. Commit following the [commit convention](#commit-messages)
5. Open a pull request whose description links the issue (`Fixes #123`)

## Code Style

Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).

### Comments and KDoc

Comment what the code can't say: invariants, server and platform behavior, RFC rules, and traps ("a delete and re-insert would lose the starred flag"). Don't restate the code.

- State each rule once, where it is enforced, and link to it with `[Symbol]` elsewhere.
- Describe the current behavior, not its history. Keep `#issue` references.
- Start KDoc with a one-line summary ("Returns..."), then what it returns and when it fails.
- Use plain words. No em dashes, bold or filler ("actually", "robust", "note that").
- When you change code, fix or delete the comments that describe it.

### Commit Messages

KashCal uses [Conventional Commits](https://www.conventionalcommits.org/). Prefix each commit with a type and an optional scope, followed by a short imperative summary:

```
feat(sync): add Radicale collection discovery
fix(widget): stop month grid clipping on small screens
docs: clarify translation workflow
build(deps): bump ical4j to 4.3.0
```

Common types: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `perf`, `chore`. Keep the summary under about 72 characters and explain the *why* in the body if it isn't obvious.

### Architecture Guidelines

KashCal follows a layered architecture:

```
UI (Compose + ViewModels)
    → Domain (EventCoordinator, EventReader)
        → Data (Room DB, CalDAV sync, DataStore)
```

Key principles:

- All data operations go through the domain layer; never access DAOs from ViewModels
- Use Room's Flow for observable data so the UI updates progressively during sync
- Queue all sync mutations through PendingOperation (never fire-and-forget)
- Exception events (modified recurring occurrences) share the master event's UID and are told apart by RECURRENCE-ID (RFC 5545 §3.8.4.4)

## Testing

New behavior is driven by a test: write the failing test first. When a test fails, fix the code, not the test, unless the test itself is wrong.

```bash
# Run all unit tests
./gradlew testDebugUnitTest :icaldav-core:test :vcard-core:test

# Run a specific test class
./gradlew testDebugUnitTest --tests "*EventCoordinatorTest*"

# Run lint
./gradlew lint
```

The integration suite (`-Pintegration`) runs against the maintainers' test accounts and local server containers, so maintainers run it for sync and ICS changes. For those changes, test by hand against at least one CalDAV server you use.

## Pull Request Checklist

The checklist is in the [pull request template](.github/PULL_REQUEST_TEMPLATE.md), which fills in the description when you open a pull request.

## AI Assistance

AI tools (Claude Code, Codex, Copilot, Cursor, and others) are welcome. If you use one, disclose it in your pull request so reviewers can calibrate their review.

Examples:

> This PR was written with GitHub Copilot assistance.

> I used ChatGPT to understand the sync architecture, but the implementation is my own.

Trivial fixes (typos, formatting) don't need disclosure.

We recommend building AI-assisted contributions with [devloop](https://github.com/OneKashLabs/devloop) (`/spec`, `/plan`, `/implement`).

Running devloop's `review-impl` agent over your diff with the correctness and cleanup lenses is required for AI-assisted pull requests. It reads the diff in a fresh context and checks it for bugs and cleanup issues. Fix the findings in your change before opening the pull request. To run it in Claude Code:

```
/plugin marketplace add OneKashLabs/devloop
/plugin install devloop@onekashlabs
```

Then add this repository as the `upstream` remote (`git remote add upstream https://github.com/KashCal/KashCal.git && git fetch upstream`) and run the review once per lens:

```
Use the review-impl agent with lenses: correctness on upstream/main...HEAD, no tracker
Use the review-impl agent with lenses: cleanup on upstream/main...HEAD, no tracker
```

Codex plugins carry no agents, so in Codex give a subagent devloop's [`agents/review-impl.md`](https://github.com/OneKashLabs/devloop/blob/main/agents/review-impl.md) prompt with the same lens and range.

Contributions that appear to be bulk AI-generated without human review or testing may be closed.

## CalDAV Server Testing

If you find a CalDAV server that doesn't work with KashCal, [open an issue](https://github.com/KashCal/KashCal/issues) with the server software and version. The integration suite runs against iCloud, Nextcloud, Radicale, Baikal (also with digest auth), Stalwart, Zoho, SOGo, mailbox.org, Cyrus, and Xandikos.

KashCal also integrates with Android device calendars (Google Calendar, Samsung Calendar, etc.) via CalendarProvider. If you hit a problem with a specific device calendar app, include the app name and Android version in your report.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
