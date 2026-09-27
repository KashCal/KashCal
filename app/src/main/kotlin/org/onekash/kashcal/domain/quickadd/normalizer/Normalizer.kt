package org.onekash.kashcal.domain.quickadd.normalizer

fun interface Normalizer {
    fun normalize(input: String): String
}

class NormalizerChain(private val lowercase: Boolean = true) : Normalizer {

    private val pipeline: List<Normalizer> = buildList {
        if (lowercase) {
            add(Normalizer { it.lowercase() })
        }
        // Replaces every character outside CHAR_CLEANUP's keep set with a space.
        add(Normalizer { input -> CHAR_CLEANUP.replace(input, " ") })
        // Collapses whitespace runs and trims.
        add(Normalizer { WHITESPACE.replace(it, " ").trim() })
        // Must precede NumberWordNormalizer, which maps "a" and "an" to 1.
        add(FuzzyQuantifierNormalizer)
        // Number words to digits, ignoring case.
        add(NumberWordNormalizer)
        // Multi-word expressions to one underscored word, ignoring case.
        add(MultiWordNormalizer)
    }

    override fun normalize(input: String): String {
        return pipeline.fold(input) { text, normalizer -> normalizer.normalize(text) }
    }

    companion object {
        // Keeps Unicode letters, digits, emoji (So, other symbol), whitespace and / ' : . -
        private val CHAR_CLEANUP = Regex("[^\\p{L}\\p{N}\\p{So}\\s/':.\\-]")
        private val WHITESPACE = Regex("\\s+")
    }
}
