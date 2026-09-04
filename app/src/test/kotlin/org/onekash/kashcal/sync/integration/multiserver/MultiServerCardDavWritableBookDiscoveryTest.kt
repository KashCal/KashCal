package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live counterpart to the deterministic read-only-detection unit guard
 * (`CardDavXmlParserTest`): drives real discovery against every configured
 * CardDAV server and asserts each reachable login resolves a WRITABLE address
 * book (the book is found and `isReadOnly == false`).
 *
 * The load-bearing member is Xandikos. It advertises the RFC 3744 aggregate
 * `<all>` privilege on its contacts collection rather than the granular
 * `<write>` / `<write-content>` (verified live: a PROPFIND on
 * `/user/contacts/addressbook/` returns `current-user-privilege-set` with only
 * `<all>`). The privilege parser must map `<all>` to a write grant — a
 * regression that does not surfaces the book as read-only and the app silently
 * blocks contact push. That is the real-world failure from issue #281, and this
 * test fails loudly on it rather than skipping.
 *
 * Skips (never fails) a server without credentials, unreachable, or one whose
 * discovery yields no address book at all (an unprovisioned account, not a
 * privilege-parse regression).
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerCardDavWritableBookDiscoveryTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCardDavWritableBookDiscoveryTest(
    private val config: CardDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CardDavServerConfig.allServers().map { arrayOf<Any>(it) }
    }

    private var client: CardDavClient? = null
    private var creds: ServerCredentials? = null

    @Before
    fun setup() {
        CardDavTestServerLoader.createClient(config)?.let {
            client = it.first
            creds = it.second
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name}: no credentials in local.properties", client != null)
        assumeTrue(
            "${config.name}: server unreachable at ${creds!!.davEndpoint}",
            CardDavTestServerLoader.isServerReachable(creds!!.davEndpoint),
        )
    }

    @Test
    fun `live discovery resolves a writable address book`() = runBlocking {
        assumeReady()
        val books = discoverBooks(client!!, creds!!)

        // No book at all is an unprovisioned account, not a privilege-parse
        // regression — skip rather than fail so a bare test login doesn't red the
        // suite. A book that comes back read-only IS the regression we guard.
        assumeTrue(
            "${config.name}: discovery yielded no address book (unprovisioned login)",
            books.isNotEmpty(),
        )

        val writable = books.firstOrNull { !it.isReadOnly }
        assertNotNull(
            "${config.name}: discovery found ${books.size} address book(s) but none writable " +
                "(isReadOnly). For Xandikos this is the issue #281 regression: the RFC 3744 " +
                "aggregate <all> privilege it grants was not mapped to a write grant.",
            writable,
        )
        assertTrue(
            "${config.name}: resolved book '${writable!!.displayName}' must be writable",
            !writable.isReadOnly,
        )
        println(
            "=== ${config.name} writable-book discovery: '${writable.displayName}' " +
                "(books=${books.size}, isReadOnly=false) ===",
        )
    }

    /** List the address books under the login's addressbook-home-set. */
    private suspend fun discoverBooks(c: CardDavClient, cr: ServerCredentials): List<CardDavAddressBook> {
        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).getOrNull() ?: return emptyList()
        val homes = (c.discoverAddressBookHome(principal) as? CalDavResult.Success)?.data.orEmpty()
        if (homes.isEmpty()) return emptyList()
        return (c.listAddressBooks(homes.first()) as? CalDavResult.Success)?.data.orEmpty()
    }
}
