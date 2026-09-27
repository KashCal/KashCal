package org.onekash.kashcal.network.dns

/**
 * Issues a TYPE 33 (SRV) query through [channel], decodes it with [SrvWireParser] and orders
 * any records in RFC 2782 order with [SrvSelection]; the default [SrvResolver].
 *
 * This class only sequences the three and turns a throwing channel into [SrvResult.Error]
 * (an empty body fails in the parser), so it is unit-tested with a fake channel.
 *
 * @param rng the weighted-selection source handed to [SrvSelection]; injected so tests can
 *   make ordering deterministic. Defaults to [Math.random].
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
