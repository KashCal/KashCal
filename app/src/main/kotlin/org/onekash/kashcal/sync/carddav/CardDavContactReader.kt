package org.onekash.kashcal.sync.carddav

import android.util.Log
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.vcard.VCardParser
import org.onekash.vcard.model.Contact

/**
 * Pairs a parsed contact with the CardDAV resource it came from.
 *
 * [href] and [etag] come from the addressbook-multiget response, not the vCard body. The
 * `version` of [contact] is the body's own `VERSION:` line.
 */
data class ReadContact(
    val href: String,
    val etag: String?,
    val contact: Contact,
)

/**
 * Holds the result of [CardDavContactReader.readContacts].
 *
 * @property contacts every contact parsed from the fetched bodies.
 * @property unreadableHrefs requested hrefs the read couldn't confirm: a body that threw while
 *   parsing, one that yielded no vCard, or an href the server omitted from the multiget
 *   response. A dropped `KIND:group` href isn't here. The caller holds the book's sync-token
 *   while this is non-empty, so an href that failed to parse this run (a malformed server card,
 *   or an R8-stripped parser constructor) is re-fetched next run instead of skipped by a token
 *   that claims it synced.
 */
data class ReadContactsResult(
    val contacts: List<ReadContact>,
    val unreadableHrefs: Set<String>,
)

/**
 * Reads contacts end to end: fetches raw vCard bodies via [CardDavClient.fetchContactsByHref]
 * and parses each through [VCardParser] into the [Contact] model.
 *
 * This is where transport meets the format layer; it may import vcard-core but touches no
 * CalDAV client symbol (`CardDavCalDavIsolationTest`).
 *
 * Not a Hilt singleton: [client] carries one account's credentials
 * ([CardDavClientFactory.createClient]), so the sync layer builds a reader per account, as the
 * CalDAV pull path takes a per-account client. It holds its own [VCardParser], as `PullStrategy`
 * holds its own `ICalParser`.
 *
 * Contract:
 * - Hrefs are fetched in batches of [MULTIGET_BATCH_SIZE].
 * - An unparseable body is logged and skipped without aborting the batch, and its href is
 *   reported in [ReadContactsResult.unreadableHrefs].
 * - A `KIND:group` vCard (RFC 6350 §6.1.4, or the vCard 3.0 `X-ADDRESSBOOKSERVER-KIND:group`
 *   form) is a distribution list, not a person, so it is dropped instead of written to the
 *   device as an empty contact. The drop is intended, so it isn't reported as unreadable.
 * - A transport error on any batch is returned as is, with no partial success, so the caller
 *   never acts on a truncated set.
 * - Each body's `VERSION:` line sets the parse version; `vcardVersion` is only what the client
 *   requests.
 */
class CardDavContactReader(
    private val client: CardDavClient,
) {
    private val parser = VCardParser()


    /**
     * Fetches and parses the contacts at [hrefs] in the collection at [addressBookUrl],
     * requesting [vcardVersion] (RFC 6352 §10.4).
     *
     * Returns the parsed contacts and the hrefs that couldn't be confirmed
     * ([ReadContactsResult.unreadableHrefs]), or the client's transport error. Empty [hrefs]
     * returns an empty result without a request.
     */
    suspend fun readContacts(
        addressBookUrl: String,
        hrefs: List<String>,
        vcardVersion: String,
    ): CalDavResult<ReadContactsResult> {
        if (hrefs.isEmpty()) return CalDavResult.success(ReadContactsResult(emptyList(), emptySet()))

        // Drop the collection self-href first. iCloud's sync-collection REPORT lists the
        // collection with no trailing slash and no resourcetype, so it passes the shared
        // parser's self-row filter; the client's multiget drops it too (a collection href
        // 400s the whole batch). Counted as requested, it could never be confirmed and would
        // hold the caller's sync-token forever. Comparing decoded, slash-trimmed paths
        // matches the slashless self-href to the collection URL. A member's path always has
        // a segment beyond the collection, so no real contact is excluded.
        val collectionPath = pathKey(addressBookUrl)
        val memberHrefs = hrefs.filter { pathKey(it) != collectionPath }
        if (memberHrefs.isEmpty()) return CalDavResult.success(ReadContactsResult(emptyList(), emptySet()))

        val contacts = ArrayList<ReadContact>(memberHrefs.size)
        // Hrefs accounted for, keyed on the raw href string: the identity the caller's
        // writesByHref, device-etag map and delete-by-href use. If a server spelled an href
        // differently in the listing and the multiget response, the contact would be neither
        // written nor confirmed, so the book isn't ok and its sync-token is held for a retry
        // instead of advancing past a contact never written. Every requested member not
        // confirmed is reported unreadable.
        val confirmed = HashSet<String>()
        for (batch in memberHrefs.chunked(MULTIGET_BATCH_SIZE)) {
            when (val fetched = client.fetchContactsByHref(addressBookUrl, batch, vcardVersion)) {
                is CalDavResult.Success -> fetched.data.forEach { data ->
                    try {
                        // CardDAV serves one vCard per resource, but a body can hold
                        // several; each gets the source href and etag. The parse uses the
                        // body's own VERSION line, never the requested version.
                        parser.parse(data.vcardBody).forEach { contact ->
                            // A group is dropped before the write path but its href is
                            // confirmed, or it would be re-fetched forever as a failure.
                            if (contact.kind.equals("group", ignoreCase = true)) {
                                Log.d(TAG, "Skipping a KIND:group vCard (distribution list, not a person)")
                                confirmed += data.href
                                return@forEach
                            }
                            contacts += ReadContact(href = data.href, etag = data.etag, contact = contact)
                            confirmed += data.href
                        }
                    } catch (e: Exception) {
                        // Skip a malformed body and keep the rest of the batch; leaving
                        // data.href out of `confirmed` reports it unreadable, so the caller
                        // holds its sync-token and re-fetches next run. Log the exception
                        // type only: neither the href nor e.message (which can embed the
                        // vCard body) may reach a log.
                        Log.w(TAG, "Skipping an unparseable contact: ${e.javaClass.simpleName}")
                    }
                }
                // Returned as is: a truncated set would read as a complete one.
                is CalDavResult.Error -> return fetched
            }
        }
        val unreadable = memberHrefs.filter { it !in confirmed }.toSet()
        return CalDavResult.success(ReadContactsResult(contacts, unreadable))
    }

    /**
     * Returns the URL's decoded path without a trailing slash, or the slash-trimmed input when
     * it isn't a URI.
     *
     * Only for the collection self-href check; never match member contacts with it, since
     * they are keyed on the raw href to agree with the caller's write path.
     */
    private fun pathKey(urlOrHref: String): String {
        val path = try {
            java.net.URI(urlOrHref).path ?: urlOrHref
        } catch (_: Exception) {
            urlOrHref
        }
        return path.trimEnd('/')
    }

    companion object {
        private const val TAG = "CardDavContactReader"

        /**
         * Caps hrefs per addressbook-multiget: iCloud answers an oversized multiget with an
         * empty or unusable response. Same as the CalDAV pull path's batch size.
         */
        private const val MULTIGET_BATCH_SIZE = 20
    }
}
