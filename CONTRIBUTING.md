# Contributing to KashCal

Thank you for your interest in contributing to KashCal!

## Code of Conduct

This project and everyone participating in it is governed by the [KashCal Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold it.

## Getting Started

### Prerequisites

- Android Studio (latest stable)
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
   ./gradlew testDebugUnitTest
   ```

### Understanding the Codebase

New to the project? [DeepWiki](https://deepwiki.com/KashCal/KashCal) gives you a browsable overview of KashCal's architecture, and you can ask it questions. For the layered architecture in short, see [Architecture Guidelines](#architecture-guidelines) below.

## How to Contribute

### Reporting Bugs

> **Found a security vulnerability?** Do not open a public issue. Report it privately following our [Security Policy](SECURITY.md).

- Use the [Bug Report](https://github.com/KashCal/KashCal/issues/new?template=bug_report.yml) template
- Include your Android version, device, and KashCal version
- Include your sync provider (iCloud, Nextcloud, Radicale, etc.) if the bug involves sync
- Provide steps to reproduce the issue
- Include screenshots or sync logs if applicable

### Suggesting Features

- Use the [Feature Request](https://github.com/KashCal/KashCal/issues/new?template=feature_request.yml) template
- Describe the problem you're trying to solve
- Explain your proposed solution

Please open an issue to discuss a feature or enhancement before writing code, so no one spends effort on a change that doesn't fit the roadmap. Bug fixes can go straight to a pull request.

### Improving Translations

New user-facing strings are added in English only, and maintainers batch-generate the 67 translations before release, so please don't hand-edit locale files in a PR. If you spot a wrong or awkward translation, [open an issue](https://github.com/KashCal/KashCal/issues) with the language, the current text, and your suggested wording. The correction goes into the next translation pass.

### Submitting Code

Keep pull requests focused: one logical change per PR. Small, self-contained PRs are easier to review and land faster than large ones. For features and enhancements, [discuss the change in an issue first](#suggesting-features).

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Run tests (`./gradlew testDebugUnitTest`)
5. Commit your changes following the [commit convention](#commit-messages) (`git commit -m 'feat: add amazing feature'`)
6. Push to the branch (`git push origin feature/amazing-feature`)
7. Open a Pull Request

## Code Style

- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Keep functions small and focused

### Comments and KDoc

Comment what the code can't say: invariants, consequences ("if X, Y breaks"), server and platform behavior, and RFC rules. Don't restate the code.

- Write the rule once, where it is enforced or decided, and link to it from elsewhere with `[Symbol]`. Every `[Symbol]` must name a real declaration.
- State the present rule, not its history. Git has the history; keep `#issue` references.
- Name the test that enforces a rule (`ContactsProviderWriteBoundaryTest`) so it can be found.
- KDoc starts with a one-line summary ("Deletes...", "Returns..."), then the contract: what it returns, when it fails, what it never does.
- Keep the alternative a future editor would reach for when it is a trap ("a delete and re-insert would lose the starred flag").
- Plain words, short sentences. No em dashes, bold, ALL-CAPS emphasis or filler ("actually", "correctly", "robust", "note that").
- If you change code, update or delete the comments that describe it.

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

- Write unit tests for new functionality
- Ensure existing tests pass before submitting a PR
- If modifying sync code, test with at least one CalDAV server (iCloud, Nextcloud, Radicale, Baikal, etc.)

```bash
# Run all unit tests (~1 min)
./gradlew testDebugUnitTest

# Run a specific test class
./gradlew testDebugUnitTest --tests "*EventCoordinatorTest*"

# Run lint
./gradlew lint
```

If you're changing sync or ICS import/export, also run the integration suite against real CalDAV servers with the `-Pintegration` flag. It is excluded by default because it is slower (~13 min):

```bash
./gradlew testDebugUnitTest -Pintegration
```

## Pull Request Checklist

Before opening a PR, please confirm:

- [ ] Tests cover the change and `./gradlew testDebugUnitTest` passes. New behavior is driven by a test. Write the failing test first, and when a test fails, fix the code, not the test, unless the test itself is wrong.
- [ ] No hardcoded user-facing text. New strings go in `app/src/main/res/values/strings.xml` in English only. Maintainers generate the other translations before release, so don't hand-edit the other locale files.
- [ ] Data access stays in the domain layer. ViewModels use `EventCoordinator` / `EventReader`, never DAOs directly, and sync mutations are queued through `PendingOperation`.
- [ ] `./gradlew lint` passes.
- [ ] AI assistance is disclosed (see below).

If your change touches sync or ICS import/export:

- [ ] Tested against at least one real CalDAV server (iCloud, Nextcloud, Radicale, Baikal, and so on).
- [ ] Credentials, sync tokens, and passwords are never logged in full.

If your change touches the UI:

- [ ] It follows KashCal's inline-over-interrupting approach: prefer inline banners to blocking dialogs, use undo for reversible actions, and reserve confirmation dialogs for destructive or irreversible ones.

## AI Assistance

We welcome the use of AI tools (Claude Code, Codex, Copilot, Cursor, etc.) in contributions. If you use AI assistance, please disclose it in your pull request so reviewers can calibrate their review.

If you're building your contribution with AI, we recommend [devloop](https://github.com/KashZod/devloop), a test-driven, review-gated workflow. It has the AI write a failing test first, implement against the project's architecture rules, and run independent review passes before the work is considered done.

At a minimum, if you use AI, run devloop's red-team review over your changes before opening the PR. It is an adversarial pass that checks the diff for correctness bugs and cleanup issues.

Examples:

> This PR was written with GitHub Copilot assistance.

> I used ChatGPT to understand the sync architecture, but the implementation is my own.

Trivial fixes (typos, formatting) don't need disclosure.

Contributions that appear to be bulk AI-generated without human review or testing may be closed.

## CalDAV Server Testing

If you find a CalDAV server that doesn't work with KashCal, please [open an issue](https://github.com/KashCal/KashCal/issues) with the server software and version. The integration suite runs against iCloud, Nextcloud, Radicale, Baikal (also with digest auth), Stalwart, Zoho, SOGo, mailbox.org, Fastmail, Cyrus, and Xandikos.

KashCal also integrates with Android device calendars (Google Calendar, Samsung Calendar, etc.) via CalendarProvider. If you encounter issues with a specific device calendar app, please include the app name and Android version in your report.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
