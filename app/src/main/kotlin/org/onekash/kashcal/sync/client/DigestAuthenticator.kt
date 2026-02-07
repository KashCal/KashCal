package org.onekash.kashcal.sync.client

import android.util.Log
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * HTTP Digest authenticator for CalDAV servers (RFC 2617, RFC 7616).
 *
 * Handles Digest challenge-response when server responds with:
 *   401 + WWW-Authenticate: Digest realm="...", nonce="...", qop="auth"
 *
 * Supports:
 * - MD5 algorithm (default, required by all servers)
 * - SHA-256 algorithm (RFC 7616 optional)
 * - qop=auth (quality of protection)
 * - stale=true nonce refresh
 * - Thread-safe via @Synchronized
 * - Infinite loop prevention (checks existing Digest header + stale flag)
 *
 * Used by: CalDavClientFactory.createClient() and OkHttpCalDavClient.createLegacyClient()
 */
class DigestAuthenticator(
    private val username: String,
    private val password: String
) : Authenticator {

    companion object {
        private const val TAG = "DigestAuthenticator"
        private val SECURE_RANDOM = SecureRandom()
    }

    /** Nonce count for qop=auth. RFC 2617 requires incrementing nc per nonce. */
    private var nonceCount = 0  // Guarded by @Synchronized

    /** Current nonce from server. Reset triggers nonceCount reset. */
    private var lastNonce: String? = null  // Guarded by @Synchronized

    /**
     * Handle a 401 challenge from the server.
     *
     * Loop prevention: if the request already has a Digest Authorization header
     * and the server rejected it, only retry if stale=true (nonce expired).
     * Otherwise return null (wrong credentials — stop).
     *
     * @return New request with Digest Authorization header, or null to give up.
     */
    @Synchronized
    override fun authenticate(route: Route?, response: Response): Request? {
        val challenge = parseDigestChallenge(response) ?: return null

        // Loop prevention: if we already sent Digest and server rejected it
        val existingAuth = response.request.header("Authorization")
        if (existingAuth != null && existingAuth.startsWith("Digest", ignoreCase = true)) {
            if (challenge.stale) {
                Log.d(TAG, "Server indicates stale nonce, resetting counter")
                nonceCount = 0  // Nonce expired — reset and retry
            } else {
                Log.w(TAG, "Digest auth failed (not stale) — credentials likely incorrect")
                return null     // Wrong credentials — stop
            }
        }

        // Reset nonce count when server issues a new nonce
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

    /**
     * Parsed Digest challenge from WWW-Authenticate header.
     */
    internal data class DigestChallenge(
        val realm: String,
        val nonce: String,
        val qop: String?,        // Parsed via parseQop() — "auth" or null
        val opaque: String?,     // Echoed back unchanged
        val algorithm: String?,  // Raw value from server: "MD5", "SHA-256", or null
        val stale: Boolean       // true = nonce expired (retry ok), false = bad credentials
    ) {
        /** Hash function name for MessageDigest. Defaults to MD5 when server omits algorithm. */
        val hashAlgorithm: String
            get() = algorithm ?: "MD5"
    }

    /**
     * Parse WWW-Authenticate: Digest header from the response.
     *
     * Handles multiple WWW-Authenticate headers (prefers Digest over Basic).
     * Returns null if no Digest challenge found or required fields missing.
     */
    internal fun parseDigestChallenge(response: Response): DigestChallenge? {
        val authHeaders = response.headers("WWW-Authenticate")
        if (authHeaders.isEmpty()) return null

        // Find the Digest challenge (prefer Digest over Basic)
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
     * Parse comma-separated qop list and select "auth" if available.
     *
     * @param rawQop Raw qop value from challenge (e.g., "auth,auth-int" or "auth")
     * @return "auth" if supported, null for legacy mode (no qop) or unsupported options
     */
    internal fun parseQop(rawQop: String?): String? {
        if (rawQop == null) return null  // Legacy mode
        val options = rawQop.split(",").map { it.trim() }
        return when {
            "auth" in options -> "auth"
            else -> null  // Only auth-int or unknown — unsupported, fall back to legacy
        }
    }

    /**
     * Parse key=value pairs from Digest challenge string.
     *
     * Handles both quoted and unquoted values per RFC 2617.
     * Keys are lowercased for case-insensitive lookup.
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
     * Build the Digest Authorization header value per RFC 2617 Section 3.2.2.
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
            (request.url.encodedQuery?.let { "?$it" } ?: "")
        val cnonce = generateCnonce()
        val nc = String.format("%08x", nonceCount)

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

    /**
     * Compute hash using specified algorithm.
     * Returns lowercase hex string.
     */
    internal fun hash(input: String, algorithm: String): String {
        val javaAlgorithm = when (algorithm.uppercase()) {
            "SHA-256" -> "SHA-256"
            else -> "MD5"
        }
        val digest = MessageDigest.getInstance(javaAlgorithm)
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate a random client nonce.
     * Uses SecureRandom for unpredictability (RFC 2617 recommends unique per request).
     */
    internal fun generateCnonce(): String {
        val bytes = ByteArray(16)
        SECURE_RANDOM.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
