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
 * Outcome of a [CardDavContactReader.readContacts] call.
 *
 * @property contacts every contact successfully parsed from the fetched bodies.
 * @property unreadableHrefs requested hrefs the read could NOT confirm — a body
 *   that threw while parsing, one that yielded no vCard at all, or an href the
 *   server omitted from the multiget response. A `KIND:group` href is NOT here:
 *   it was deliberately dropped, not failed. This set is the signal the caller
 *   uses to avoid advancing a book's sync cursor past contacts it never saw — an
 *   href that failed to parse on this run (e.g. a stripped reflective ctor before
 *   the R8 keep rules, or a genuinely malformed server card) must be re-fetched on
 *   the next run rather than orphaned by a cursor that claims it's already synced.
 */
data class ReadContactsResult(
    val contacts: List<ReadContact>,
    val unreadableHrefs: Set<String>,
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
 * - Empty [hrefs] short-circuits to an empty result (no network round-trip).
 * - Hrefs are fetched in bounded batches of [MULTIGET_BATCH_SIZE]: iCloud does not
 *   return a usable single oversized addressbook-multiget, so an unbounded fetch of
 *   a large book comes back empty. The cap mirrors the CalDAV pull path.
 * - A single unparseable body is logged and skipped — it never aborts the parse
 *   of the other hrefs in the batch — but its href is reported in
 *   [ReadContactsResult.unreadableHrefs] so the caller can decline to advance its
 *   sync cursor past a contact it couldn't actually read.
 * - A `KIND:group` vCard (RFC 6350 §6.1.4, or the 3.0 Apple
 *   `X-ADDRESSBOOKSERVER-KIND:group` form) is a distribution list, not a person, so
 *   it is dropped here rather than mirrored to the device as a phantom empty contact.
 *   A group drop is deliberate, so it is NOT reported as unreadable.
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
     * Returns the parsed contacts plus the requested hrefs that could NOT be
     * confirmed (see [ReadContactsResult.unreadableHrefs]), or the client's
     * transport error verbatim. Bodies that fail to parse are dropped from the
     * result rather than aborting the batch, but their hrefs are reported as
     * unreadable so the caller can hold its sync cursor instead of orphaning them.
     */
    suspend fun readContacts(
        addressBookUrl: String,
        hrefs: List<String>,
        vcardVersion: String,
    ): CalDavResult<ReadContactsResult> {
        if (hrefs.isEmpty()) return CalDavResult.success(ReadContactsResult(emptyList(), emptySet()))

        // Drop the collection self-href before anything else. iCloud's sync-collection
        // REPORT lists the collection itself with no trailing slash and no
        // resourcetype, so it slips past the shared parser's self-row filter and into
        // the requested href set — but the client's multiget deliberately drops it (a
        // non-contact collection href 400s the whole batch). If we still counted it as
        // "requested", it could never be confirmed and would falsely report unreadable,
        // permanently holding the caller's sync cursor. Compare on the decoded,
        // slash-normalized path so the slashless self-href collapses onto the
        // collection URL — mirrors the same self-href drop the client's multiget does.
        // This exclusion never touches a real member (its path always carries a segment
        // beyond the collection), so it cannot suppress a genuine contact.
        val collectionPath = pathKey(addressBookUrl)
        val memberHrefs = hrefs.filter { pathKey(it) != collectionPath }
        if (memberHrefs.isEmpty()) return CalDavResult.success(ReadContactsResult(emptyList(), emptySet()))

        val contacts = ArrayList<ReadContact>(memberHrefs.size)
        // Hrefs we positively accounted for, keyed on the RAW href string — the same
        // identity the caller's write path (writesByHref), device-etag map, and
        // delete-by-href all use. Keeping this consistent with the write path is the
        // point: if a server ever spelled an href differently between the enumeration
        // and the multiget response, the contact wouldn't be written AND wouldn't be
        // confirmed, so the book stays not-ok and its cursor is HELD for a retry
        // rather than advancing past a contact that was never mirrored. Every
        // requested member NOT confirmed — a body that threw, one that parsed to zero
        // vCards, or an href the server omitted — is reported unreadable.
        val confirmed = HashSet<String>()
        for (batch in memberHrefs.chunked(MULTIGET_BATCH_SIZE)) {
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
                            // A group drop is deliberate, so the href IS confirmed —
                            // it must not be re-fetched forever as if it had failed.
                            if (contact.kind.equals("group", ignoreCase = true)) {
                                Log.d(TAG, "Skipping group vCard at ${data.href}")
                                confirmed += data.href
                                return@forEach
                            }
                            contacts += ReadContact(href = data.href, etag = data.etag, contact = contact)
                            confirmed += data.href
                        }
                    } catch (e: Exception) {
                        // Isolate a malformed body: skip it, keep the rest of the batch.
                        // Leaving data.href out of `confirmed` reports it unreadable so
                        // the caller holds its cursor and retries the fetch next run.
                        Log.w(TAG, "Skipping unparseable contact at ${data.href}: ${e.message}")
                    }
                }
                // Surface a transport error verbatim rather than returning a truncated
                // set the caller would mistake for a complete read.
                is CalDavResult.Error -> return fetched
            }
        }
        val unreadable = memberHrefs.filter { it !in confirmed }.toSet()
        return CalDavResult.success(ReadContactsResult(contacts, unreadable))
    }

    /**
     * Path key for the collection self-href compare ONLY: the URL's path with any
     * trailing slash removed, so iCloud's slashless self-href collapses onto the
     * collection URL that carries a trailing slash. Used solely to exclude the
     * self-href — never to match member contacts, which are keyed on their raw href
     * string to stay consistent with the caller's write path. Falls back to the raw
     * input (slash-trimmed) if it can't be parsed as a URI.
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
         * Max hrefs per addressbook-multiget. iCloud returns an empty/unusable
         * response to a single oversized multiget, so the read is chunked. Mirrors
         * the CalDAV pull path's batch size.
         */
        private const val MULTIGET_BATCH_SIZE = 20
    }
}
