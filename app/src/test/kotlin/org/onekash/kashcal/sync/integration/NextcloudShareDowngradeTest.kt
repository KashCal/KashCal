package org.onekash.kashcal.sync.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials as BasicAuth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.integration.multiserver.CalDavTestServerLoader
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI
import java.util.UUID

/**
 * A shared calendar whose owner takes back write access turns read-only in the app at the next
 * calendar refresh (#388). The owner shares a new calendar read-write with a second user, the
 * app refreshes as that user, then the owner downgrades the share to read-only and the app
 * refreshes again: the same calendar row must now be read-only.
 *
 * Runs against the local Nextcloud container only (the owner and sharee are `NEXTCLOUD_USERNAME`
 * and `NEXTCLOUD_USERNAME_2`). Sharing uses Nextcloud's `oc:share` POST on the calendar. The
 * test deletes only the calendar it created, by the URL it created it at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NextcloudShareDowngradeTest {

    private val http = OkHttpClient()
    private val server = CalDavTestServerLoader.property("NEXTCLOUD_SERVER")?.trimEnd('/')
    private val owner = CalDavTestServerLoader.property("NEXTCLOUD_USERNAME")
    private val ownerPassword = CalDavTestServerLoader.property("NEXTCLOUD_PASSWORD")
    private val sharee = CalDavTestServerLoader.property("NEXTCLOUD_USERNAME_2")
    private val shareePassword = CalDavTestServerLoader.property("NEXTCLOUD_PASSWORD_2")

    private lateinit var db: KashCalDatabase
    private var createdCalendarUrl: String? = null

    @Before
    fun setUp() {
        assumeTrue(
            "Nextcloud owner and sharee credentials not configured",
            server != null && owner != null && ownerPassword != null &&
                sharee != null && shareePassword != null
        )
        assumeTrue("local Nextcloud only", URI(server!!).host in setOf("localhost", "127.0.0.1"))
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        createdCalendarUrl?.let { url ->
            assertEquals("cleanup DELETE $url", 204, send(Request.Builder().url(url).delete()))
        }
    }

    private fun send(request: Request.Builder): Int =
        http.newCall(request.header("Authorization", BasicAuth.basic(owner!!, ownerPassword!!)).build())
            .execute().use { it.code }

    /** Sets the sharee's access to the owner's calendar: `<o:read-write/>`, or read-only. */
    private fun share(calendarUrl: String, readWrite: Boolean) {
        val access = if (readWrite) "<o:read-write/>" else ""
        val body = """<?xml version="1.0"?>
            |<o:share xmlns:d="DAV:" xmlns:o="http://owncloud.org/ns"><o:set>
            |<d:href>principal:principals/users/$sharee</d:href>$access
            |</o:set></o:share>""".trimMargin()
        val code = send(
            Request.Builder().url(calendarUrl)
                .post(body.toRequestBody("application/xml".toMediaType()))
        )
        assertEquals("share POST", 200, code)
    }

    @Test
    fun `refresh marks a shared calendar read-only after the owner revokes write access`() = runBlocking {
        val name = "kashcal-share-downgrade-${UUID.randomUUID()}"
        val calendarUrl = "$server/remote.php/dav/calendars/$owner/$name/"
        assertEquals(
            "MKCALENDAR", 201,
            send(Request.Builder().url(calendarUrl).method("MKCALENDAR", null))
        )
        createdCalendarUrl = calendarUrl
        share(calendarUrl, readWrite = true)

        val credentialManager: CredentialManager = mockk(relaxed = true) {
            coEvery { getCredentials(any()) } returns
                AccountCredentials(username = sharee!!, password = shareePassword!!, serverUrl = server!!)
        }
        val accounts = AccountRepositoryImpl(
            accountsDao = db.accountsDao(),
            addressBookDao = db.addressBookDao(),
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            pendingOperationsDao = db.pendingOperationsDao(),
            credentialManager = credentialManager,
            reminderScheduler = mockk(relaxed = true),
            workManager = mockk(relaxed = true),
            contactSystemAccountRegistrar = mockk(relaxed = true),
            contactsProviderRepository = mockk(relaxed = true),
        )
        val discovery = CalDavAccountDiscoveryService(
            OkHttpCalDavClientFactory(), accounts, CalendarRepositoryImpl(db.calendarsDao())
        )
        val accountId = db.accountsDao().insert(
            Account(
                provider = AccountProvider.CALDAV,
                email = sharee!!,
                homeSetUrl = "$server/remote.php/dav/calendars/$sharee/"
            )
        )

        suspend fun refreshAndFindShared(): Calendar {
            val result = discovery.refreshCalendars(accountId)
            assertTrue("refresh failed: $result", result is DiscoveryResult.Success)
            val shared = db.calendarsDao().getByAccountIdOnce(accountId)
                .firstOrNull { name in it.caldavUrl }
            assertNotNull("shared calendar not listed for the sharee", shared)
            return shared!!
        }

        val writable = refreshAndFindShared()
        assertFalse("read-write share listed as read-only", writable.isReadOnly)

        share(calendarUrl, readWrite = false)
        val downgraded = refreshAndFindShared()

        assertEquals("refresh replaced the calendar row", writable.id, downgraded.id)
        assertTrue("downgraded share still writable", downgraded.isReadOnly)
    }
}
