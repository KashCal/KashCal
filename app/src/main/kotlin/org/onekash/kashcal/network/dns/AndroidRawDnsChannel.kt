package org.onekash.kashcal.network.dns

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Issues one raw DNS query through `android.net.DnsResolver.rawQuery` and returns the
 * response message bytes; the production [RawDnsChannel].
 *
 * This is the only class in DNS discovery that touches the framework, so it is verified only
 * on a real device and network. Everything above it ([SrvWireParser] and [TxtRecordParser]
 * decoding, RFC 2782 selection, the RFC 6764 §6 fallback ladder) is unit-tested against a fake
 * channel.
 *
 * `rawQuery` goes through the system resolver, so it honors Private DNS (DoT), VPN,
 * per-network DNS and split tunnels. A bundled DNS library opening its own port 53 socket
 * bypasses all of that, leaking queries and failing on networks that block direct DNS.
 * `rawQuery` is public since API 29 and `minSdk` is 31, so every install has it, including
 * de-Googled AOSP builds.
 *
 * The resolver encodes the query from the name, class IN and type itself; this adapter only
 * bridges the framework callback to a coroutine and passes the response bytes on unchanged.
 */
class AndroidRawDnsChannel(
    private val resolver: DnsResolver = DnsResolver.getInstance(),
    /** The network to query on; `null` uses the process's default active network. */
    private val network: Network? = null,
    /**
     * Where the framework posts its callback. The default runs it inline on the resolver's
     * delivery thread; the callback only resumes a continuation, so no dispatcher hop is needed.
     */
    private val executor: Executor = Executor { it.run() },
) : RawDnsChannel {

    override suspend fun query(fqdn: String, nsType: Int): ByteArray =
        suspendCancellableCoroutine { cont ->
            // A cancelled coroutine must abort the in-flight lookup, not leak it.
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }

            resolver.rawQuery(
                network,
                fqdn,
                DnsResolver.CLASS_IN,
                nsType,
                DnsResolver.FLAG_EMPTY,
                executor,
                signal,
                object : DnsResolver.Callback<ByteArray> {
                    // Resuming a cancelled or completed continuation would throw
                    // IllegalStateException.
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (cont.isActive) cont.resume(answer)
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        if (cont.isActive) cont.resumeWithException(error)
                    }
                },
            )
        }
}
