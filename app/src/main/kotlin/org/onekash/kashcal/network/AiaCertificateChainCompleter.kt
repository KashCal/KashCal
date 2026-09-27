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
 * Completes a server's certificate chain that is missing its intermediate, using the leaf's
 * AIA (Authority Information Access) extension.
 *
 * Such a server fails OkHttp's handshake with `SSLHandshakeException`. The completer:
 * 1. Reads the leaf cert through a trust-all handshake that sends no data.
 * 2. Extracts the caIssuers URL from the leaf's AIA extension.
 * 3. Downloads the intermediate over plain HTTP, refusing non-public targets and capping the
 *    body size.
 * 4. Builds a trust manager that appends the intermediate to the server's chain and validates
 *    the completed path against the system roots (PKIX).
 * 5. Returns a new OkHttpClient with that trust manager.
 *
 * Hostname verification is never disabled. The downloaded cert is only a candidate
 * intermediate and never a trust anchor, so a rogue cert that reaches no system root is
 * rejected.
 */
class AiaCertificateChainCompleter(
    /**
     * Allows AIA fetches to local addresses; tests only. Production always refuses them
     * ([isAllowedFetchUrl]) so an attacker-controlled AIA URL can't probe the device's own
     * network (blind SSRF). MockWebServer binds to loopback, so tests of a real download opt in.
     */
    @get:VisibleForTesting internal val allowLocalFetchTargets: Boolean = false,
) {

    sealed class Result {
        data class Success(val client: OkHttpClient) : Result()
        data class Failed(val reason: String) : Result()
    }

    companion object {
        private const val TAG = "AiaCertChainCompleter"

        /** OID for Authority Information Access (RFC 5280 §4.2.2.1). */
        private const val AIA_OID = "1.3.6.1.5.5.7.1.1"

        /** Read timeout for the trust-all handshake; connect and read timeout for the download. */
        private const val TIMEOUT_MS = 5_000

        /**
         * Caps an AIA download body. One DER or PEM intermediate is a few KB; a body past 1 MB
         * is a misconfigured endpoint or an attempt to exhaust memory, so the download fails.
         */
        private const val MAX_CERT_BYTES = 1L * 1024 * 1024

        /**
         * Maps hostname to its intermediate cert for the life of the process. Written only after
         * the platform trust manager accepts a completed chain for that host, so an unvalidated
         * (attacker-seeded) cert is never stored.
         */
        private val intermediateCache = ConcurrentHashMap<String, X509Certificate>()

        /**
         * Matches ASN.1 tag bytes stuck to a cert file extension at the URL end. DER-encoded
         * AIA has no delimiter between the URL and the next tag, so a byte like 0x30 ('0') can
         * follow the URL: "...R36.crt0" becomes "...R36.crt". Only alphanumerics after a known
         * extension are trimmed, so query strings (?v=2) and paths survive.
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
     * Completes [hostname]'s broken certificate chain via AIA, reusing a cached intermediate
     * when one exists. Never throws: every failure, an exception included, returns
     * [Result.Failed].
     *
     * @param baseClientBuilder builder to configure with the custom trust manager. Pass
     *   `existingClient.newBuilder()` to keep its connection pool and timeouts.
     */
    fun attemptChainCompletion(
        hostname: String,
        port: Int = 443,
        baseClientBuilder: OkHttpClient.Builder
    ): Result {
        return try {
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

                // Not cached here: the trust-all leaf fetch and plain-HTTP download are
                // attacker-influenceable, and a cached bogus intermediate would break real
                // connections to this host until the process restarts. [buildTrustManager]
                // caches it once the platform validator accepts a completed chain.
                downloaded
            }

            val trustManager = buildTrustManager(intermediate, hostname)
                ?: return Result.Failed("Could not build trust manager with intermediate")

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), null)

            val client = baseClientBuilder
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                // Hostname verification keeps its default and is never disabled.
                .build()

            Log.i(TAG, "AIA chain completion succeeded for $hostname")
            Result.Success(client)
        } catch (e: Exception) {
            Log.e(TAG, "AIA chain completion failed for $hostname: ${e.message}", e)
            Result.Failed("Unexpected error: ${e.message}")
        }
    }

    /**
     * Returns the server's leaf certificate from a trust-all handshake, or null on failure. No
     * HTTP data is sent; the socket closes right after the handshake.
     */
    internal fun getLeafCertificate(hostname: String, port: Int): X509Certificate? {
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
                // The leaf is first in the chain.
                capturedChain?.firstOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve leaf cert from $hostname:$port: ${e.message}")
            null
        }
    }

    /**
     * Returns the first `http://` URL in [cert]'s AIA extension, or null if there is none.
     *
     * Scans the DER bytes for "http://" instead of parsing ASN.1, so an unusual encoding can
     * be missed, and the callers then report an SSL error.
     */
    internal fun extractAiaCaIssuersUrl(cert: X509Certificate): String? {
        val extensionBytes = cert.getExtensionValue(AIA_OID) ?: return null

        // The value is wrapped in an OCTET STRING; ISO-8859-1 maps bytes 1:1 to chars.
        val asString = String(extensionBytes, Charsets.ISO_8859_1)
        val httpIndex = asString.indexOf("http://")
        if (httpIndex < 0) return null

        // The URL ends at a control char, high byte, space or '#' (RFC 3986 fragment
        // delimiter; AIA URLs have no fragment).
        val urlBuilder = StringBuilder()
        for (i in httpIndex until asString.length) {
            val ch = asString[i]
            if (ch.code in 0x21..0x7E && ch != '#') {
                urlBuilder.append(ch)
            } else {
                break
            }
        }

        val url = urlBuilder.toString()
        val trimmed = TRAILING_ASN1_JUNK.replace(url, "$1")
        return if (trimmed.length > "http://".length) trimmed else null
    }

    /**
     * Downloads the certificate at [url], or returns null on a refused target, non-2xx
     * response, oversized body or parse failure.
     *
     * The URL comes from the not-yet-trusted leaf, so [isAllowedFetchUrl] refuses non-public
     * targets (blind SSRF) and the body is capped at [MAX_CERT_BYTES]. Plain HTTP reveals the
     * host to a passive observer, which is acceptable: the cert is only a candidate until it
     * validates to a system root in [buildTrustManager].
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
                // Never follow redirects: [isAllowedFetchUrl] cleared only the original
                // host, so an attacker-directed 3xx (to a cloud metadata address or
                // loopback) would bypass the SSRF guard. A caIssuers endpoint is a static
                // file, so any non-2xx response fails the fetch.
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
     * Returns true if every address [url]'s host resolves to is public ([isBlockedFetchAddress]),
     * so an attacker-supplied AIA URL can't probe the device's own network. An unresolvable
     * host is refused; [allowLocalFetchTargets] allows everything.
     */
    @VisibleForTesting
    internal fun isAllowedFetchUrl(url: String): Boolean {
        if (allowLocalFetchTargets) return true
        return try {
            val host = URL(url).host
            if (host.isNullOrEmpty()) return false
            // Any non-public address refuses, so a host mixing a public IP with 127.0.0.1
            // can't pass on the public one. The connection re-resolves the host, so a
            // low-TTL rebinding DNS server could still flip to an internal address after
            // this check. The fetch is blind (its body only feeds CertificateFactory) and
            // the cert must still chain to a system root, so rebinding gives the attacker
            // no oracle and can't cause mis-trust.
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.none { isBlockedFetchAddress(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve AIA host for $url: ${e.message}")
            false
        }
    }

    /**
     * Returns true for an internal or non-routable address an AIA fetch must never reach: the
     * JDK's loopback, link-local, site-local, any-local and multicast checks, plus ranges they
     * miss: IPv4 0.0.0.0/8, CGNAT 100.64.0.0/10 (RFC 6598), the limited broadcast address, and
     * IPv6 unique-local fc00::/7 (`isSiteLocalAddress` matches only the deprecated fec0::/10).
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

    /** Reads [input] fully, or returns null as soon as it exceeds [max] bytes. */
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
     * Builds a trust manager that completes the server's chain with [intermediate] and still
     * requires it to reach a system root, or returns null if none can be built.
     *
     * [intermediate] is never a trust anchor. The returned wrapper around the platform's
     * default trust manager appends it to each presented chain and lets the platform build a
     * PKIX path to a system root; a cert that reaches none, such as an attacker's own CA, is
     * rejected. [intermediate] is cached for [hostname] only after a completed chain passes.
     */
    internal fun buildTrustManager(intermediate: X509Certificate, hostname: String): X509TrustManager? {
        return try {
            // init(null) uses the platform's system anchors (AndroidCAStore on Android, the
            // JRE cacerts on the JVM), never a store holding the download.
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val systemTrustManager = tmf.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull() ?: return null

            object : X509TrustManager {
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val completed = if (chain.any { it == intermediate }) {
                        chain
                    } else {
                        chain + intermediate
                    }
                    // Throws if the completed chain reaches no system root, so the cache
                    // write below only ever stores a validated intermediate.
                    systemTrustManager.checkServerTrusted(completed, authType)
                    intermediateCache[hostname] = intermediate
                }

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    systemTrustManager.checkClientTrusted(chain, authType)
                }

                // Advertises only the system anchors; the intermediate is deliberately not
                // one. If certificate pinning is added to these clients, OkHttp's chain
                // cleaner, built from these issuers, can't build a path through the
                // intermediate, which breaks pinned connections to missing-intermediate
                // servers. Revisit this if pinning lands.
                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    systemTrustManager.acceptedIssuers
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build trust manager: ${e.message}")
            null
        }
    }
}
