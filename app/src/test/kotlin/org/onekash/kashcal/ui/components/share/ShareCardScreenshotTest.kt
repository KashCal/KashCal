package org.onekash.kashcal.ui.components.share

import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.screenshot.ScreenshotMatrix
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual-regression goldens for the share-as-card surface — the export artifact
 * users share publicly, so a layout regression here ships a broken image. Rendered
 * on the JVM via Robolectric native graphics (no emulator); goldens live in
 * src/test/screenshots/. Fixtures are shared with the behavioral test via
 * [ShareCardFixtures] so goldens and assertions can't drift apart. Inert in the
 * normal test sweep; see ScreenshotMatrix for record/verify commands.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class ShareCardScreenshotTest {

    @Test
    fun standard_timed_matrix() {
        ScreenshotMatrix.captureMatrix("sharecard_standard") {
            ShareCardFixtures.StandardTimed()
        }
    }

    @Test
    fun all_day_canonical() {
        ScreenshotMatrix.capture("sharecard_allday") {
            ShareCardFixtures.AllDay()
        }
    }

    @Test
    fun celebration_canonical() {
        ScreenshotMatrix.capture("sharecard_celebration") {
            ShareCardFixtures.Celebration()
        }
    }
}
