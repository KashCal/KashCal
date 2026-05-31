package org.onekash.kashcal.util

import android.content.ComponentName
import android.content.Intent
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
