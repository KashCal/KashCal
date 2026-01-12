package org.onekash.kashcal.sync.client

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.onekash.kashcal.BuildConfig
import org.onekash.kashcal.sync.auth.Credentials as AccountCredentials
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating isolated CalDavClient instances.
 *
 * This solves the credential race condition in multi-account sync:
 * - Old pattern: Singleton CalDavClient with mutable credentials (race condition!)
 * - New pattern: Factory creates isolated client per account (thread-safe)
 *
 * Each client created by this factory has immutable credentials baked into
 * its OkHttpClient interceptor chain. This ensures:
 * - No credential mutation during sync
 * - Thread-safe concurrent sync of multiple accounts
 * - Clear ownership: each account sync owns its client
 *
 * Usage:
 * ```
 * for (account in accounts) {
 *     val credentials = credProvider.getCredentials(account.id) ?: continue
 *     val client = calDavClientFactory.createClient(credentials, quirks)
 *     syncEngine.syncAccountWithClient(account, client)
 * }
 * ```
 */
interface CalDavClientFactory {
    /**
     * Create a new CalDavClient with immutable credentials.
     *
     * @param credentials The account credentials (username/password)
     * @param quirks Provider-specific CalDAV quirks
     * @return A new CalDavClient instance with credentials baked in
     */
    fun createClient(credentials: AccountCredentials, quirks: CalDavQuirks): CalDavClient
}

/**
 * OkHttp-based implementation of CalDavClientFactory.
 *
 * Creates OkHttpCalDavClient instances with pre-authenticated OkHttpClient.
 * Shares base configuration (timeouts, connection pool) but each client
 * has its own auth interceptor with immutable credentials.
 */
@Singleton
class OkHttpCalDavClientFactory @Inject constructor() : CalDavClientFactory {

    companion object {
        private const val TAG = "CalDavClientFactory"

        // Shared configuration (same as OkHttpCalDavClient)
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val MAX_IDLE_CONNECTIONS = 5
        private const val KEEP_ALIVE_DURATION_MINUTES = 5L
    }

    /**
     * HTTP logging interceptor - shared across all clients.
     * Note: Set to HEADERS level for debugging, NONE for production.
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
     * Shared connection pool for all clients.
     * Connection pooling is safe to share since HTTP connections are per-host.
     */
    private val sharedConnectionPool by lazy {
        ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION_MINUTES, TimeUnit.MINUTES)
    }

    /**
     * Base OkHttpClient with shared configuration.
     * Each created client builds from this with its own auth interceptor.
     */
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

    override fun createClient(credentials: AccountCredentials, quirks: CalDavQuirks): CalDavClient {
        // Create new OkHttpClient with credentials baked into network interceptor
        // This is thread-safe because OkHttpClient.newBuilder() creates independent copy
        val authenticatedClient = baseHttpClient.newBuilder()
            .addNetworkInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()

                // Add authentication - credentials are captured in this lambda (immutable)
                requestBuilder.header(
                    "Authorization",
                    OkHttpCredentials.basic(credentials.username, credentials.password)
                )

                // Add provider-specific headers
                quirks.getAdditionalHeaders().forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }

                chain.proceed(requestBuilder.build())
            }
            .build()

        Log.d(TAG, "Created isolated client for: ${credentials.username.take(3)}***")

        return OkHttpCalDavClient(quirks, authenticatedClient)
    }
}
