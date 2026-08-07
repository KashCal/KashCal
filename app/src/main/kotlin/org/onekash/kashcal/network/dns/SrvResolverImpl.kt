package org.onekash.kashcal.network.dns

/**
 * The default [SrvResolver]: issues a TYPE 33 (SRV) query through [channel], decodes
 * the bytes with [SrvWireParser], and orders any records with [SrvSelection].
 *
 * The three collaborators keep their single responsibilities: [channel] does the
 * (untestable) IO, [SrvWireParser] turns bytes into a typed result, [SrvSelection]
 * imposes RFC 2782 connection order. This class only sequences them and folds a
 * thrown/empty channel response into [SrvResult.Error] — so it is fully unit-tested
 * with a fake channel, no network.
 *
 * @param rng the weighted-selection source handed to [SrvSelection]; injected so
 *   tests can make ordering deterministic. Defaults to [Math.random].
 */
class SrvResolverImpl(
    private val channel: RawDnsChannel,
    private val rng: () -> Double = Math::random,
) : SrvResolver {

    override suspend fun resolve(service: String, proto: String, domain: String): SrvResult {
        val fqdn = "_$service._$proto.$domain"
        val response = try {
            channel.query(fqdn, TYPE_SRV)
        } catch (e: Exception) {
            return SrvResult.Error(e.message ?: e.javaClass.simpleName)
        }

        return when (val parsed = SrvWireParser.parse(response)) {
            is SrvParseResult.Records -> SrvResult.Found(SrvSelection.order(parsed.records, rng))
            SrvParseResult.NotAvailable -> SrvResult.NotAvailable
            SrvParseResult.NoRecords -> SrvResult.NoRecords
            is SrvParseResult.Failed -> SrvResult.Error(parsed.reason)
        }
    }

    private companion object {
        private const val TYPE_SRV = 33
    }
}
