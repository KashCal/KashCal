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
 * Checks, live against every configured CardDAV server, that each reachable login discovers
 * a writable address book (found, with `isReadOnly == false`). The deterministic
 * read-only-detection guard is `CardDavXmlParserTest`.
 *
 * The server that matters is Xandikos. It advertises the RFC 3744 aggregate `<all>` privilege
 * on its contacts collection instead of the granular `<write>` or `<write-content>` (verified
 * live: a PROPFIND on `/user/contacts/addressbook/` returns `current-user-privilege-set` with
 * only `<all>`). The privilege parser must map `<all>` to a write grant; if it doesn't, the
 * book surfaces as read-only and the app silently blocks contact push. That is the real-world
 * failure from issue #281, and this test fails on it instead of skipping.
 *
 * Skips (never fails) a server without credentials, an unreachable one, or one whose
 * discovery yields no address book at all (an unprovisioned account, not a privilege-parse
 * regression).
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

        // No book at all is an unprovisioned account, not a privilege-parse regression, so it
        // skips and a bare test login doesn't fail the suite. A book that comes back read-only
        // is the regression this test guards.
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

    /** Lists the address books under the login's first addressbook-home-set, or none. */
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
