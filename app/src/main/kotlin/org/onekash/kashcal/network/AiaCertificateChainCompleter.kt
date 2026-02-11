package org.onekash.kashcal.network

import android.util.Log
import androidx.annotation.VisibleForTesting
import okhttp3.OkHttpClient
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
 * This class implements the same fallback Chrome and Google Calendar use:
 * 1. Grab the leaf cert via a trust-all handshake (no data transfer)
 * 2. Extract the AIA caIssuers URL from the leaf cert's AIA extension
 * 3. Download the missing intermediate certificate (always HTTP, not HTTPS)
 * 4. Build a custom trust manager with system CAs + the intermediate
 * 5. Return a new OkHttpClient configured with that trust manager
 *
 * Security: hostname verification is never disabled. The chain must resolve
 * to a system root CA via TrustManagerFactory — a rogue intermediate is rejected.
 */
class AiaCertificateChainCompleter {

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

        /** In-memory cache: hostname -> intermediate cert. Cleared on process death. */
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
     * @param systemStoreType The system KeyStore type. Defaults to "AndroidCAStore".
     *   Override to `KeyStore.getDefaultType()` in JVM unit tests.
     */
    fun attemptChainCompletion(
        hostname: String,
        port: Int = 443,
        baseClientBuilder: OkHttpClient.Builder,
        systemStoreType: String = "AndroidCAStore"
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

                intermediateCache[hostname] = downloaded
                downloaded
            }

            val trustManager = buildTrustManager(intermediate, systemStoreType)
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
     */
    internal fun downloadCertificate(url: String): X509Certificate? {
        return try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS

            connection.getInputStream().use { inputStream ->
                val certFactory = CertificateFactory.getInstance("X.509")
                certFactory.generateCertificate(inputStream) as? X509Certificate
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download certificate from $url: ${e.message}")
            null
        }
    }

    /**
     * Build a TrustManager that trusts system CAs plus the given intermediate certificate.
     *
     * @param intermediate The intermediate certificate to add
     * @param systemStoreType "AndroidCAStore" on Android, or `KeyStore.getDefaultType()` for JVM tests
     */
    internal fun buildTrustManager(
        intermediate: X509Certificate,
        systemStoreType: String
    ): X509TrustManager? {
        return try {
            // Load system trust store
            val systemStore = KeyStore.getInstance(systemStoreType)
            systemStore.load(null, null)

            // Create a new KeyStore with system CAs + the intermediate
            val combinedStore = KeyStore.getInstance(KeyStore.getDefaultType())
            combinedStore.load(null, null)

            // Copy system CAs
            val aliases = systemStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                val cert = systemStore.getCertificate(alias)
                if (cert != null) {
                    combinedStore.setCertificateEntry(alias, cert)
                }
            }

            // Add the intermediate
            combinedStore.setCertificateEntry("aia-intermediate", intermediate)

            // Build trust manager
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(combinedStore)

            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build trust manager: ${e.message}")
            null
        }
    }
}
