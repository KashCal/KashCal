package org.onekash.kashcal.network

import android.util.Log
import androidx.annotation.VisibleForTesting
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * AIA (Authority Information Access) certificate chain completer.
 *
 * Some servers serve incomplete SSL certificate chains — the leaf cert is present
 * but the intermediate is missing. OkHttp's TLS handshake fails with SSLHandshakeException.
 *
 * This class implements the same fallback major browsers and calendar apps use:
 * 1. Grab the leaf cert via a trust-all handshake (no data transfer)
 * 2. Extract the AIA caIssuers URL from the leaf cert's AIA extension
 * 3. Download the missing intermediate certificate (always HTTP, not HTTPS),
 *    refusing non-public fetch targets and capping the response size
 * 4. Build a trust manager that appends the intermediate to the server's chain
 *    and validates the completed path against the system roots (PKIX)
 * 5. Return a new OkHttpClient configured with that trust manager
 *
 * Security: hostname verification is never disabled. The downloaded cert is
 * treated only as a candidate intermediate — it is appended to the chain the
 * server presents and the whole path must still validate to a real system root
 * via the platform's PKIX trust manager. It never becomes a trust anchor of its
 * own, so a rogue cert that reaches no system root is rejected.
 */
class AiaCertificateChainCompleter(
    /**
     * Test-only escape hatch. Production always refuses AIA fetches to
     * loopback/link-local/private/any-local addresses (an attacker-controlled AIA
     * URL must not be usable to probe the device's own network — blind SSRF).
     * MockWebServer binds to loopback, so unit tests that exercise a real download
     * opt in with `allowLocalFetchTargets = true`.
     */
    @get:VisibleForTesting internal val allowLocalFetchTargets: Boolean = false,
) {

    sealed class Result {
        data class Success(val client: OkHttpClient) : Result()
        data class Failed(val reason: String) : Result()
    }

    companion object {
        private const val TAG = "AiaCertChainCompleter"

        /** OID for Authority Information Access (RFC 5280, section 4.2.2.1) */
        private const val AIA_OID = "1.3.6.1.5.5.7.1.1"

        /** Timeout for trust-all handshake and cert download */
        private const val TIMEOUT_MS = 5_000

        /**
         * Hard ceiling on an AIA download body. A single DER/PEM intermediate is a
         * few KB; anything past 1 MB is either a misconfigured endpoint or an attempt
         * to exhaust memory via the attacker-controlled AIA URL, so we bail rather
         * than buffer it.
         */
        private const val MAX_CERT_BYTES = 1L * 1024 * 1024

        /**
         * In-memory cache: hostname -> intermediate cert. Cleared on process death.
         * Only populated once the platform trust manager has accepted a completed
         * chain for that host, so an unvalidated (attacker-seeded) cert is never stored.
         */
        private val intermediateCache = ConcurrentHashMap<String, X509Certificate>()

        /**
         * Trim trailing ASN.1 tag bytes stuck to a cert file extension.
         * DER-encoded AIA has no delimiter between the URL and the next ASN.1 tag,
         * so bytes like 0x30 ('0') can stick to the URL end.
         * Only trims alphanumeric junk — preserves query strings (?v=2) and paths.
         * Example: "...R36.crt0" → "...R36.crt"
         */
        private val TRAILING_ASN1_JUNK = Regex("""(\.(?:crt|cer|der|pem|p7b|p7c))[a-zA-Z0-9]*$""", RegexOption.IGNORE_CASE)

        @VisibleForTesting
        fun clearCacheForTesting() {
            intermediateCache.clear()
        }

        @VisibleForTesting
        fun isIntermediateCachedForTesting(hostname: String): Boolean =
            intermediateCache.containsKey(hostname)
    }

    /**
     * Attempt to complete a broken certificate chain via AIA.
     *
     * Never throws — always returns [Result.Failed] on any error.
     *
     * @param hostname The server hostname
     * @param port The server port (default 443)
     * @param baseClientBuilder An OkHttpClient.Builder to configure with the custom trust manager.
     *   Use `existingClient.newBuilder()` to preserve connection pool and timeouts.
     */
    fun attemptChainCompletion(
        hostname: String,
        port: Int = 443,
        baseClientBuilder: OkHttpClient.Builder
    ): Result {
        return try {
            // Check cache first
            val cached = intermediateCache[hostname]
            val intermediate = if (cached != null) {
                Log.d(TAG, "Using cached intermediate for $hostname")
                cached
            } else {
                val leaf = getLeafCertificate(hostname, port)
                    ?: return Result.Failed("Could not retrieve leaf certificate")

                val aiaUrl = extractAiaCaIssuersUrl(leaf)
                    ?: return Result.Failed("No AIA caIssuers URL in leaf certificate")

                Log.d(TAG, "AIA URL: $aiaUrl")

                val downloaded = downloadCertificate(aiaUrl)
                    ?: return Result.Failed("Could not download intermediate from $aiaUrl")

                // NOT cached here: the download is attacker-influenceable (trust-all
                // leaf fetch + plain-HTTP fetch). Caching it now would let an on-path
                // attacker seed a bogus intermediate that breaks genuine connections to
                // this host until the process restarts. We cache only after the platform
                // validator accepts a completed chain (see buildTrustManager).
                downloaded
            }

            val trustManager = buildTrustManager(intermediate, hostname)
                ?: return Result.Failed("Could not build trust manager with intermediate")

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), null)

            val client = baseClientBuilder
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                // Hostname verification stays ON (default) — never disabled
                .build()

            Log.i(TAG, "AIA chain completion succeeded for $hostname")
            Result.Success(client)
        } catch (e: Exception) {
            Log.e(TAG, "AIA chain completion failed for $hostname: ${e.message}", e)
            Result.Failed("Unexpected error: ${e.message}")
        }
    }

    /**
     * Connect with a trust-all TrustManager to retrieve the leaf certificate.
     * No HTTP data is transferred — socket is closed immediately after handshake.
     */
    internal fun getLeafCertificate(hostname: String, port: Int): X509Certificate? {
        // Capture the server chain in a local variable accessible from the anonymous object
        var capturedChain: Array<X509Certificate>? = null

        val trustAllManager = object : X509TrustManager {
            @Suppress("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

            @Suppress("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                capturedChain = chain
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), null)

        return try {
            (sslContext.socketFactory.createSocket(hostname, port) as SSLSocket).use { socket ->
                socket.soTimeout = TIMEOUT_MS
                socket.startHandshake()
                // Leaf cert is first in the chain
                capturedChain?.firstOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve leaf cert from $hostname:$port: ${e.message}")
            null
        }
    }

    /**
     * Extract the caIssuers URL from a certificate's AIA extension.
     *
     * Known limitation: uses a heuristic (scanning for "http://" in the DER-encoded
     * extension bytes) rather than full ASN.1 parsing. This works for all known CA
     * AIA extensions but could theoretically miss unusual encodings. If it fails,
     * the caller falls back to the original SSL error.
     */
    internal fun extractAiaCaIssuersUrl(cert: X509Certificate): String? {
        val extensionBytes = cert.getExtensionValue(AIA_OID) ?: return null

        // The extension value is wrapped in an OCTET STRING. Convert to a string
        // using ISO-8859-1 (1:1 byte mapping) and scan for an HTTP URL.
        val asString = String(extensionBytes, Charsets.ISO_8859_1)
        val httpIndex = asString.indexOf("http://")
        if (httpIndex < 0) return null

        // Extract URL: runs until a non-URI character.
        // Stop at control chars, high bytes, whitespace, and fragment delimiter '#'
        // (RFC 3986: '#' starts fragment — AIA URLs never have fragments).
        val urlBuilder = StringBuilder()
        for (i in httpIndex until asString.length) {
            val ch = asString[i]
            if (ch.code in 0x21..0x7E && ch != '#') {
                urlBuilder.append(ch)
            } else {
                break
            }
        }

        // DER encoding may leave ASN.1 tag bytes (e.g., 0x30 = '0') appended
        // to the URL. Trim any trailing characters after the last dot-extension.
        val url = urlBuilder.toString()
        val trimmed = TRAILING_ASN1_JUNK.replace(url, "$1")
        return if (trimmed.length > "http://".length) trimmed else null
    }

    /**
     * Download a DER-encoded certificate from a URL (typically HTTP, not HTTPS).
     *
     * The URL comes from the leaf cert's AIA extension, which is attacker-influenceable
     * on the very path where trust has not yet been established, so the fetch is doubly
     * bounded: [isAllowedFetchUrl] refuses non-public destinations (blind-SSRF guard) and
     * the body is capped at [MAX_CERT_BYTES] (memory-exhaustion guard). Downloading over
     * plain HTTP reveals the target host to a passive network observer, which is
     * acceptable here because the fetched cert is only ever a *candidate* — it must still
     * validate to a system root before it is trusted (see [buildTrustManager]).
     */
    internal fun downloadCertificate(url: String): X509Certificate? {
        if (!isAllowedFetchUrl(url)) {
            Log.w(TAG, "Refusing AIA fetch to a non-public address: $url")
            return null
        }
        return try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection is HttpURLConnection) {
                // Do NOT auto-follow redirects: isAllowedFetchUrl only cleared the
                // ORIGINAL host, so following an attacker-directed 3xx (e.g. to a
                // cloud metadata address or loopback) would walk straight past the
                // SSRF guard. A caIssuers endpoint is a static file, so any non-2xx
                // response is treated as a failed fetch rather than chased.
                connection.instanceFollowRedirects = false
                if (connection.responseCode !in 200..299) {
                    Log.w(TAG, "AIA fetch returned HTTP ${connection.responseCode}, not following: $url")
                    return null
                }
            }

            connection.getInputStream().use { inputStream ->
                val bytes = readCapped(inputStream, MAX_CERT_BYTES) ?: run {
                    Log.w(TAG, "AIA certificate exceeded $MAX_CERT_BYTES bytes: $url")
                    return null
                }
                val certFactory = CertificateFactory.getInstance("X.509")
                certFactory.generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download certificate from $url: ${e.message}")
            null
        }
    }

    /**
     * True if [url]'s host resolves to a routable, public address. Refuses
     * loopback/link-local/site-local/any-local/multicast targets so an
     * attacker-supplied AIA URL cannot be used to probe the device's own network.
     * A host that cannot be resolved is treated as not allowed.
     */
    @VisibleForTesting
    internal fun isAllowedFetchUrl(url: String): Boolean {
        if (allowLocalFetchTargets) return true
        return try {
            val host = URL(url).host
            if (host.isNullOrEmpty()) return false
            // Refuse if ANY resolved address is non-public, so a multi-record host
            // that mixes a public IP with 127.0.0.1 can't slip through on the public
            // one. Residual: the connection re-resolves the host independently, so a
            // low-TTL rebinding DNS server could still flip to an internal address
            // after this check. That risk is bounded here — the fetch is blind (its
            // body only ever feeds CertificateFactory) and a fetched cert must still
            // chain to a system root before it is trusted, so rebinding yields no
            // oracle back to the attacker and cannot cause mis-trust.
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.none { isBlockedFetchAddress(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve AIA host for $url: ${e.message}")
            false
        }
    }

    /**
     * Non-routable / internal address ranges an AIA fetch must never reach. Covers
     * the JDK's built-in categories plus ranges its helpers miss: IPv4 0.0.0.0/8,
     * CGNAT 100.64.0.0/10 (RFC 6598), the limited broadcast address, and IPv6
     * unique-local fc00::/7 (isSiteLocalAddress only matches the deprecated fec0::/10).
     */
    private fun isBlockedFetchAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isAnyLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        return when (bytes.size) {
            4 -> {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                b0 == 0 ||
                    (b0 == 100 && b1 in 64..127) ||
                    bytes.all { (it.toInt() and 0xFF) == 255 }
            }
            16 -> (bytes[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }

    /**
     * Read [input] fully, but abort (returning null) as soon as it exceeds [max] bytes.
     */
    private fun readCapped(input: InputStream, max: Long): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > max) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * Build a TrustManager that completes a broken chain with [intermediate] while
     * still requiring the result to chain to a real system root.
     *
     * The downloaded cert is NOT added as a trust anchor. Instead we return a wrapper
     * around the platform's default trust manager (system anchors only): on each
     * connection it appends [intermediate] to the chain the server presents and hands
     * the completed chain to the platform validator, which builds a PKIX path to a
     * system root. If [intermediate] genuinely bridges the gap the connection succeeds;
     * a cert that reaches no system root — an attacker's own CA — is rejected.
     *
     * The intermediate is cached for [hostname] only once the platform validator
     * has accepted a completed chain, so an unvalidated download is never stored.
     *
     * @param intermediate The candidate intermediate certificate fetched via AIA
     * @param hostname The host this intermediate completes a chain for (cache key)
     */
    internal fun buildTrustManager(intermediate: X509Certificate, hostname: String): X509TrustManager? {
        return try {
            // Default trust manager backed by the system trust anchors only.
            // init(null) uses the platform store (AndroidCAStore on Android, the JRE
            // cacerts on the JVM) — never a store we've mutated with the download.
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val systemTrustManager = tmf.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull() ?: return null

            object : X509TrustManager {
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    // Splice the fetched intermediate into the presented chain, then let
                    // the platform validator build (and verify) a path to a system root.
                    val completed = if (chain.any { it == intermediate }) {
                        chain
                    } else {
                        chain + intermediate
                    }
                    // Throws if the completed chain reaches no system root. Only past
                    // this point has the intermediate proven itself, so cache it here
                    // (never on the raw, unvalidated download) to close cache poisoning.
                    systemTrustManager.checkServerTrusted(completed, authType)
                    intermediateCache[hostname] = intermediate
                }

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    systemTrustManager.checkClientTrusted(chain, authType)
                }

                // Only the system anchors are advertised — the downloaded intermediate
                // is deliberately not one. Note for the future: if certificate pinning
                // is ever added to these clients, OkHttp's chain cleaner is derived from
                // these accepted issuers and would be unable to build a path through the
                // (non-anchor, not-yet-presented) intermediate, breaking pinned
                // connections to missing-intermediate servers. Revisit this if pinning lands.
                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    systemTrustManager.acceptedIssuers
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build trust manager: ${e.message}")
            null
        }
    }
}
