package org.onekash.kashcal.domain.quickadd.normalizer

fun interface Normalizer {
    fun normalize(input: String): String
}

class NormalizerChain(private val lowercase: Boolean = true) : Normalizer {

    private val pipeline: List<Normalizer> = buildList {
        if (lowercase) {
            add(Normalizer { it.lowercase() })
        }
        // Character cleanup: keep letters, digits, whitespace, and allowed punctuation
        add(Normalizer { input -> CHAR_CLEANUP.replace(input, " ") })
        // Whitespace normalization
        add(Normalizer { WHITESPACE.replace(it, " ").trim() })
        // Number words → digits (case-insensitive internally)
        add(NumberWordNormalizer)
        // Multi-word expressions → underscored (case-insensitive internally)
        add(MultiWordNormalizer)
    }

    override fun normalize(input: String): String {
        return pipeline.fold(input) { text, normalizer -> normalizer.normalize(text) }
    }

    companion object {
        // Keep Unicode letters/digits, whitespace, allowed punctuation, and emoji (So = Symbol, other)
        private val CHAR_CLEANUP = Regex("[^\\p{L}\\p{N}\\p{So}\\s/':.\\-]")
        private val WHITESPACE = Regex("\\s+")
    }
}
