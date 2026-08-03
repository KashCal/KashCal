package org.onekash.kashcal.sync.adapter

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies the manifest + resource wiring that makes a per-login contacts
 * account appear in Android as its OWN source, independent of the singleton
 * "KashCal" calendar account.
 *
 * Contacts need a dedicated account type (`org.onekash.kashcal.contacts`) with
 * its own authenticator + sync-adapter so a per-login, email-named account can
 * be registered without colliding with the calendar type. Without the
 * registered type Android would purge any RawContacts written under it, and
 * without `WRITE_CONTACTS` the sync adapter could never write them. This guard
 * fails loudly the day any of that wiring regresses.
 *
 * Split responsibilities:
 * - PackageManager (Robolectric parses the merged manifest) proves the app
 *   *requests* `WRITE_CONTACTS` and *declares* both contacts services.
 * - A source scan of the two `res/xml` resources proves the account type and
 *   content authority are the contacts-specific values and differ from the
 *   calendar type — PackageManager can't read the meta-data XML contents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactsAccountManifestTest {

    private val pm: PackageManager =
        ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
    private val pkg: String =
        ApplicationProvider.getApplicationContext<android.content.Context>().packageName

    private companion object {
        const val CONTACTS_ACCOUNT_TYPE = "org.onekash.kashcal.contacts"
        const val CALENDAR_ACCOUNT_TYPE = "org.onekash.kashcal"
        const val CONTACTS_AUTHORITY = "com.android.contacts"

        fun resXmlRoot(): File {
            val relative = "src/main/res/xml"
            val candidates = listOf(File(relative), File("app/$relative"))
            return candidates.firstOrNull { it.isDirectory }
                ?: error(
                    "Could not locate res/xml from working dir " +
                        "'${File(".").absolutePath}'. Tried: " +
                        candidates.joinToString { it.path }
                )
        }
    }

    @Test
    fun `app requests WRITE_CONTACTS permission`() {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList() ?: emptyList()
        assertTrue(
            "Manifest must request WRITE_CONTACTS so the contacts sync adapter can " +
                "write RawContacts. Requested: $requested",
            requested.contains("android.permission.WRITE_CONTACTS")
        )
    }

    @Test
    fun `contacts authenticator and sync-adapter services are declared`() {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES)
        val serviceNames = info.services?.map { it.name }?.toSet() ?: emptySet()

        assertTrue(
            "Contacts authenticator service must be declared. Declared: $serviceNames",
            serviceNames.contains(
                "org.onekash.kashcal.sync.adapter.KashCalContactsAuthenticatorService"
            )
        )
        assertTrue(
            "Contacts sync-adapter service must be declared. Declared: $serviceNames",
            serviceNames.contains(
                "org.onekash.kashcal.sync.adapter.KashCalContactsSyncAdapterService"
            )
        )
    }

    @Test
    fun `contacts authenticator xml uses the dedicated contacts account type`() {
        val xml = File(resXmlRoot(), "kashcal_contacts_authenticator.xml").readText()
        assertTrue(
            "Authenticator XML must declare accountType=\"$CONTACTS_ACCOUNT_TYPE\"",
            xml.contains("android:accountType=\"$CONTACTS_ACCOUNT_TYPE\"")
        )
        assertNotEquals(
            "Contacts account type must differ from the calendar type so the two " +
                "accounts stay independent sources",
            CALENDAR_ACCOUNT_TYPE,
            CONTACTS_ACCOUNT_TYPE
        )
    }

    @Test
    fun `contacts sync-adapter xml binds the contacts type to the contacts authority`() {
        val xml = File(resXmlRoot(), "kashcal_contacts_syncadapter.xml").readText()
        assertTrue(
            "Sync-adapter XML must declare contentAuthority=\"$CONTACTS_AUTHORITY\"",
            xml.contains("android:contentAuthority=\"$CONTACTS_AUTHORITY\"")
        )
        assertTrue(
            "Sync-adapter XML must declare accountType=\"$CONTACTS_ACCOUNT_TYPE\"",
            xml.contains("android:accountType=\"$CONTACTS_ACCOUNT_TYPE\"")
        )
    }
}
