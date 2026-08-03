package org.onekash.kashcal.sync.carddav

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.onekash.kashcal.BuildConfig
import org.onekash.kashcal.sync.client.DigestAuthenticator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.Credentials as OkHttpCredentials
import org.onekash.kashcal.sync.auth.Credentials as AccountCredentials

/**
 * Factory for creating isolated [CardDavClient] instances — read path only.
 *
 * Mirrors [org.onekash.kashcal.sync.client.CalDavClientFactory] in shape while
 * staying firewall-isolated (it borrows no CalDAV *client* symbol, only the
 * shared [DigestAuthenticator] and the generic account [AccountCredentials]).
 * Each created client bakes immutable credentials into its own
 * `OkHttpClient` interceptor chain, so concurrent multi-account contact sync
 * is race-free — the same rationale as the CalDAV factory.
 */
interface CardDavClientFactory {
    /**
     * Create a new [CardDavClient] with immutable credentials.
     *
     * @param credentials the account credentials (username/password)
     * @param quirks provider-specific CardDAV quirks
     * @return a new client with credentials baked in
     */
    fun createClient(credentials: AccountCredentials, quirks: CardDavQuirks): CardDavClient
}

/**
 * OkHttp-based implementation of [CardDavClientFactory].
 *
 * Shares base configuration (timeouts, connection pool) across clients but gives
 * each its own auth interceptor with immutable credentials. The construction
 * plumbing intentionally duplicates the CalDAV factory rather than extracting a
 * shared base — see the package-level isolation rationale.
 */
@Singleton
class OkHttpCardDavClientFactory @Inject constructor() : CardDavClientFactory {

    companion object {
        private const val TAG = "CardDavClientFactory"

        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val MAX_IDLE_CONNECTIONS = 5
        private const val KEEP_ALIVE_DURATION_MINUTES = 5L
    }

    private val loggingInterceptor by lazy {
        HttpLoggingInterceptor { message ->
            val truncated = if (message.length > 1000) {
                message.take(1000) + "... [truncated ${message.length - 1000} chars]"
            } else {
                message
            }
            Log.d(TAG, truncated)
        }.apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    private val sharedConnectionPool by lazy {
        ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION_MINUTES, TimeUnit.MINUTES)
    }

    private val baseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .connectionPool(sharedConnectionPool)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    override fun createClient(credentials: AccountCredentials, quirks: CardDavQuirks): CardDavClient {
        val clientBuilder = baseHttpClient.newBuilder()
            // Digest auth: handle 401 Digest challenges (RFC 2617/7616). If the
            // server rejects the preemptive Basic header with a 401, this
            // Authenticator computes Digest credentials and OkHttp auto-retries.
            .authenticator(DigestAuthenticator(credentials.username, credentials.password))
            .addNetworkInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()

                // Preemptive Basic — but never overwrite an Authorization header
                // already set (e.g. the Digest header from a 401 retry), which
                // would cause an infinite 401 loop.
                if (chain.request().header("Authorization") == null) {
                    requestBuilder.header(
                        "Authorization",
                        OkHttpCredentials.basic(credentials.username, credentials.password, Charsets.UTF_8)
                    )
                }

                quirks.getAdditionalHeaders().forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }

                chain.proceed(requestBuilder.build())
            }

        if (credentials.trustInsecure) {
            Log.w(TAG, "Creating client with insecure SSL (user opted in)")
            configureTrustAllCertificates(clientBuilder)
        }

        val authenticatedClient = clientBuilder.build()

        Log.d(TAG, "Created isolated client for: ${credentials.username.take(3)}*** (trustInsecure=${credentials.trustInsecure})")

        return OkHttpCardDavClient(quirks, authenticatedClient)
    }

    /**
     * Trust all certificates. SECURITY WARNING: only when the user explicitly
     * opts in for self-signed certificates.
     */
    private fun configureTrustAllCertificates(builder: OkHttpClient.Builder) {
        val trustAllCerts = arrayOf<TrustManager>(
            @Suppress("CustomX509TrustManager")
            object : X509TrustManager {
                @Suppress("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                }

                @Suppress("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        builder.hostnameVerifier { _, _ -> true }
    }
}
