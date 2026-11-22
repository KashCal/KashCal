package org.onekash.kashcal.util

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.MainActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareChooserTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `createKashCalChooser excludes own MainActivity`() {
        val payload = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Hello")
        }

        val chooser = ShareChooser.createKashCalChooser(context, payload, "Share")

        @Suppress("UNCHECKED_CAST")
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)
            as? Array<Parcelable>

        assertNotNull(
            "EXTRA_EXCLUDE_COMPONENTS must be set so KashCal does not appear in its own share sheet",
            excluded
        )
        val components = excluded!!.filterIsInstance<ComponentName>()
        assertEquals(1, components.size)
        assertEquals(MainActivity::class.java.name, components[0].className)
        assertEquals(context.packageName, components[0].packageName)
    }

    @Test
    fun `createFileChooser sends the file with its type and a read grant`() {
        val uri = Uri.parse("content://org.onekash.kashcal.fileprovider/shared_ics/kashcal-debug-report-20261022-1403.txt")

        val chooser = ShareChooser.createFileChooser(context, uri, "text/plain", "Share debug report")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share debug report", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(uri, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.toList()
        assertEquals(listOf(ComponentName(context, MainActivity::class.java)), excluded)
    }
}
