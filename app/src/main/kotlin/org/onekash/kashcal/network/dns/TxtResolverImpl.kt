package org.onekash.kashcal.network.dns

/**
 * Queries TXT (TYPE 16) through [channel], decodes it with [TxtRecordParser] and extracts the
 * RFC 6763 §6.4 `path` key.
 *
 * A present but empty `path=` is still a [TxtResult.Path] with an empty value: RFC 6764 §4
 * keys on the key's presence. A bare `path` with no '=' yields [TxtResult.NoPath], following
 * [TxtRecordParser.pathValue]. A channel exception, or bytes the parser rejects (including an
 * empty response), yields [TxtResult.Error].
 */
class TxtResolverImpl(
    private val channel: RawDnsChannel,
) : TxtResolver {

    override suspend fun resolvePath(service: String, proto: String, domain: String): TxtResult {
        val fqdn = "_$service._$proto.$domain"
        val response = try {
            channel.query(fqdn, TYPE_TXT)
        } catch (e: Exception) {
            return TxtResult.Error(e.message ?: e.javaClass.simpleName)
        }

        return when (val parsed = TxtRecordParser.parse(response)) {
            is TxtParseResult.Records ->
                TxtRecordParser.pathValue(parsed.strings)?.let { TxtResult.Path(it) } ?: TxtResult.NoPath
            TxtParseResult.NoRecords -> TxtResult.NoPath
            is TxtParseResult.Failed -> TxtResult.Error(parsed.reason)
        }
    }

    private companion object {
        private const val TYPE_TXT = 16
    }
}
