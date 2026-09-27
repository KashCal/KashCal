package org.onekash.kashcal.sync.client

import android.util.Log
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Answers HTTP Digest challenges (RFC 2617, RFC 7616) for CalDAV and CardDAV clients.
 *
 * Handles a `401` with `WWW-Authenticate: Digest realm="...", nonce="...", qop="auth"`:
 * - MD5, the default when the server names no algorithm, and SHA-256 (RFC 7616)
 * - qop=auth, or the legacy form without qop
 * - stale=true nonce refresh
 * - no endless retry: a rejected Digest header is retried only when the nonce is stale
 *
 * Calls are `@Synchronized`, so one instance is safe across concurrent requests. Installed by
 * [CalDavClientFactory], `CardDavClientFactory` and OkHttpCalDavClient's legacy client.
 */
class DigestAuthenticator(
    private val username: String,
    private val password: String,
    /**
     * Whether a challenge on a plain http request may be answered: true only when the account
     * was set up with an http:// address. A Digest response is derived from the password, so
     * it is never sent over cleartext for an https account.
     */
    private val allowCleartext: Boolean
) : Authenticator {

    companion object {
        private const val TAG = "DigestAuthenticator"
        private val SECURE_RANDOM = SecureRandom()
    }

    /** Nonce count for qop=auth; RFC 2617 requires nc to increase per nonce. */
    private var nonceCount = 0  // Guarded by @Synchronized

    /** The server's current nonce; a new one resets [nonceCount]. */
    private var lastNonce: String? = null  // Guarded by @Synchronized

    /**
     * Answers a 401 Digest challenge.
     *
     * When the rejected request already carried a Digest header, retries only if the nonce
     * is stale; otherwise the credentials are wrong and it gives up. Also gives up on plain
     * http unless [allowCleartext].
     *
     * @return the request with a Digest Authorization header, or null to give up
     */
    @Synchronized
    override fun authenticate(route: Route?, response: Response): Request? {
        if (!response.request.url.isHttps && !allowCleartext) {
            Log.w(TAG, "Not answering a Digest challenge over plain http")
            return null
        }
        val challenge = parseDigestChallenge(response) ?: return null

        // A Digest header was already sent and rejected.
        val existingAuth = response.request.header("Authorization")
        if (existingAuth != null && existingAuth.startsWith("Digest", ignoreCase = true)) {
            if (challenge.stale) {
                Log.d(TAG, "Server indicates stale nonce, resetting counter")
                nonceCount = 0  // Nonce expired: reset and retry
            } else {
                Log.w(TAG, "Digest auth failed (not stale) — credentials likely incorrect")
                return null     // Wrong credentials: stop
            }
        }

        if (challenge.nonce != lastNonce) {
            nonceCount = 0
            lastNonce = challenge.nonce
        }
        nonceCount++

        Log.d(TAG, "Responding to Digest challenge: realm=${challenge.realm}, " +
            "algorithm=${challenge.hashAlgorithm}, qop=${challenge.qop}")

        val authHeader = buildDigestHeader(response.request, challenge)
        return response.request.newBuilder()
            .header("Authorization", authHeader)
            .build()
    }

    /** A parsed `WWW-Authenticate: Digest` challenge. */
    internal data class DigestChallenge(
        val realm: String,
        val nonce: String,
        val qop: String?,        // From parseQop(): "auth" or null
        val opaque: String?,     // Echoed back unchanged
        val algorithm: String?,  // As the server sent it, e.g. "MD5", "SHA-256", or null
        val stale: Boolean       // true = nonce expired (retry ok), false = bad credentials
    ) {
        /** The challenge's algorithm, or MD5 when the server names none. */
        val hashAlgorithm: String
            get() = algorithm ?: "MD5"
    }

    /**
     * Parses the Digest challenge among the response's `WWW-Authenticate` headers, ignoring
     * Basic ones.
     *
     * @return null when there is no Digest challenge or it lacks realm or nonce
     */
    internal fun parseDigestChallenge(response: Response): DigestChallenge? {
        val authHeaders = response.headers("WWW-Authenticate")
        if (authHeaders.isEmpty()) return null

        val digestHeader = authHeaders
            .firstOrNull { it.trimStart().startsWith("Digest ", ignoreCase = true) }
            ?: return null

        val params = parseAuthParams(digestHeader.substringAfter(" ").trim())

        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null

        return DigestChallenge(
            realm = realm,
            nonce = nonce,
            qop = parseQop(params["qop"]),
            opaque = params["opaque"],
            algorithm = params["algorithm"],
            stale = params["stale"]?.equals("true", ignoreCase = true) ?: false
        )
    }

    /**
     * Picks "auth" from a comma-separated qop list such as "auth,auth-int".
     *
     * @return "auth" when offered; null for no qop or only unsupported options (legacy mode)
     */
    internal fun parseQop(rawQop: String?): String? {
        if (rawQop == null) return null  // Legacy mode
        val options = rawQop.split(",").map { it.trim() }
        return when {
            "auth" in options -> "auth"
            else -> null  // Only auth-int or unknown: unsupported, fall back to legacy
        }
    }

    /**
     * Parses the key=value pairs of a Digest challenge, quoted or unquoted (RFC 2617). Keys
     * are lowercased for case-insensitive lookup.
     */
    internal fun parseAuthParams(header: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        // Regex: key = "quoted-value" or key = unquoted-value
        val regex = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([\w./-]+))""")
        for (match in regex.findAll(header)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            params[key] = value
        }
        return params
    }

    /**
     * Builds the Digest Authorization header value (RFC 2617 §3.2.2).
     *
     * When qop=auth:
     *   HA1 = H(username:realm:password)
     *   HA2 = H(method:uri)
     *   response = H(HA1:nonce:nc:cnonce:qop:HA2)
     *
     * When qop is absent (legacy):
     *   response = H(HA1:nonce:HA2)
     */
    internal fun buildDigestHeader(request: Request, challenge: DigestChallenge): String {
        val digestUri = request.url.encodedPath +
            (request.url.encodedQuery?.let { "?$it" }.orEmpty())
        val cnonce = generateCnonce()
        val nc = String.format(java.util.Locale.ROOT, "%08x", nonceCount)

        val ha1 = hash("$username:${challenge.realm}:$password", challenge.hashAlgorithm)
        val ha2 = hash("${request.method}:$digestUri", challenge.hashAlgorithm)

        val responseHash = if (challenge.qop != null) {
            hash("$ha1:${challenge.nonce}:$nc:$cnonce:${challenge.qop}:$ha2", challenge.hashAlgorithm)
        } else {
            hash("$ha1:${challenge.nonce}:$ha2", challenge.hashAlgorithm)
        }

        return buildString {
            append("Digest username=\"$username\"")
            append(", realm=\"${challenge.realm}\"")
            append(", nonce=\"${challenge.nonce}\"")
            append(", uri=\"$digestUri\"")
            append(", response=\"$responseHash\"")
            challenge.opaque?.let { append(", opaque=\"$it\"") }
            if (challenge.qop != null) {
                append(", qop=${challenge.qop}")
                append(", nc=$nc")
                append(", cnonce=\"$cnonce\"")
            }
            challenge.algorithm?.let { append(", algorithm=$it") }
        }
    }

    /** Hashes [input] as lowercase hex: SHA-256 when [algorithm] names it, else MD5. */
    internal fun hash(input: String, algorithm: String): String {
        val javaAlgorithm = when (algorithm.uppercase()) {
            "SHA-256" -> "SHA-256"
            else -> "MD5"
        }
        val digest = MessageDigest.getInstance(javaAlgorithm)
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Generates an unpredictable client nonce (RFC 2617: unique per request). */
    internal fun generateCnonce(): String {
        val bytes = ByteArray(16)
        SECURE_RANDOM.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
