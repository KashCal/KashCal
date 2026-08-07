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

        fun mapperFile(): String {
            val relative = "src/main/kotlin/org/onekash/kashcal/data/contacts/VCardContactMapper.kt"
            val candidates = listOf(File(relative), File("app/$relative"))
            return candidates.firstOrNull { it.isFile }?.path
                ?: error("Could not locate VCardContactMapper.kt from '${File(".").absolutePath}'")
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

    @Test
    fun `contacts sync-adapter service declares the CONTACTS_STRUCTURE metadata`() {
        val info = pm.getServiceInfo(
            android.content.ComponentName(
                pkg,
                "org.onekash.kashcal.sync.adapter.KashCalContactsSyncAdapterService"
            ),
            PackageManager.GET_META_DATA
        )
        val metaData = info.metaData
        assertTrue(
            "Sync-adapter service must carry the android.provider.CONTACTS_STRUCTURE " +
                "meta-data — without it the account type is not a recognized contacts " +
                "source, so its contacts never appear in 'Contacts to display'. " +
                "Present keys: ${metaData?.keySet()}",
            metaData != null && metaData.containsKey("android.provider.CONTACTS_STRUCTURE")
        )
    }

    @Test
    fun `contacts structure xml declares every data kind the mapper actually writes`() {
        val xml = File(resXmlRoot(), "contacts.xml").readText()
        assertTrue("must open a ContactsAccountType/EditSchema", xml.contains("<EditSchema>"))

        // Derive the required kinds from the mapper SOURCE rather than a hand-copied
        // literal, so adding a new row type to VCardContactMapper without declaring it
        // in contacts.xml fails this test (the drift the guard exists to catch). The
        // mapper emits every Data row as `row(<CommonDataKinds type>.CONTENT_ITEM_TYPE)`;
        // scan those type names and translate each to its EditSchema `kind=` token.
        val mapperSrc = File(mapperFile()).readText()
        val emittedTypes = Regex("""row\((\w+)\.CONTENT_ITEM_TYPE""")
            .findAll(mapperSrc)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(
            "sanity: the mapper source scan found no CommonDataKinds rows — the regex or " +
                "path is wrong, not the schema",
            emittedTypes.isNotEmpty()
        )

        // CommonDataKinds inner-class name -> EditSchema kind token.
        val kindFor = mapOf(
            "StructuredName" to "name",
            "Phone" to "phone",
            "Email" to "email",
            "Photo" to "photo",
            "Organization" to "organization",
            "Im" to "im",
            "Nickname" to "nickname",
            "Note" to "note",
            "GroupMembership" to "group_membership",
            "StructuredPostal" to "postal",
            "Website" to "website",
            "Event" to "event",
            "Relation" to "relationship",
        )

        val unmapped = emittedTypes - kindFor.keys
        assertTrue(
            "the mapper emits row type(s) this test can't translate to an EditSchema " +
                "kind: $unmapped — extend kindFor (and declare the kind in contacts.xml)",
            unmapped.isEmpty()
        )

        val missing = emittedTypes.mapNotNull { kindFor[it] }
            .filterNot { kind -> xml.contains("kind=\"$kind\"") }
        assertTrue("contacts.xml is missing DataKind(s) the mapper writes: $missing", missing.isEmpty())
    }
}
