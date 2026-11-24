## Description

What the change does and why.

## Related Issue

<!-- Required. Pull requests without a linked issue are closed. -->
Fixes #(issue number)

## Type of Change

- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update

## Checklist

- [ ] I have read [CONTRIBUTING](https://github.com/KashCal/KashCal/blob/main/CONTRIBUTING.md)
- [ ] Tests cover the change, written before the code, and `./gradlew testDebugUnitTest :icaldav-core:test :vcard-core:test` passes
- [ ] `./gradlew assembleDebug` and `./gradlew lint` pass
- [ ] New user-facing text is in `values/strings.xml` in English only, and no other locale file is edited
- [ ] ViewModels use `EventCoordinator` / `EventReader`, not DAOs, and sync changes are queued through `PendingOperation`

If the change touches sync or ICS import/export:

- [ ] Tested against at least one CalDAV server
- [ ] Credentials, sync tokens and passwords are never logged in full

If the change touches the UI:

- [ ] Inline banners instead of blocking dialogs, undo for reversible actions, and confirmation dialogs only for destructive or irreversible ones

## AI Disclosure

If you used AI assistance (Claude Code, Codex, Copilot, Cursor, etc.) while working on this PR, disclose it here so reviewers can calibrate their review.

- [ ] No AI assistance was used
- [ ] AI assisted with: ___
- [ ] If AI assisted, I ran [devloop](https://github.com/OneKashLabs/devloop)'s `review-impl` agent with the correctness and cleanup lenses and fixed what it found

## Screenshots (if applicable)

Add screenshots for UI changes.