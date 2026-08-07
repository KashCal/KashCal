package org.onekash.kashcal.sync.carddav

import android.util.Log
import org.onekash.kashcal.network.dns.SrvResolver
import org.onekash.kashcal.network.dns.SrvResult
import org.onekash.kashcal.network.dns.TxtResolver
import org.onekash.kashcal.network.dns.TxtResult

/**
 * Resolves the seed CardDAV base URL for an account per RFC 6764 §6, sitting
 * between the sync worker and the pure DNS library. It answers only the discovery
 * question — "where does this domain's CardDAV service live?" — and returns a base
 * URL; the downstream well-known probe, principal PROPFIND, and home-set walk stay
 * in [org.onekash.kashcal.sync.contacts.ContactPullStrategy] unchanged.
 *
 * The ladder it owns is the front of RFC 6764 §6:
 *   1. SRV `_carddavs._tcp.<domain>` (TLS-only; the plaintext `_carddav` service is
 *      never queried, closing a downgrade attack) → host + port.
 *   2. TXT `_carddavs._tcp.<domain>` `path=` (RFC 6763 §6.4) → context path, queried
 *      *only* after a successful SRV lookup, per §6 step 3.
 *   3. otherwise the caller's [fallback] — the provider bootstrap constant (iCloud's
 *      `contacts.icloud.com`, Zoho's host) or a user-configured home host. This is
 *      the §6 step-2 well-known FQDN / manual last resort, so a provider with no SRV
 *      record (Zoho) or an unreachable resolver keeps working exactly as before.
 *
 * **Credential-redirection guard (security-critical).** DNS is unauthenticated;
 * a network attacker can forge `_carddavs._tcp.fastmail.com → evil.com` and, if
 * honored, the account's HTTP Basic credentials would be sent to `evil.com`. So an
 * SRV target is accepted only when it is the query domain itself or within the same
 * registrable (public-suffix + 1) domain, reusing the same
 * [DefaultRegistrableDomainResolver] that guards contact-photo fetches. Every real
 * target (`icloud.com→contacts.icloud.com`, `fastmail.com→…carddav.fastmail.com`)
 * already satisfies it; a cross-domain or suffix-trick target is rejected and the
 * ladder falls through to [fallback].
 */
class CardDavHostResolver(
    private val srvResolver: SrvResolver,
    private val txtResolver: TxtResolver,
    private val registrableDomainOf: RegistrableDomainResolver = DefaultRegistrableDomainResolver,
) {

    /**
     * Resolve the seed base URL for [emailDomain] (the account's email domain, e.g.
     * `icloud.com`), falling back to [fallback] when SRV yields no usable in-domain
     * host. A blank [emailDomain] skips DNS entirely and returns [fallback].
     */
    suspend fun resolveBaseUrl(emailDomain: String, fallback: String): String {
        if (emailDomain.isBlank()) return fallback

        // DNS is ASCII-only: an internationalized domain (e.g. "münchen.de") must be
        // queried in its A-label/punycode form or it could never resolve. Normalize
        // once, up front, so both the SRV/TXT lookups and the credential-domain guard
        // operate on the wire form. A malformed input that IDN can't convert degrades
        // to the fallback rather than throwing.
        val asciiDomain = try {
            java.net.IDN.toASCII(emailDomain)
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Email domain not convertible to an A-label; falling back")
            return fallback
        }

        val record = when (val srv = srvResolver.resolve(SERVICE, PROTO, asciiDomain)) {
            is SrvResult.Found -> srv.records.first()  // already RFC 2782-ordered
            SrvResult.NotAvailable, SrvResult.NoRecords, is SrvResult.Error -> return fallback
        }

        // Credential-redirection guard: accept the SRV target only if the account's
        // credentials may legitimately travel to it — the same registrable-domain
        // check that gates contact-photo fetches. Both sides are always https, so no
        // scheme downgrade is possible; a cross-domain or suffix-trick target fails.
        if (!shouldAttachCredentials("https://$asciiDomain", "https://${record.target}", registrableDomainOf)) {
            Log.w(TAG, "Rejecting cross-domain SRV target for $asciiDomain; falling back")
            return fallback
        }

        val host = if (record.port == HTTPS_PORT) {
            "https://${record.target}"
        } else {
            "https://${record.target}:${record.port}"
        }

        // RFC 6764 §6 step 3: query TXT for a `path=` context path only after a
        // successful SRV lookup. Absent/empty/failed → host-only base URL.
        val path = when (val txt = txtResolver.resolvePath(SERVICE, PROTO, asciiDomain)) {
            is TxtResult.Path -> txt.value
            TxtResult.NoPath, is TxtResult.Error -> ""
        }
        return if (path.isBlank()) host else host + if (path.startsWith("/")) path else "/$path"
    }

    private companion object {
        private const val TAG = "CardDavHostResolver"
        private const val SERVICE = "carddavs"  // TLS-only; never the plaintext `_carddav`
        private const val PROTO = "tcp"
        private const val HTTPS_PORT = 443
    }
}
