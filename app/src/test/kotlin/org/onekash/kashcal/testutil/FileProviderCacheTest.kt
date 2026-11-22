package org.onekash.kashcal.testutil

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Tests [resetFileProviderCache] against roots cached from another test's cache directory. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileProviderCacheTest {

    @Test
    fun `reset lets a lookup succeed after another cache directory was cached`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val authority = "${context.packageName}.fileprovider"
        // An earlier test on this SDK may have cached roots already; this lookup needs a clean map.
        resetFileProviderCache()
        val otherCache = File(context.cacheDir.parentFile, "other-test-cache")
        val otherTest = object : ContextWrapper(context) {
            override fun getCacheDir() = otherCache
        }
        FileProvider.getUriForFile(otherTest, authority, sharedFile(otherCache))
        val file = sharedFile(context.cacheDir)
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, authority, file)
        }

        resetFileProviderCache()

        assertEquals("content", FileProvider.getUriForFile(context, authority, file).scheme)
    }

    private fun sharedFile(cacheDir: File) =
        File(cacheDir, "shared/probe.ics").apply { parentFile!!.mkdirs(); writeText("") }
}
