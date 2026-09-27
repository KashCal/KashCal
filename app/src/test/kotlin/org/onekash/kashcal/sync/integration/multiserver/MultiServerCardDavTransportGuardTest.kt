package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.ContactDeleteResult
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult
import org.onekash.kashcal.sync.client.model.CalDavResult
import java.util.UUID

/**
 * Checks the contact client's transport guards on every configured CardDAV server, through the
 * app's own client as its factory builds it. No discovery step or read of the address book
 * fails with a guard code ([CalDavResult.CODE_NOT_MULTISTATUS] or
 * [CalDavResult.CODE_TRANSPORT_REFUSED]), and a contact is created and updated with If-Match
 * with neither write redirected, then deleted.
 *
 * Each run uses a new UID and deletes the contact at the end by the href the server lists for
 * that UID. URLs in failure messages are redacted.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration --tests '*MultiServerCardDavTransportGuardTest*'
 */
@RunWith(Parameterized::class)
class MultiServerCardDavTransportGuardTest(private val config: CardDavServerConfig) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CardDavServerConfig> = CardDavServerConfig.allServers()

        private val GUARD_CODES = setOf(CalDavResult.CODE_NOT_MULTISTATUS, CalDavResult.CODE_TRANSPORT_REFUSED)
    }

    private var client: CardDavClient? = null
    private var creds: ServerCredentials? = null
    private var book: CardDavAddressBook? = null
    private val uid = "transport-guard-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        CardDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
    }

    @After
    fun tearDown() = runBlocking {
        val c = client ?: return@runBlocking
        val b = book ?: return@runBlocking
        val listed = (c.listAllContactHrefs(b.url) as? CalDavResult.Success)?.data.orEmpty()
        for ((href, etag) in listed.filter { it.first.contains(uid) }) {
            runCatching { c.deleteContact(resolve(b.url, href), etag.orEmpty()) }
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name}: no credentials in local.properties", client != null)
        assumeTrue("${config.name}: server unreachable", CardDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
    }

    private fun redact(text: Any?): String = FixtureRedactor.redact(text.toString())

    private fun assertNotGuardError(what: String, result: CalDavResult<*>) {
        val code = (result as? CalDavResult.Error)?.code
        assertFalse("${config.name} $what: ${redact(result)}", code in GUARD_CODES)
    }

    private fun resolve(bookUrl: String, href: String): String =
        if (href.startsWith("http")) href else java.net.URI(bookUrl).resolve(href).toString()

    private fun vcard(fn: String) = listOf(
        "BEGIN:VCARD", "VERSION:3.0", "UID:$uid", "FN:$fn", "N:;;;;", "END:VCARD", "",
    ).joinToString("\r\n")

    private suspend fun writableBook(c: CardDavClient, cr: ServerCredentials): CardDavAddressBook? {
        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).also { assertNotGuardError("well-known", it) }.getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).also { assertNotGuardError("principal", it) }.getOrNull() ?: return null
        val homes = c.discoverAddressBookHome(principal).also { assertNotGuardError("address book home", it) }
            .getOrNull().orEmpty()
        if (homes.isEmpty()) return null
        val books = c.listAddressBooks(homes.first()).also { assertNotGuardError("address book listing", it) }
            .getOrNull().orEmpty()
        return books.firstOrNull { !it.isReadOnly }
    }

    @Test
    fun `contact reads are real multistatus replies and a write round trip is never redirected`() = runBlocking<Unit> {
        assumeReady()
        val c = client!!
        book = writableBook(c, creds!!)
        assumeTrue("${config.name}: no writable address book", book != null)
        val b = book!!

        assertNotGuardError("ctag", c.getCtag(b.url))
        val token = c.getSyncToken(b.url).also { assertNotGuardError("sync token", it) }.getOrNull()
        assertNotGuardError("sync-collection", c.syncCollection(b.url, token))
        assertNotGuardError("full listing", c.listAllContactHrefs(b.url))

        val url = b.url.trimEnd('/') + "/$uid.vcf"
        val created = c.putContact(url, vcard("Transport Guard"), ContactPrecondition.IfAbsent)
        assumeTrue("${config.name}: server refused the contact write: ${redact(created)}", created is ContactUploadResult.Success)
        assertNull("${config.name}: the create was not redirected", (created as ContactUploadResult.Success).finalUrl)

        val listed = (c.listAllContactHrefs(b.url) as? CalDavResult.Success)?.data.orEmpty()
        val (href, etag) = listed.firstOrNull { it.first.contains(uid) }
            ?: error("${config.name}: the new contact isn't listed")
        val resource = resolve(b.url, href)
        val updated = c.putContact(resource, vcard("Transport Guard Edited"), ContactPrecondition.IfMatch(created.etag ?: etag.orEmpty()))
        assertTrue("${config.name}: update: ${redact(updated)}", updated is ContactUploadResult.Success)
        assertNull("${config.name}: the update was not redirected", (updated as ContactUploadResult.Success).finalUrl)

        val currentEtag = (c.listAllContactHrefs(b.url) as? CalDavResult.Success)?.data.orEmpty()
            .firstOrNull { it.first.contains(uid) }?.second.orEmpty()
        assertEquals("${config.name}: delete", ContactDeleteResult.Deleted, c.deleteContact(resource, updated.etag ?: currentEtag))
    }
}
