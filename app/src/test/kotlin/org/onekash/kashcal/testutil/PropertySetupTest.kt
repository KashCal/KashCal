package org.onekash.kashcal.testutil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs Kotest's checkAll inside Robolectric's sandbox with the shared seed settings. */
@RunWith(RobolectricTestRunner::class)
class PropertySetupTest {

    @Test
    fun `checkAll runs every iteration under Robolectric`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = PropertyRuns.config(iterations = 25)
        var runs = 0
        checkAll(config, Arb.int()) {
            assertNotNull(context.packageName)
            runs++
        }
        assertEquals(config.iterations, runs)
    }

    @Test
    fun `a failing property reports its seed`() {
        val config = PropertyRuns.config(iterations = 25)
        val failure = assertThrows(AssertionError::class.java) {
            runBlocking { checkAll(config, Arb.int(0..9)) { n -> assertTrue(n < 0) } }
        }
        val message = failure.message.orEmpty()
        assertTrue(message, message.contains("seed ${config.seed}"))
    }

    @Test
    fun `the seed property replaces the default seed`() {
        val previous = System.getProperty(PropertyRuns.SEED_PROPERTY)
        try {
            System.clearProperty(PropertyRuns.SEED_PROPERTY)
            assertEquals(PropertyRuns.DEFAULT_SEED, PropertyRuns.seed())
            System.setProperty(PropertyRuns.SEED_PROPERTY, "12345")
            assertEquals(12345L, PropertyRuns.seed())
            assertEquals(12345L, PropertyRuns.config(iterations = 1).seed)
        } finally {
            if (previous == null) System.clearProperty(PropertyRuns.SEED_PROPERTY)
            else System.setProperty(PropertyRuns.SEED_PROPERTY, previous)
        }
    }

    @Test
    fun `the iterations property multiplies each property's iterations`() {
        val previous = System.getProperty(PropertyRuns.ITERATIONS_PROPERTY)
        try {
            System.setProperty(PropertyRuns.ITERATIONS_PROPERTY, "3")
            assertEquals(30, PropertyRuns.config(iterations = 10).iterations)
        } finally {
            if (previous == null) System.clearProperty(PropertyRuns.ITERATIONS_PROPERTY)
            else System.setProperty(PropertyRuns.ITERATIONS_PROPERTY, previous)
        }
    }
}
