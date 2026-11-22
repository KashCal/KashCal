package org.onekash.kashcal.domain.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Reads a child process's output line by line with a line cap and a timeout. */
class ProcessLineReader(private val timeoutMs: Long) {

    @Inject constructor() : this(DEFAULT_TIMEOUT_MS)

    /**
     * Runs [command] and passes each line of its output (stderr merged in) to [onLine] until the
     * output ends or [maxLines] lines are read. Call it on a thread that may block.
     *
     * Throws [IOException] when the command can't start and [TimeoutException] when it
     * runs past the timeout. Cancelling the caller destroys the process.
     */
    suspend fun read(command: List<String>, maxLines: Int, onLine: (String) -> Unit): Unit = coroutineScope {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        val timedOut = AtomicBoolean(false)
        // A blocked readLine ignores cancellation, so a child on the IO pool destroys the process
        // at the timeout or when the caller is cancelled; either closes the stream and the read
        // returns.
        val watchdog = launch(Dispatchers.IO) {
            try {
                delay(timeoutMs)
                timedOut.set(true)
            } finally {
                process.destroyForcibly()
            }
        }
        try {
            process.inputStream.bufferedReader().use { reader ->
                var count = 0
                while (count < maxLines) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                    count++
                }
            }
        } catch (e: IOException) {
            // A stream the watchdog closed is reported below as a cancellation or a timeout.
            if (!timedOut.get()) {
                ensureActive()
                throw e
            }
        } finally {
            watchdog.cancel()
            process.destroyForcibly()
        }
        ensureActive()
        if (timedOut.get()) throw TimeoutException("${command.first()} ran past $timeoutMs ms")
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
