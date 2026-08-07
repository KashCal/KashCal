package org.onekash.kashcal.sync.carddav

import android.util.Log
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.vcard.VCardParser
import org.onekash.vcard.model.Contact

/**
 * A parsed contact paired with its CardDAV source coordinates.
 *
 * [href] and [etag] come from the addressbook-multiget response (not the vCard
 * body) so downstream sync can track the resource; [contact] is the neutral
 * model whose `version` reflects the body's own `VERSION:` line.
 */
data class ReadContact(
    val href: String,
    val etag: String?,
    val contact: Contact,
)

/**
 * Composes the CardDAV read path end-to-end: fetch raw vCard bodies via
 * [CardDavClient.fetchContactsByHref], then parse each through [VCardParser] into
 * the neutral [Contact] model.
 *
 * This is the seam where transport meets the format layer. Importing vcard-core
 * here is firewall-permitted; the reader touches no CalDAV client symbol.
 *
 * Not a Hilt-managed singleton: the [client] carries per-account credentials
 * (built by [CardDavClientFactory.createClient]), so the sync layer constructs a
 * reader per account with a freshly-created client — mirroring how the CalDAV
 * pull path takes a per-account client rather than injecting a bare one. The
 * pure-JVM [VCardParser] (vcard-core, no Hilt) is instantiated internally, the
 * same way `PullStrategy` holds its own `ICalParser`.
 *
 * Robustness contract:
 * - Empty [hrefs] short-circuits to an empty list (no network round-trip).
 * - Hrefs are fetched in bounded batches of [MULTIGET_BATCH_SIZE]: iCloud does not
 *   return a usable single oversized addressbook-multiget, so an unbounded fetch of
 *   a large book comes back empty. The cap mirrors the CalDAV pull path.
 * - A single unparseable body is logged and skipped — it never aborts the parse
 *   of the other hrefs in the batch.
 * - A `KIND:group` vCard (RFC 6350 §6.1.4, or the 3.0 Apple
 *   `X-ADDRESSBOOKSERVER-KIND:group` form) is a distribution list, not a person, so
 *   it is dropped here rather than mirrored to the device as a phantom empty contact.
 * - A transport error on any batch is returned verbatim (no partial success): the
 *   caller retries the whole read rather than acting on a truncated set.
 * - The parsed version is driven entirely by each body's `VERSION:` line; the
 *   negotiated [vcardVersion] is only what the client *requested* over the wire.
 */
class CardDavContactReader(
    private val client: CardDavClient,
) {
    private val parser = VCardParser()


    /**
     * Fetch and parse the contacts at [hrefs] within the collection at
     * [addressBookUrl]. [vcardVersion] is the version to request over the wire
     * (RFC 6352 §10.4); the actual parse version comes from each returned body.
     *
     * Returns the parsed contacts on success, or the client's transport error
     * verbatim. Bodies that fail to parse are dropped from the result, not
     * surfaced as an error.
     */
    suspend fun readContacts(
        addressBookUrl: String,
        hrefs: List<String>,
        vcardVersion: String,
    ): CalDavResult<List<ReadContact>> {
        if (hrefs.isEmpty()) return CalDavResult.success(emptyList())

        val contacts = ArrayList<ReadContact>(hrefs.size)
        for (batch in hrefs.chunked(MULTIGET_BATCH_SIZE)) {
            when (val fetched = client.fetchContactsByHref(addressBookUrl, batch, vcardVersion)) {
                is CalDavResult.Success -> fetched.data.forEach { data ->
                    try {
                        // CardDAV serves one vCard per resource, but a body could
                        // technically hold several; associate each with the source
                        // href/etag. Never trust the requested version — parse from
                        // the body's own VERSION line.
                        parser.parse(data.vcardBody).forEach { contact ->
                            // A KIND:group vCard is a distribution list, not a person;
                            // mirroring it would create a phantom empty contact on the
                            // device. Drop it here so it never reaches the write path.
                            if (contact.kind.equals("group", ignoreCase = true)) {
                                Log.d(TAG, "Skipping group vCard at ${data.href}")
                                return@forEach
                            }
                            contacts += ReadContact(href = data.href, etag = data.etag, contact = contact)
                        }
                    } catch (e: Exception) {
                        // Isolate a malformed body: skip it, keep the rest of the batch.
                        Log.w(TAG, "Skipping unparseable contact at ${data.href}: ${e.message}")
                    }
                }
                // Surface a transport error verbatim rather than returning a truncated
                // set the caller would mistake for a complete read.
                is CalDavResult.Error -> return fetched
            }
        }
        return CalDavResult.success(contacts)
    }

    companion object {
        private const val TAG = "CardDavContactReader"

        /**
         * Max hrefs per addressbook-multiget. iCloud returns an empty/unusable
         * response to a single oversized multiget, so the read is chunked. Mirrors
         * the CalDAV pull path's batch size.
         */
        private const val MULTIGET_BATCH_SIZE = 20
    }
}
