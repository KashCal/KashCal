package org.onekash.kashcal.network.dns

/**
 * The default [TxtResolver]: issues a TYPE 16 (TXT) query through [channel], decodes
 * the bytes with [TxtRecordParser], and extracts the RFC 6763 §6.4 `path` key.
 *
 * A present-but-empty `path=` is still a [TxtResult.Path] (empty value) — the key's
 * *presence* is what §4 keys on, and the parser's `pathValue` already returns "" vs
 * null correctly. A bare boolean `path` token (no '=') carries no value and yields
 * [TxtResult.NoPath], matching `pathValue`'s null. A thrown/empty channel or a
 * parser Failure folds to [TxtResult.Error].
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
