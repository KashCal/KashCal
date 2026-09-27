package org.onekash.kashcal.network

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import org.onekash.kashcal.util.maskHost
import java.io.IOException

/** Why the transport guard stopped a request instead of sending it. */
enum class DavTransportRefusal {
    /** An https request was redirected to plain http on another host or port. */
    INSECURE_REDIRECT,

    /** A plain http request on an account set up with https. */
    CLEARTEXT_REFUSED,

    /** More than [DavRedirectPolicy.MAX_REDIRECTS] redirects in a row (a loop). */
    TOO_MANY_REDIRECTS,

    /** A redirect of a request whose body can only be written once. */
    UNREPLAYABLE_BODY,
}

/**
 * The guard refused to send a request. Carries masked hosts only, never a path,
 * query or credentials, so the message is safe for logs and the sync history.
 */
class DavTransportRefusedException(
    val reason: DavTransportRefusal,
    fromHost: String,
    toHost: String?,
) : IOException(
    "Connection refused by KashCal ($reason): ${fromHost.maskHost()}" +
        (toHost?.let { " -> ${it.maskHost()}" } ?: "")
)

/**
 * Where a DAV request goes after a redirect, decided without touching the network.
 *
 * - 301, 302, 307 and 308 are followed for every method with the same method and
 *   body (a WebDAV method has no GET equivalent, so turning a PUT or DELETE into a
 *   GET would silently drop the write).
 * - 303 is followed only for GET, HEAD and PROPFIND: RFC 6764 §5 names 303 for
 *   the well-known URI, while for a write a 303 means "done, look elsewhere".
 * - https to http: re-sent as https when the target is the same host on port 80 or
 *   on the port that answered over https (a TLS-terminating proxy that writes http
 *   into its Location header). Any other downgrade is refused.
 * - A plain http target is refused unless the account itself was set up with http.
 */
object DavRedirectPolicy {
    const val MAX_REDIRECTS = 5

    private val ALWAYS_FOLLOWED = setOf(301, 302, 307, 308)
    private val SEE_OTHER_METHODS = setOf("GET", "HEAD", "PROPFIND")

    sealed interface Decision {
        /** Not a redirect to follow: hand the response to the caller as it is. */
        data object Stop : Decision
        data class Follow(val url: HttpUrl) : Decision
        data class Refuse(val reason: DavTransportRefusal, val target: HttpUrl) : Decision
    }

    fun next(from: HttpUrl, code: Int, location: String?, method: String, allowCleartext: Boolean): Decision {
        val followed = code in ALWAYS_FOLLOWED || (code == 303 && method.uppercase() in SEE_OTHER_METHODS)
        if (!followed || location.isNullOrBlank()) return Decision.Stop
        val target = from.resolve(location) ?: return Decision.Stop

        val next = if (from.isHttps && !target.isHttps) {
            val sameHost = target.host.equals(from.host, ignoreCase = true)
            if (sameHost && (target.port == 80 || target.port == from.port)) {
                target.newBuilder().scheme("https").port(from.port).build()
            } else {
                return Decision.Refuse(DavTransportRefusal.INSECURE_REDIRECT, target)
            }
        } else {
            target
        }
        if (!next.isHttps && !allowCleartext) return Decision.Refuse(DavTransportRefusal.CLEARTEXT_REFUSED, next)
        return Decision.Follow(next)
    }
}

/**
 * Follows redirects for DAV requests itself, as an OkHttp application interceptor.
 *
 * OkHttp's own follow-up turns a redirected PUT, DELETE, REPORT or MOVE into a GET,
 * which a server usually answers 200: the write never happens yet reads as a
 * success. This re-sends the same method, body and headers (If-Match, Depth,
 * Destination, ...) to the new location, up to [DavRedirectPolicy.MAX_REDIRECTS]
 * times, and never sends anything over plain http unless the account was set up
 * with an http:// address ([allowCleartext]).
 *
 * The returned response's `request.url` is where the request ended up, and its
 * `priorResponse` chain holds each redirect (bodies dropped), like OkHttp's own.
 * Install it with [installDavTransport], which also turns OkHttp's redirects off.
 */
class DavTransportGuard(private val allowCleartext: Boolean) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (!request.url.isHttps && !allowCleartext) {
            throw refuse(DavTransportRefusal.CLEARTEXT_REFUSED, request.url, null)
        }

        var response = chain.proceed(request)
        var prior: Response? = null
        var redirects = 0
        while (true) {
            val decision = DavRedirectPolicy.next(
                from = request.url,
                code = response.code,
                location = response.header("Location"),
                method = request.method,
                allowCleartext = allowCleartext
            )
            val target = when (decision) {
                DavRedirectPolicy.Decision.Stop ->
                    return if (prior == null) response else response.newBuilder().priorResponse(prior).build()
                is DavRedirectPolicy.Decision.Refuse -> {
                    response.close()
                    throw refuse(decision.reason, request.url, decision.target)
                }
                is DavRedirectPolicy.Decision.Follow -> decision.url
            }
            if (redirects == DavRedirectPolicy.MAX_REDIRECTS) {
                response.close()
                throw refuse(DavTransportRefusal.TOO_MANY_REDIRECTS, request.url, target)
            }
            if (request.body?.isOneShot() == true) {
                response.close()
                throw refuse(DavTransportRefusal.UNREPLAYABLE_BODY, request.url, target)
            }

            prior = response.newBuilder().body(ResponseBody.EMPTY).priorResponse(prior).build()
            response.close()
            redirects++
            request = request.newBuilder().url(target).build()
            response = chain.proceed(request)
        }
    }

    private fun refuse(reason: DavTransportRefusal, from: HttpUrl, to: HttpUrl?): DavTransportRefusedException {
        val e = DavTransportRefusedException(reason, from.host, to?.host)
        Log.w(TAG, e.message.orEmpty())
        return e
    }

    companion object {
        private const val TAG = "DavTransportGuard"

        /** Returns whether an account whose [accountUrl] starts with http:// may use plain http. */
        fun allowsCleartext(accountUrl: String?): Boolean =
            accountUrl?.trimStart()?.startsWith("http://", ignoreCase = true) == true

        /** Returns whether the account's credentials may go on a request to [url]. */
        fun mayAttachCredentials(url: HttpUrl, allowCleartext: Boolean): Boolean =
            url.isHttps || allowCleartext
    }
}

/**
 * Turns OkHttp's own redirect handling off and follows redirects with [DavTransportGuard].
 * Call it on the builder that carries the account's auth.
 */
fun OkHttpClient.Builder.installDavTransport(allowCleartext: Boolean): OkHttpClient.Builder =
    followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(DavTransportGuard(allowCleartext))

/**
 * Returns a copy of this client that follows no redirects, neither through
 * [DavTransportGuard] nor through OkHttp's own (for a contact photo, whose redirect may
 * point at a foreign host).
 */
fun OkHttpClient.withoutDavTransportGuard(): OkHttpClient =
    newBuilder()
        .apply { interceptors().removeAll { it is DavTransportGuard } }
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

/**
 * Returns the DAV endpoint a well-known discovery ended at (RFC 6764 §5), cleaned for
 * storage: query and fragment dropped, the path kept as is (it is the service's
 * context path; trailing slashes matter to some servers).
 *
 * The scheme never goes down: an https result stays https even if the user typed
 * http (the server upgraded), and an http result is lifted back to https when the
 * user started on https (a TLS-terminating proxy that writes http:// into its
 * redirects). So a stored https address is never rewritten to plain http.
 */
fun wellKnownEndpoint(finalUrl: String, originalScheme: String?): String {
    val cleanUrl = finalUrl.substringBefore("?").substringBefore("#")
    val scheme = cleanUrl.substringBefore("://", missingDelimiterValue = "")
    if (scheme.isEmpty() || !scheme.equals("http", ignoreCase = true)) return cleanUrl
    if (!originalScheme.equals("https", ignoreCase = true)) return cleanUrl
    return "https://" + cleanUrl.substringAfter("://")
}
