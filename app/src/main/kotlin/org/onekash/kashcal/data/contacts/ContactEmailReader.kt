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
 * Queries `Email.CONTENT_FILTER_URI` with the typed prefix as a path segment, off the main
 * thread, projecting only the contact name and address (the provider docs warn that fetching
 * all detail columns hurts performance). The birthday and anniversary reader
 * ([BaseContactEventRepository]) is separate and never projects an email column.
 *
 * The name is [Contacts.DISPLAY_NAME], the joined contact's name. [Email.DISPLAY_NAME] is the
 * per-email label (DATA4), usually blank, so projecting it shows a bare address even for a
 * suggestion matched by name.
 *
 * Without READ_CONTACTS the query returns empty instead of throwing, so the picker falls back
 * to manual email entry.
 */
@Singleton
class ContactEmailReader(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    // Hilt can't inject a ContentResolver, so this entry point derives it from the application
    // context; the primary constructor takes one so tests can supply a fake.
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(context, context.contentResolver, ioDispatcher)

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Returns contact emails whose name or address matches [prefix], de-duplicated by
     * canonical address and capped at [LIMIT]. Empty when the prefix is blank or READ_CONTACTS
     * isn't granted.
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
                        // canonical() folds bare emails; lowercase() also folds rows that
                        // aren't mailbox-shaped, so one address typed in mixed case on two
                        // rows dedups. The display keeps the original case.
                        if (!seen.add(AddressNormalizer.canonical(address).lowercase())) continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                        results.add(ContactEmail(displayName = name, address = address))
                    }
                }
            } catch (e: SecurityException) {
                // Permission revoked between the check and the query: return what was read.
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
