package org.onekash.kashcal.sync.carddav

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.onekash.kashcal.BuildConfig
import org.onekash.kashcal.network.DavTransportGuard
import org.onekash.kashcal.network.installDavTransport
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
 * Creates [CardDavClient] instances, each with its own immutable credentials.
 *
 * Mirrors [org.onekash.kashcal.sync.client.CalDavClientFactory] in shape but borrows no CalDAV
 * client symbol, only the shared [DigestAuthenticator] and the generic [AccountCredentials]
 * (`CardDavCalDavIsolationTest`). Each client bakes its credentials into its own `OkHttpClient`
 * interceptor chain, so concurrent multi-account contact sync can't mix them up.
 */
interface CardDavClientFactory {
    /**
     * Creates a client with [credentials] baked in, sent only on requests
     * [DavTransportGuard.mayAttachCredentials] allows.
     */
    fun createClient(credentials: AccountCredentials, quirks: CardDavQuirks): CardDavClient
}

/**
 * Builds OkHttp-backed [CardDavClient]s.
 *
 * Clients share timeouts and a connection pool but each gets its own auth interceptor. The
 * construction code duplicates the CalDAV factory on purpose; a shared base would couple the two
 * stacks (`CardDavCalDavIsolationTest`).
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
            .connectionPool(sharedConnectionPool)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    override fun createClient(credentials: AccountCredentials, quirks: CardDavQuirks): CardDavClient {
        // Plain http only for an account the user set up with http://; everything
        // else is refused before it is sent, and Basic/Digest never go over it.
        val allowCleartext = DavTransportGuard.allowsCleartext(credentials.serverUrl)
        val clientBuilder = baseHttpClient.newBuilder()
            // Follow redirects ourselves with the same method and body (OkHttp would
            // turn a redirected PUT or DELETE into a GET that looks like a success).
            .installDavTransport(allowCleartext)
            // Answers a 401 Digest challenge (RFC 2617/7616) to the preemptive Basic
            // header; OkHttp then retries with the Digest credentials.
            .authenticator(DigestAuthenticator(credentials.username, credentials.password, allowCleartext))
            .addNetworkInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()

                // Preemptive Basic, but never over an Authorization header already set
                // (e.g. the Digest header of a 401 retry): that would loop on 401 forever.
                if (chain.request().header("Authorization") == null &&
                    DavTransportGuard.mayAttachCredentials(chain.request().url, allowCleartext)
                ) {
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

    /** Trusts every certificate; only for an account whose user opted in to self-signed ones. */
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
