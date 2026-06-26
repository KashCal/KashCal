package org.onekash.kashcal.data.contacts

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.Contacts
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Unit tests for [ContactEmailReader]. Robolectric supplies a real Context for
 * the READ_CONTACTS permission check (granted/denied via the shadow), while a
 * mocked [ContentResolver] + [Cursor] feeds rows — the convention used by the
 * other contacts repository tests. Exercises the permission gate, the
 * blank-prefix short-circuit, and the cursor→model mapping with dedup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContactEmailReaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val contentResolver: ContentResolver = mockk(relaxed = true)

    private fun reader() = ContactEmailReader(context, contentResolver, Dispatchers.Unconfined)

    private fun grant() =
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)

    private fun deny() =
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)

    /** Mock a 2-column cursor (contact name, address) over the given (name,address) rows. */
    private fun cursorOf(vararg rows: Pair<String?, String?>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        // Reader resolves columns by name; give it stable indices 0/1. The name
        // column is the joined contact name (Contacts.DISPLAY_NAME), NOT the
        // per-email-row label (Email.DISPLAY_NAME / DATA4).
        every { cursor.getColumnIndex(any()) } answers {
            when (firstArg<String>()) {
                Contacts.DISPLAY_NAME -> 0
                Email.DATA -> 1
                else -> -1
            }
        }
        val moves = rows.map { true } + false
        every { cursor.moveToNext() } returnsMany moves
        every { cursor.getString(0) } returnsMany rows.map { it.first }
        every { cursor.getString(1) } returnsMany rows.map { it.second }
        return cursor
    }

    @Test
    fun `returns empty when permission not granted`() = runTest {
        deny()
        assertTrue(reader().query("al").isEmpty())
    }

    @Test
    fun `returns empty for blank prefix without touching the resolver`() = runTest {
        grant()
        assertTrue(reader().query("   ").isEmpty())
    }

    @Test
    fun `maps cursor rows to contact emails when granted`() = runTest {
        grant()
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns
            cursorOf("Alice Chen" to "alice@example.com", "Bob" to "bob@work.example.com")
        assertEquals(
            listOf(
                ContactEmail(displayName = "Alice Chen", address = "alice@example.com"),
                ContactEmail(displayName = "Bob", address = "bob@work.example.com"),
            ),
            reader().query("al"),
        )
    }

    @Test
    fun `dedups rows that share a canonical address`() = runTest {
        grant()
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns
            cursorOf("Alice Chen" to "alice@example.com", "Alice (work)" to "Alice@Example.com")
        assertEquals(1, reader().query("al").size)
    }

    @Test
    fun `skips rows with a blank address`() = runTest {
        grant()
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns
            cursorOf("Ghost" to "")
        assertTrue(reader().query("gh").isEmpty())
    }

    @Test
    fun `returns empty when the resolver yields null`() = runTest {
        grant()
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null
        assertTrue(reader().query("al").isEmpty())
    }

    @Test
    fun `projects the joined contact name column, not the per-email-row label`() = runTest {
        // Email.DISPLAY_NAME (DATA4) is the per-email label and is almost always
        // blank, so projecting it rendered every suggestion as a bare address
        // even when the filter matched the contact's name. The projection must
        // request Contacts.DISPLAY_NAME (the joined contact name).
        grant()
        val projectionSlot = slot<Array<String>>()
        every {
            contentResolver.query(any(), capture(projectionSlot), any(), any(), any())
        } returns cursorOf("Alice Chen" to "alice@example.com")

        reader().query("al")

        assertTrue(
            "projection must request the joined contact name",
            projectionSlot.captured.contains(Contacts.DISPLAY_NAME),
        )
        assertTrue(
            "projection must not request the per-email-row label (DATA4)",
            !projectionSlot.captured.contains(Email.DISPLAY_NAME),
        )
    }
}
