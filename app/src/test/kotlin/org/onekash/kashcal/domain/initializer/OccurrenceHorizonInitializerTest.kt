package org.onekash.kashcal.domain.initializer

import dagger.Lazy
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.coordinator.EventCoordinator

/** The process-start pass asks the coordinator for the horizon from now, building it lazily. */
class OccurrenceHorizonInitializerTest {

    @Test
    fun `run asks for the horizon from now and builds the coordinator only then`(): Unit = runBlocking {
        val coordinator: EventCoordinator = mockk { coJustRun { ensureOccurrenceHorizon(any()) } }
        var built = 0
        val initializer = OccurrenceHorizonInitializer(Lazy { built++; coordinator })
        assertEquals("not built at injection", 0, built)

        initializer.run(nowMs = 1_000L)

        assertEquals(1, built)
        coVerify(exactly = 1) { coordinator.ensureOccurrenceHorizon(1_000L) }
    }
}
