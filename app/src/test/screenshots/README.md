# Screenshot goldens

Reference PNGs for the JVM screenshot tests (Roborazzi with Robolectric native
graphics, no emulator or device). The CI visual-regression step compares each
pull request against these committed images; it is non-blocking for now
(`continue-on-error` in `.github/workflows/build.yml`).

## Regenerating goldens

After an intended UI change to a covered surface, re-record, review the changed
PNGs, and commit them:

    ./gradlew :app:testDebugUnitTest --tests "*Screenshot*" -Proborazzi.test.record=true

## Verifying locally

    ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests "*Screenshot*" -Proborazzi.test.verify=true --no-build-cache

`cleanTest` and `--no-build-cache` make the test task run; otherwise Gradle can
restore a cached result and skip the comparison. The maintainers' local
`scripts/preflight.sh` (not in the repository) runs this too, unless given
`--skip-screenshots`.

## Record on Linux x86_64

Record goldens on Linux x86_64, the same platform family as the CI runner.
Rendering on another OS or architecture can shift text anti-aliasing past the
comparison threshold and produce false diffs, so don't record on macOS or arm.
