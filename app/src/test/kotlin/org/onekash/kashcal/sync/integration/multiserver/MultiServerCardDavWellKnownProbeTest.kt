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
 * Cross-server characterization of the RFC 6764 CardDAV discovery walk, run to
 * decide whether contacts sync can rely on *independent* well-known discovery
 * (start from a domain/host, let the provider point at its own CardDAV home)
 * rather than reusing the account's CalDAV host.
 *
 * Why this matters: today the contact-sync worker feeds the CalDAV-derived base
 * URL into the discovery chain. That is correct only for providers that serve
 * CardDAV and CalDAV from the same host. A provider whose contacts live on a
 * different host than its calendars (Zoho serves contacts from `contacts.zoho.com`,
 * not its `calendar.zoho.com` CalDAV endpoint) can only be reached if discovery
 * starts from the domain and follows the provider's own `/.well-known/carddav`
 * redirect. This probe measures, per server, whether the chain resolves an
 * addressbook-home from each candidate starting point:
 *  - the bare registrable domain (`https://zoho.com`) — the "feed the domain" model,
 *  - the full configured host (`https://contacts.zoho.com`) — the host as we know it,
 *  - the direct endpoint (with any `davEndpointSuffix`) as a no-well-known control
 *    that tells us the server has contacts at all, independent of well-known.
 *
 * It RECORDS the full per-candidate matrix (resolved? which home host? how many
 * books?) and only softly asserts that *some* candidate resolved — so a server
 * that legitimately lacks well-known (targeted directly in production) stays green
 * while the printed matrix, not a brittle assertion, carries the design signal.
 * Promote a finding into a hard per-server assertion only once the durable path is
 * built around it.
 *
 * Zoho is included here (unlike [CardDavServerConfig.allServers], which omits it)
 * precisely because characterizing Zoho's split-host discovery is the point.
 *
 * PII discipline: on cloud accounts the resolved home path carries an account id
 * (iCloud DSID, Zoho user), so every printed URL is reduced to scheme+host via
 * [hostShape] — never a full path, fetched body, or account address.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerCardDavWellKnownProbeTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCardDavWellKnownProbeTest(
    private val config: CardDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CardDavServerConfig.allDiscoveryProbeServers().map { arrayOf<Any>(it) }
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

    @Test
    fun `well-known discovery resolves an addressbook-home from a candidate start`() = runBlocking {
        assumeTrue("${config.name}: no credentials in local.properties", client != null)
        val c = client!!
        val cr = creds!!
        assumeTrue(
            "${config.name}: server unreachable at ${cr.davEndpoint}",
            CardDavTestServerLoader.isServerReachable(cr.davEndpoint),
        )

        val results = candidatesFor(cr).map { candidate ->
            candidate to walk(c, candidate)
        }

        // RFC 6764 §6 makes DNS SRV (`_carddavs._tcp.<domain>`) the PRIMARY
        // discovery mechanism; well-known is the fallback. The client does not do
        // SRV, but recording whether the provider publishes one tells us whether an
        // SRV client COULD reach it from the bare domain — the difference between
        // "needs a bootstrap constant forever" and "SRV would find it for free."
        val srv = srvLookup(dnsDomainOf(cr.serverUrl))

        println("=== ${config.name} CardDAV discovery matrix ===")
        results.forEach { (candidate, outcome) ->
            println(
                "    [${candidate.label}] start=${hostShape(candidate.startUrl)} " +
                    "-> $outcome",
            )
        }
        println("    [dns-srv _carddavs._tcp] -> $srv")

        // A characterization probe, so the matrix above is the deliverable. The
        // only hard claim: the server was reachable via SOME route. An auth-rejected
        // candidate still proves discovery reached a CardDAV endpoint, so treat stale
        // credentials as a skip (assumeTrue), not a failure — otherwise an expired
        // app-password would masquerade as "well-known unreachable." Fail only if no
        // route resolved AND none even reached an endpoint.
        val anyResolved = results.any { (_, o) -> o is Outcome.Resolved }
        val anyReached = results.any { (_, o) -> o is Outcome.Resolved || o is Outcome.AuthRejected }
        assumeTrue(
            "${config.name}: all candidates auth-rejected (stale credentials?) — endpoint reached, not a discovery gap",
            anyResolved || !anyReached,
        )
        assertTrue(
            "${config.name}: no candidate reached a CardDAV endpoint; matrix above",
            anyReached,
        )
    }

    /**
     * Candidate discovery entry points, most-portable first. The full host and the
     * bare domain are exercised through well-known; the configured endpoint is a
     * direct (no-well-known) control.
     */
    private fun candidatesFor(cr: ServerCredentials): List<Candidate> {
        val host = schemeHost(cr.serverUrl) ?: return listOf(
            Candidate("direct-endpoint", cr.davEndpoint, useWellKnown = false),
        )
        val domain = registrableDomainUrl(cr.serverUrl)
        return buildList {
            // The host a real account stored from CalDAV setup — first, because it
            // decides whether a split-host provider (Zoho) can reach contacts from
            // what the account already knows, or needs a bootstrap constant.
            config.caldavHostUrl?.let { caldavHost ->
                add(Candidate("well-known @ caldav-host", caldavHost, useWellKnown = true))
            }
            add(Candidate("well-known @ domain", domain, useWellKnown = true))
            if (host != domain) {
                add(Candidate("well-known @ host", host, useWellKnown = true))
            }
            if (cr.davEndpoint.trimEnd('/') != host.trimEnd('/')) {
                add(Candidate("direct-endpoint", cr.davEndpoint, useWellKnown = false))
            }
        }
    }

    /** Run well-known (optional) -> principal -> addressbook-home for one candidate. */
    private suspend fun walk(c: CardDavClient, candidate: Candidate): Outcome {
        val base = if (candidate.useWellKnown) {
            c.discoverWellKnown(candidate.startUrl).getOrNull() ?: candidate.startUrl
        } else {
            candidate.startUrl
        }
        when (val principal = c.discoverPrincipal(base)) {
            is CalDavResult.Error ->
                return if (principal.isAuthError()) Outcome.AuthRejected else Outcome.NoPrincipal
            is CalDavResult.Success -> {
                val homes = (c.discoverAddressBookHome(principal.data) as? CalDavResult.Success)
                    ?.data.orEmpty()
                if (homes.isEmpty()) return Outcome.NoHome
                val books = (c.listAddressBooks(homes.first()) as? CalDavResult.Success)?.data.orEmpty()
                return Outcome.Resolved(hostShape(homes.first()), homes.size, books.size)
            }
        }
    }

    private data class Candidate(
        val label: String,
        val startUrl: String,
        val useWellKnown: Boolean,
    )

    private sealed interface Outcome {
        data class Resolved(val homeHost: String, val homeCount: Int, val bookCount: Int) : Outcome {
            override fun toString() = "RESOLVED home=$homeHost homes=$homeCount books=$bookCount"
        }
        // 401 at the principal step means well-known/redirect DID reach a CardDAV
        // endpoint (it answered), but our credentials were rejected — a credential
        // problem, not a discovery gap. Distinguished so stale creds don't read as
        // "well-known unreachable."
        object AuthRejected : Outcome { override fun toString() = "auth rejected (401) — endpoint reached" }
        object NoPrincipal : Outcome { override fun toString() = "no principal" }
        object NoHome : Outcome { override fun toString() = "principal but no addressbook-home" }
    }

    /** scheme://host[:port] of a URL, or null if it can't be parsed. */
    private fun schemeHost(url: String): String? =
        Regex("""^(\w+://[^/]+)""").find(url)?.groupValues?.get(1)

    /**
     * scheme://<registrable-domain> — strips the leading subdomain labels so a
     * host like `contacts.zoho.com` yields `https://zoho.com`, modelling the domain
     * a user would type at setup. Deliberately simplistic (last two labels): the
     * server set here uses single-suffix domains (zoho.com, icloud.com) or a bare
     * host (localhost[:port], an IP), for which the whole host is returned unchanged.
     * Not a public-suffix-list implementation.
     */
    private fun registrableDomainUrl(url: String): String {
        val scheme = url.substringBefore("://", "https")
        val hostPort = url.substringAfter("://").substringBefore('/')
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "")
        val labels = host.split('.')
        val isIp = host.all { it.isDigit() || it == '.' }
        val domain = if (host == "localhost" || isIp || labels.size < 2) {
            host
        } else {
            labels.takeLast(2).joinToString(".")
        }
        val suffix = if (port.isNotEmpty()) ":$port" else ""
        return "$scheme://$domain$suffix"
    }

    /** Scheme+host of a URL for logging, without the account-identifying path. */
    private fun hostShape(url: String?): String =
        url?.let { schemeHost(it)?.plus("/<path>") ?: "<opaque>" } ?: "(none)"

    /** Bare DNS domain (last two labels) for the SRV query, or the host for local servers. */
    private fun dnsDomainOf(url: String): String {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        val labels = host.split('.')
        val isIp = host.all { it.isDigit() || it == '.' }
        return if (host == "localhost" || isIp || labels.size < 2) host else labels.takeLast(2).joinToString(".")
    }

    private sealed interface Srv {
        data class Present(val target: String) : Srv {
            override fun toString() = "PUBLISHED -> $target"
        }
        object Absent : Srv { override fun toString() = "none published" }
        object Skipped : Srv { override fun toString() = "skipped (local/non-routable)" }
    }

    /**
     * Resolve `_carddavs._tcp.<domain>` via the JDK's built-in JNDI DNS provider
     * (no external dependency). Returns the SRV target host, or Absent when the
     * provider publishes none. Local/loopback domains are Skipped — they have no
     * public DNS and SRV is irrelevant to a directly-targeted local server.
     */
    private fun srvLookup(domain: String): Srv {
        if (domain == "localhost" || domain.all { it.isDigit() || it == '.' } || !domain.contains('.')) {
            return Srv.Skipped
        }
        return try {
            val env = java.util.Hashtable<String, String>().apply {
                put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
                put("com.sun.jndi.dns.timeout.initial", "3000")
                put("com.sun.jndi.dns.timeout.retries", "1")
            }
            val ctx = javax.naming.directory.InitialDirContext(env)
            val attrs = ctx.getAttributes("_carddavs._tcp.$domain", arrayOf("SRV"))
            ctx.close()
            val srv = attrs.get("SRV")?.get() as? String
            if (srv == null) {
                Srv.Absent
            } else {
                // SRV rdata: "priority weight port target." — target is the last field.
                val target = srv.trim().split(Regex("\\s+")).lastOrNull()?.trimEnd('.').orEmpty()
                if (target.isBlank()) Srv.Absent else Srv.Present(target)
            }
        } catch (_: javax.naming.NameNotFoundException) {
            Srv.Absent
        } catch (_: Exception) {
            // Any resolver failure (no network, DNS blocked) — record as absent
            // rather than fail the probe; the well-known columns still carry signal.
            Srv.Absent
        }
    }
}
