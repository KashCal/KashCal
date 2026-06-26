package org.onekash.kashcal.domain.generator.parity

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serializes per-engine corpus output to the baseline JSON format (one file
 * per engine). Format chosen for readable `git diff`s — engine version bumps
 * show as per-case hunks.
 *
 * Schema:
 *   {
 *     "engine": "lib-recur",
 *     "cases": [
 *       { "name": "…", "timestamps": [1704067200000, …], "error": null },
 *       { "name": "…", "timestamps": [], "error": "TimeoutException: …" }
 *     ]
 *   }
 *
 * Cases are emitted in corpus-declaration order so git diff hunks are stable.
 */
object ParityBaselineCodec {

    @Serializable
    data class BaselineCase(
        val name: String,
        val timestamps: List<Long>,
        val error: String? = null,
    )

    @Serializable
    data class Baseline(
        val engine: String,
        val cases: List<BaselineCase>,
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    fun encode(engineName: String, entries: List<Pair<RRuleCase, ExpansionResult>>): String {
        val baseline = Baseline(
            engine = engineName,
            cases = entries.map { (case, result) -> toBaselineCase(case, result) },
        )
        return json.encodeToString(Baseline.serializer(), baseline) + "\n"
    }

    fun decode(text: String): Baseline = json.decodeFromString(Baseline.serializer(), text)

    private fun toBaselineCase(case: RRuleCase, result: ExpansionResult): BaselineCase =
        when (result) {
            is ExpansionResult.Success -> BaselineCase(
                name = case.name,
                timestamps = result.timestampsMs.sorted(),
                error = null,
            )
            is ExpansionResult.Error -> BaselineCase(
                name = case.name,
                timestamps = emptyList(),
                error = "${result.throwableClass}: ${result.message}",
            )
        }
}
