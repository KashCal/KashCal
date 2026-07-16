package org.onekash.kashcal.sync.client

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Fuzz oracle for Digest challenge parsing — the hand-rolled tokenizer over the
 * attacker/server-controlled `WWW-Authenticate: Digest ...` header.
 *
 * `parseAuthParams` is a bespoke regex tokenizer and `parseDigestChallenge`
 * builds a DigestChallenge from its output; both run on bytes KashCal does not
 * control (any CalDAV server, or a MITM on a misconfigured endpoint). The
 * contract is simply: **never throw** for any header value. A malformed or
 * adversarial challenge must degrade to null or a best-effort parse, never crash
 * the auth path.
 *
 * Two surfaces are fuzzed:
 *  - `parseAuthParams(String)` directly, with raw adversarial bytes that OkHttp's
 *    header validation would otherwise reject before they reach a Response.
 *  - `parseDigestChallenge(Response)` end-to-end, with header-safe random values.
 *
 * Seed + iterations are fixed constants, overridable via -Dfuzz.digest.seed= and
 * -Dfuzz.digest.iterations= for longer runs. A failure prints the exact input.
 */
class DigestChallengeFuzzTest {

    private lateinit var authenticator: DigestAuthenticator

    private val seed: Long =
        System.getProperty("fuzz.digest.seed")?.toLongOrNull() ?: DEFAULT_SEED
    private val iterations: Int =
        System.getProperty("fuzz.digest.iterations")?.toIntOrNull() ?: DEFAULT_ITERATIONS

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        authenticator = DigestAuthenticator("testuser", "testpass")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `parseAuthParams never throws on adversarial header bytes`() {
        val random = Random(seed)
        for (i in 0 until iterations) {
            val header = randomParamString(random)
            try {
                authenticator.parseAuthParams(header)
            } catch (t: Throwable) {
                fail("parseAuthParams threw ${t::class.java.simpleName} on input " +
                    "(seed=$seed, i=$i): '${header.take(200)}'\n$t")
            }
        }
        println("Digest parseAuthParams fuzz: ran $iterations cases (seed=$seed), no throw.")
    }

    @Test
    fun `parseDigestChallenge never throws on header-safe random challenges`() {
        val random = Random(seed)
        var built = 0
        for (i in 0 until iterations) {
            // OkHttp validates header values (rejects control chars / newlines) at
            // addHeader, so restrict to a header-safe alphabet here; the raw-byte
            // adversarial cases are covered by the parseAuthParams test above.
            val value = randomHeaderSafeChallenge(random)
            val response = try {
                buildResponse(value)
            } catch (_: IllegalArgumentException) {
                continue // value rejected by OkHttp header validation — not our target
            }
            built++
            try {
                authenticator.parseDigestChallenge(response)
            } catch (t: Throwable) {
                fail("parseDigestChallenge threw ${t::class.java.simpleName} on WWW-Authenticate " +
                    "(seed=$seed, i=$i): '${value.take(200)}'\n$t")
            }
        }
        println("Digest parseDigestChallenge fuzz: built $built/$iterations responses (seed=$seed), no throw.")
    }

    private fun buildResponse(wwwAuthenticate: String): Response {
        val request = Request.Builder()
            .url("http://example.com/dav/")
            .method("PROPFIND", null)
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .addHeader("WWW-Authenticate", wwwAuthenticate)
            .build()
    }

    /** Raw adversarial param string for parseAuthParams (may contain anything). */
    private fun randomParamString(random: Random): String {
        // Mix of the digest alphabet and hostile bytes: quotes, backslashes,
        // equals, commas, control chars, unicode, long runs.
        val alphabet = "realm=nonce\"qop,auth opaque=stale true algorithm MD5 SHA-256 " +
            "\\\"'; \t\r\n=,()<>@\u0000\u007F\uFFFF日本"
        val len = random.nextInt(0, 300)
        return buildString {
            // Optionally prefix a leading Digest scheme, as parseDigestChallenge strips.
            if (random.nextBoolean()) append("Digest ")
            repeat(len) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    /** A header-safe (no control char / newline) random Digest challenge value. */
    private fun randomHeaderSafeChallenge(random: Random): String {
        val alphabet = "realmnoncqopauthMD5SHA-256opaquestaletru= \",.;/-" // header-safe subset
        val len = random.nextInt(0, 250)
        return buildString {
            append("Digest ")
            repeat(len) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private companion object {
        const val DEFAULT_SEED = 20260715L
        const val DEFAULT_ITERATIONS = 5000
    }
}
