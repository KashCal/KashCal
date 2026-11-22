package org.onekash.kashcal.domain.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeoutException

/** Runs real `sh` processes on the real IO dispatcher, as the report does with logcat. */
class ProcessLineReaderTest {

    @Test
    fun `streams every stdout line`() = runBlocking(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        ProcessLineReader(timeoutMs = 10_000).read(listOf("sh", "-c", "printf 'a\\nb\\nc\\n'"), 100) { lines += it }
        assertEquals(listOf("a", "b", "c"), lines)
    }

    @Test
    fun `stops at the line cap`() = runBlocking(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        ProcessLineReader(timeoutMs = 10_000)
            .read(listOf("sh", "-c", "for i in 1 2 3 4 5; do echo \$i; done"), 2) { lines += it }
        assertEquals(listOf("1", "2"), lines)
    }

    @Test
    fun `destroys a process that outlives the timeout`() = runBlocking(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        val start = System.nanoTime()
        try {
            ProcessLineReader(timeoutMs = 500).read(listOf("sh", "-c", "echo a; exec sleep 30"), 100) { lines += it }
            fail("expected a timeout")
        } catch (e: TimeoutException) {
            // expected
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(listOf("a"), lines)
        assertTrue("took $ms ms", ms < 5_000)
    }

    // Without the destroy, the read would wait out the 30 s sleep. The 5 s budget leaves room
    // for process teardown on a loaded host.
    @Test
    fun `cancelling destroys the process well before it would exit`() = runBlocking(Dispatchers.IO) {
        val firstLine = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            ProcessLineReader(timeoutMs = 60_000)
                .read(listOf("sh", "-c", "echo a; exec sleep 30"), 100) { firstLine.complete(Unit) }
        }
        withTimeout(10_000) { firstLine.await() }
        val start = System.nanoTime()
        job.cancel()
        withTimeout(5_000) { job.join() }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test(expected = IOException::class)
    fun `missing executable throws`() = runBlocking(Dispatchers.IO) {
        ProcessLineReader(timeoutMs = 1_000).read(listOf("kashcal-no-such-command"), 10) { }
    }
}
