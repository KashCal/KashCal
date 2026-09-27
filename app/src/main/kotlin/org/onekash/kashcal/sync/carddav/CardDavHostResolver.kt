package org.onekash.kashcal.sync.carddav

import android.util.Log
import org.onekash.kashcal.network.dns.SrvResolver
import org.onekash.kashcal.network.dns.SrvResult
import org.onekash.kashcal.network.dns.TxtResolver
import org.onekash.kashcal.network.dns.TxtResult

/**
 * Resolves an account's seed CardDAV base URL from its email domain per RFC 6764 §6.
 *
 * It answers only where the domain's CardDAV service lives. The well-known probe, principal
 * PROPFIND and home-set walk that follow are in
 * [org.onekash.kashcal.sync.contacts.ContactPullStrategy].
 *
 * The lookup order is the front of RFC 6764 §6:
 *   1. SRV `_carddavs._tcp.<domain>` gives host and port. Only the TLS service is queried;
 *      the plaintext `_carddav` never is, which closes a downgrade attack.
 *   2. TXT `_carddavs._tcp.<domain>` `path=` (RFC 6763 §6.4) gives the context path, queried
 *      only after a successful SRV lookup (§6 step 3).
 *   3. Otherwise the caller's `fallback`: the provider bootstrap host (iCloud's
 *      `contacts.icloud.com`, Zoho's host) or a user-configured home host. This is the §6
 *      step 2 well-known FQDN or manual last resort, so a provider with no SRV record (Zoho)
 *      or an unreachable resolver still syncs.
 *
 * ## Credential-redirection guard
 *
 * DNS is unauthenticated: a network attacker can forge `_carddavs._tcp.fastmail.com` to point
 * at `evil.com`, and honoring it would send the account's Basic credentials there. An SRV
 * target is accepted only when it is the query domain or in the same registrable
 * (public-suffix + 1) domain, via the same [DefaultRegistrableDomainResolver] that guards
 * contact-photo fetches. Real targets (`icloud.com` to `contacts.icloud.com`, `fastmail.com`
 * to its `carddav.fastmail.com` host) pass; a cross-domain or suffix-trick target falls
 * through to `fallback`.
 */
class CardDavHostResolver(
    private val srvResolver: SrvResolver,
    private val txtResolver: TxtResolver,
    private val registrableDomainOf: RegistrableDomainResolver = DefaultRegistrableDomainResolver,
) {

    /**
     * Returns the seed base URL for [emailDomain] (e.g. `icloud.com`), or [fallback] when SRV
     * yields no usable in-domain host. A blank [emailDomain] skips DNS and returns [fallback].
     */
    suspend fun resolveBaseUrl(emailDomain: String, fallback: String): String {
        if (emailDomain.isBlank()) return fallback

        // DNS is ASCII-only: an internationalized domain (e.g. "münchen.de") resolves only
        // in its A-label (punycode) form. Convert once so the SRV and TXT lookups and the
        // credential guard all use the wire form. Input IDN can't convert returns the
        // fallback instead of throwing.
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

        // The credential-redirection guard in the class doc. Both sides are always https, so no
        // scheme downgrade is possible.
        if (!shouldAttachCredentials("https://$asciiDomain", "https://${record.target}", registrableDomainOf)) {
            Log.w(TAG, "Rejecting cross-domain SRV target for $asciiDomain; falling back")
            return fallback
        }

        val host = if (record.port == HTTPS_PORT) {
            "https://${record.target}"
        } else {
            "https://${record.target}:${record.port}"
        }

        // RFC 6764 §6 step 3: TXT only after a successful SRV lookup. An absent, empty or
        // failed `path=` gives a host-only base URL.
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
