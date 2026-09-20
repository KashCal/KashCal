# Screenshot goldens

Reference PNGs for the JVM screenshot tests (Roborazzi + Robolectric native
graphics — no emulator or device). These committed images are the baseline the
CI visual-regression check compares each pull request against.

## Regenerating goldens

After an **intentional** UI change to a covered surface, re-record, review the
changed PNGs, and commit them:

    ./gradlew :app:testDebugUnitTest --tests "*Screenshot*" -Proborazzi.test.record=true

## Verifying locally

    ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests "*Screenshot*" -Proborazzi.test.verify=true --no-build-cache

(`cleanTest` + `--no-build-cache` force the test to actually run — otherwise
Gradle can restore a cached result and skip the comparison.) `scripts/preflight.sh`
runs this too (skip with `--skip-screenshots`).

## Determinism constraint

Record goldens on **Linux x86_64** — the same platform family as the CI runner.
Rendering on a different OS or architecture can shift text anti-aliasing enough
to exceed the comparison threshold and produce false diffs. Do not record on
macOS or arm.
