package org.onekash.kashcal.data.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.Contacts
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.onekash.kashcal.di.IoDispatcher
import org.onekash.kashcal.util.AddressNormalizer
import javax.inject.Inject
import javax.inject.Singleton

/** One contact email suggestion: a display name (may be blank) and its address. */
data class ContactEmail(
    val displayName: String,
    val address: String,
)

/**
 * Reads contact email addresses for the attendee picker's type-ahead.
 *
 * Queries the Email rows of the Contacts provider filtered by a typed prefix
 * ([Email.CONTENT_FILTER_URI] with the prefix as an appended path segment),
 * with a narrow projection (contact name + address only — the provider docs
 * warn that fetching all detail columns hurts performance), off the main
 * thread. Distinct from [ContactEventManager], which reads birthday/anniversary
 * START_DATE rows and never projects an email column.
 *
 * Name comes from [Contacts.DISPLAY_NAME] (the joined contact's name), NOT
 * [Email.DISPLAY_NAME] — the latter is the per-email-row label (DATA4), which
 * is almost always blank, so projecting it made every suggestion render as a
 * bare address even when matched by name.
 *
 * The query is gated on READ_CONTACTS: without the grant it returns empty
 * rather than throwing, so the picker degrades to manual email entry.
 */
@Singleton
class ContactEmailReader(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    // Hilt can't inject a ContentResolver directly, so the injected entry
    // point derives it from the application context; the primary constructor
    // stays resolver-injectable so tests can supply a fake.
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(context, context.contentResolver, ioDispatcher)

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Return contact emails whose name or address matches [prefix], de-duped by
     * canonical address and capped at [LIMIT]. Empty when the prefix is blank
     * or READ_CONTACTS isn't granted.
     */
    suspend fun query(prefix: String): List<ContactEmail> {
        val trimmed = prefix.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!hasPermission()) return emptyList()

        return withContext(ioDispatcher) {
            val uri = Uri.withAppendedPath(Email.CONTENT_FILTER_URI, Uri.encode(trimmed))
            val projection = arrayOf(Contacts.DISPLAY_NAME, Email.DATA)
            val seen = HashSet<String>()
            val results = ArrayList<ContactEmail>()
            try {
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(Contacts.DISPLAY_NAME)
                    val addrIdx = cursor.getColumnIndex(Email.DATA)
                    if (addrIdx < 0) return@use
                    while (cursor.moveToNext() && results.size < LIMIT) {
                        val address = cursor.getString(addrIdx)?.trim().orEmpty()
                        if (address.isEmpty()) continue
                        // Contact rows are bare emails; canonical() only
                        // lowercases mailto: forms, so lowercase the dedup key
                        // to collapse the same address typed in mixed case
                        // across two contact rows. Display keeps original case.
                        if (!seen.add(AddressNormalizer.canonical(address).lowercase())) continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                        results.add(ContactEmail(displayName = name, address = address))
                    }
                }
            } catch (e: SecurityException) {
                // Permission revoked between the check and the query — degrade.
                Log.w(TAG, "Contacts query denied: ${e.javaClass.simpleName}")
            }
            results
        }
    }

    private companion object {
        const val TAG = "ContactEmailReader"
        const val LIMIT = 50
    }
}
