package org.onekash.kashcal.testutil

import io.kotest.common.ExperimentalKotest
import io.kotest.property.PropTestConfig
import io.kotest.property.PropertyTesting

/**
 * Runs every property with one fixed seed by default, so a new failure can't appear in an
 * unrelated commit. `-Pkashcal.propertySeed=<seed>` replays a seed a failure printed and
 * `-Pkashcal.propertySeed=random` draws a new one; `-Pkashcal.propertyIterations=<n>` multiplies
 * each property's iterations for a longer run. A failure prints its seed and its input, shrunk
 * where the input's generator shrinks; no failed seed is written under the home folder.
 */
object PropertyRuns {

    /** System property set by `-Pkashcal.propertySeed`. */
    const val SEED_PROPERTY = "kashcal.propertySeed"

    /** System property set by `-Pkashcal.propertyIterations`. */
    const val ITERATIONS_PROPERTY = "kashcal.propertyIterations"

    /** The seed of the default suite. */
    const val DEFAULT_SEED = 20_261_109L

    init {
        PropertyTesting.writeFailedSeed = false
    }

    /**
     * Returns the run's seed: [DEFAULT_SEED], the one requested, or a new random one, which is
     * printed to the test's standard output so a passing random run can be replayed too.
     */
    fun seed(): Long = when (val asked = System.getProperty(SEED_PROPERTY)?.takeIf { it.isNotBlank() }) {
        null -> DEFAULT_SEED
        "random" -> kotlin.random.Random.nextLong().also { println("PropertyRuns: random seed $it") }
        else -> asked.toLong()
    }

    /** Returns the run settings: [seed], and [iterations] times the requested multiplier. */
    @OptIn(ExperimentalKotest::class) // PropTestConfig's constructor is experimental in Kotest 6.
    fun config(iterations: Int): PropTestConfig {
        val times = System.getProperty(ITERATIONS_PROPERTY)?.takeIf { it.isNotBlank() }?.toInt() ?: 1
        return PropTestConfig(seed = seed(), iterations = iterations * times)
    }
}
