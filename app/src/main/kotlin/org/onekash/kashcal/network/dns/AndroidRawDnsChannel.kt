package org.onekash.kashcal.network.dns

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The production [RawDnsChannel]: issues one raw DNS query through
 * `android.net.DnsResolver.rawQuery` and returns the response message bytes.
 *
 * This is the single genuinely platform-dependent seam of DNS discovery. Every
 * decision above it — [SrvWireParser]/[TxtRecordParser] decode, RFC 2782
 * selection, the RFC 6764 §6 fallback ladder — is pure and unit-tested against a
 * fake [RawDnsChannel]; this adapter is the only class touching the framework and
 * is verified only by integration on a real device + network.
 *
 * **Why `rawQuery` and not a bundled DNS library:** it routes through the *system*
 * resolver, so it honors Private DNS/DoT, VPN, per-network DNS, and split-tunnel.
 * A library opening its own `:53` socket bypasses all of that — leaking queries
 * and breaking on networks that block direct DNS. `rawQuery` is a public framework
 * API since API 29; KashCal's `minSdk` is 31, so it is present on the entire
 * install base, including de-Googled AOSP variants.
 *
 * The resolver builds the query wire-format itself from ([fqdn], class IN, [nsType]),
 * so this adapter carries no DNS-encoding logic — it only bridges the framework's
 * callback to a coroutine and forwards the response bytes verbatim to the parser.
 */
class AndroidRawDnsChannel(
    private val resolver: DnsResolver = DnsResolver.getInstance(),
    /** The network to query on; `null` uses the process's default active network. */
    private val network: Network? = null,
    /**
     * Where the framework posts its callback. The default runs it inline on the
     * resolver's own delivery thread — the callback body only resumes a
     * continuation, so no dispatcher hop is warranted.
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
                    // isActive guards against resuming an already-cancelled/completed
                    // continuation, which would throw IllegalStateException.
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
