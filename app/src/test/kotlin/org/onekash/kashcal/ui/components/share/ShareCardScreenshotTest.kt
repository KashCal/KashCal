package org.onekash.kashcal.ui.components.share

import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.screenshot.ScreenshotMatrix
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual-regression goldens for the share card, the image users share publicly, so a layout
 * regression here ships a broken image. Rendered on the JVM through Robolectric native graphics
 * (no emulator); goldens live in src/test/screenshots/. [ShareCardFixtures] shares the inputs with
 * the behavioral test so goldens and assertions can't drift apart. Inert in the normal test
 * sweep; [ScreenshotMatrix] has the record and verify commands.
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
