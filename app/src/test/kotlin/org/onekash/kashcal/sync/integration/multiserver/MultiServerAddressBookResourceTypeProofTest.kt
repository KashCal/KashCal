package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Empirical safety proof for CardDAV address-book discovery, run live across every
 * configured server. The CardDAV twin of [MultiServerCalendarResourceTypeProofTest]
 * — and the protocol where the reserved-word substring bug actually bit (a real
 * Radicale book "notifications-contacts" was silently hidden).
 *
 * Proves, on real servers, that:
 *   (1) every collection the reserved-word name filter would skip is ALSO excluded
 *       by the resourcetype gate (lacks the `<addressbook>` resourcetype), AND
 *   (2) no collection carrying `<addressbook>` is skipped by the name filter.
 *
 * So resourcetype alone is a superset of the name filter's exclusions and never
 * over-includes a scheduling/notification collection — the whole-segment name
 * filter is safe redundancy, never load-bearing. Reads the RAW home-set PROPFIND
 * so the notification collection the parser drops is visible for inspection; each
 * server's redacted raw XML is written as a fixture.
 *
 * Skips (never fails) servers without credentials / unreachable / no CardDAV.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerAddressBookResourceTypeProofTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerAddressBookResourceTypeProofTest(
    private val config: CardDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CardDavServerConfig.allServers().map { arrayOf<Any>(it) }

        /** Exact production `listAddressBooks` PROPFIND body (OkHttpCardDavClient). */
        private val LIST_ADDRESSBOOKS_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav"
                        xmlns:cs="http://calendarserver.org/ns/">
                <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                    <card:addressbook-description/>
                    <cs:getctag/>
                    <d:current-user-privilege-set/>
                    <card:supported-address-data/>
                </d:prop>
            </d:propfind>
        """.trimIndent()
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
    fun `notification collections never carry the addressbook resourcetype`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!

        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).getOrNull()
        assumeTrue("${config.name}: no principal (CardDAV likely unsupported)", principal != null)

        val home = (c.discoverAddressBookHome(principal!!) as? CalDavResult.Success)?.data?.firstOrNull()
        assumeTrue("${config.name}: no addressbook-home-set", home != null)

        val raw = CollectionResourceTypeProof.fetchRawPropfind(
            CollectionResourceTypeProof.rawClient(cr.username, cr.password),
            home!!,
            LIST_ADDRESSBOOKS_BODY,
        )
        assumeTrue("${config.name}: raw home-set PROPFIND failed", raw != null)

        CollectionResourceTypeProof.writeFixture("carddav", config.name, raw!!)
        val rows = CollectionResourceTypeProof.parseCollections(raw)
        assumeTrue("${config.name}: no collections parsed from home-set", rows.isNotEmpty())

        println("\n=== CardDAV resourcetype proof: ${config.name} (${rows.size} collections) ===")
        rows.forEach { println("  " + CollectionResourceTypeProof.matrixRow(config.name, it, "carddav")) }

        // The invariant, calling the REAL production predicate: every collection the
        // shipped name filter skips is one the app would NOT surface anyway (lacks the
        // <addressbook> resourcetype). So the name filter can only add false-drops,
        // never prevent a real one — which is what makes whole-segment matching safe.
        val disagreements = rows.filter {
            CollectionResourceTypeProof.cardDavNameFilterSkips(it) && it.appSurfacesAsAddressBook
        }
        assertTrue(
            "${config.name}: the production name filter skips a collection the app WOULD " +
                "surface as an address book — the filter is not safe redundancy: " +
                disagreements.map { CollectionResourceTypeProof.redactPii(it.href) },
            disagreements.isEmpty(),
        )

        assumeTrue(
            "${config.name}: no app-visible address book surfaced (discovery reached wrong home?)",
            rows.any { it.appSurfacesAsAddressBook },
        )
    }
}
