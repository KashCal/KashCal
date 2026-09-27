package org.onekash.kashcal.sync.client

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.onekash.kashcal.BuildConfig
import org.onekash.kashcal.network.DavTransportGuard
import org.onekash.kashcal.network.installDavTransport
import org.onekash.kashcal.sync.quirks.CalDavQuirks
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
 * Creates one [CalDavClient] per account, with that account's credentials fixed in its
 * OkHttp interceptor chain.
 *
 * A shared client with mutable credentials would race when accounts sync concurrently: one
 * account's requests could go out with another's password. Callers (`CalDavSyncWorker`, the
 * account discovery services) create a client per account; a client's credentials never change.
 */
interface CalDavClientFactory {
    /** Creates a client that authenticates every request with [credentials]. */
    fun createClient(credentials: AccountCredentials, quirks: CalDavQuirks): CalDavClient
}

/**
 * Creates [OkHttpCalDavClient]s that share timeouts, logging and a connection pool, each
 * with its own auth interceptor and [DigestAuthenticator].
 */
@Singleton
class OkHttpCalDavClientFactory @Inject constructor() : CalDavClientFactory {

    companion object {
        private const val TAG = "CalDavClientFactory"

        // Same values as OkHttpCalDavClient's own defaults.
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val MAX_IDLE_CONNECTIONS = 5
        private const val KEEP_ALIVE_DURATION_MINUTES = 5L
    }

    /**
     * Logs request and response headers in debug builds, nothing in release. As an application
     * interceptor it runs before the network interceptor adds Authorization, so credentials
     * aren't logged.
     */
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

    /**
     * Connection pool shared by all clients. Sharing is safe: credentials go on each request,
     * not on the connection.
     */
    private val sharedConnectionPool by lazy {
        ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION_MINUTES, TimeUnit.MINUTES)
    }

    /** Shared base that each created client extends with its own auth. */
    private val baseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(sharedConnectionPool)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    override fun createClient(credentials: AccountCredentials, quirks: CalDavQuirks): CalDavClient {
        // Thread-safe because newBuilder() copies the base: each account's interceptors stay
        // its own.
        // Plain http is allowed only for an account the user set up with http://; any other
        // plain http request is refused before it is sent, so Basic and Digest never go over it.
        val allowCleartext = DavTransportGuard.allowsCleartext(credentials.serverUrl)
        val clientBuilder = baseHttpClient.newBuilder()
            // Follow redirects ourselves with the same method and body (OkHttp would
            // turn a redirected PUT or DELETE into a GET that looks like a success).
            .installDavTransport(allowCleartext)
            // A Digest server rejects the preemptive Basic with a 401 challenge; this
            // authenticator answers it (RFC 2617/7616) and OkHttp retries.
            .authenticator(DigestAuthenticator(credentials.username, credentials.password, allowCleartext))
            .addNetworkInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()

                // Preemptive Basic, only when no Authorization header is present: the
                // retry after a Digest challenge carries a Digest header, which must not be
                // overwritten with Basic or the request loops on 401 forever.
                if (chain.request().header("Authorization") == null &&
                    DavTransportGuard.mayAttachCredentials(chain.request().url, allowCleartext)
                ) {
                    // Issue #49: Use UTF-8 encoding for non-ASCII passwords (RFC 7617)
                    requestBuilder.header(
                        "Authorization",
                        OkHttpCredentials.basic(credentials.username, credentials.password, Charsets.UTF_8)
                    )
                }

                // Provider-specific headers.
                quirks.getAdditionalHeaders().forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }

                chain.proceed(requestBuilder.build())
            }

        // The user opted in to trusting self-signed certificates for this account.
        if (credentials.trustInsecure) {
            Log.w(TAG, "Creating client with insecure SSL (user opted in)")
            configureTrustAllCertificates(clientBuilder)
        }

        val authenticatedClient = clientBuilder.build()

        Log.d(TAG, "Created isolated client for: ${credentials.username.take(3)}*** (trustInsecure=${credentials.trustInsecure})")

        return OkHttpCalDavClient(quirks, authenticatedClient)
    }

    /**
     * Makes [builder] trust every certificate and hostname. Call only when the user has opted
     * in for self-signed certificates; it disables TLS server authentication.
     */
    private fun configureTrustAllCertificates(builder: OkHttpClient.Builder) {
        val trustAllCerts = arrayOf<TrustManager>(
            @Suppress("CustomX509TrustManager")
            object : X509TrustManager {
                @Suppress("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    // Trust all client certificates
                }

                @Suppress("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    // Trust all server certificates
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        builder.hostnameVerifier { _, _ -> true }  // Accept any hostname
    }
}
