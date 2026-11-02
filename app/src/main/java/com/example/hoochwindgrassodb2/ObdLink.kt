package com.example.hoochwindgrassodb2

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Command/response link to an ELM327 adapter. Commands are serialised, never block
 * forever, and can be cancelled. [logger] receives every TX/RX line for the live feed.
 */
class ObdLink(private val logger: (String, String) -> Unit = { _, _ -> }) {
    class TimeoutException(message: String) : IOException(message)
    class AbortedException : IOException("Cancelled")

    private val lock = Any()
    private val abortRequested = AtomicBoolean(false)

    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var source: Closeable? = null
    @Volatile private var lastReplyAtNanos: Long? = null

    val isOpen: Boolean get() = input != null && output != null

    fun millisSinceLastReply(): Long? =
        lastReplyAtNanos?.let { (System.nanoTime() - it) / NANOS_PER_MILLI }

    fun attach(closeable: Closeable, inputStream: InputStream, outputStream: OutputStream) {
        close()
        source = closeable
        input = inputStream
        output = outputStream
        lastReplyAtNanos = null
        abortRequested.set(false)
    }

    // Deliberately not locked: closing the socket is what unblocks a stuck command.
    fun close() {
        val closeable = source
        source = null
        input = null
        output = null
        try {
            closeable?.close()
        } catch (_: IOException) {
            // Already closed or broken; nothing more to release.
        }
    }

    fun requestAbort() = abortRequested.set(true)

    fun clearAbort() = abortRequested.set(false)

    /** Runs [block] while holding the command lock so a multi-step sequence is not interleaved. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }

    fun send(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String = synchronized(lock) {
        val out = output ?: throw IOException(NOT_CONNECTED)
        val inp = input ?: throw IOException(NOT_CONNECTED)
        drain(inp)
        logger("TX", command)
        out.write("$command\r".toByteArray(Charsets.US_ASCII))
        out.flush()

        val raw = StringBuilder()
        val buffer = ByteArray(256)
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        var promptReceived = false
        while (!promptReceived) {
            if (abortRequested.get()) {
                resync(out, inp)
                logger("!!", "cancelled")
                throw AbortedException()
            }
            val available = inp.available()
            if (available > 0) {
                val count = inp.read(buffer, 0, minOf(buffer.size, available))
                if (count < 0) throw IOException("OBD-II adapter disconnected")
                val promptIndex = (0 until count).firstOrNull { buffer[it] == PROMPT } ?: -1
                raw.append(String(buffer, 0, if (promptIndex >= 0) promptIndex else count, Charsets.US_ASCII))
                promptReceived = promptIndex >= 0
            } else if (System.nanoTime() >= deadline) {
                resync(out, inp)
                logger("!!", "no reply within ${timeoutMs / 1000}s")
                throw TimeoutException("No reply from adapter to $command")
            } else {
                try {
                    Thread.sleep(WAIT_STEP_MS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for adapter", error)
                }
            }
        }
        lastReplyAtNanos = System.nanoTime()
        val cleaned = clean(raw.toString(), command)
        logger("RX", cleaned.ifEmpty { "(empty)" })
        cleaned
    }

    private fun clean(raw: String, command: String): String =
        raw.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(command, ignoreCase = true) }
            .joinToString(" ")

    private fun drain(inp: InputStream) {
        var available = inp.available()
        while (available > 0) {
            val skipped = inp.skip(available.toLong())
            if (skipped <= 0) break
            available = inp.available()
        }
    }

    // A bare carriage return interrupts whatever the adapter is doing and brings back the prompt,
    // so a late reply to an abandoned command cannot be mistaken for the next command's reply.
    private fun resync(out: OutputStream, inp: InputStream) {
        try {
            out.write('\r'.code)
            out.flush()
            val deadline = System.nanoTime() + RESYNC_TIMEOUT_MS * NANOS_PER_MILLI
            val buffer = ByteArray(256)
            while (System.nanoTime() < deadline) {
                val available = inp.available()
                if (available > 0) {
                    val count = inp.read(buffer, 0, minOf(buffer.size, available))
                    if (count > 0 && (0 until count).any { buffer[it] == PROMPT }) return
                } else {
                    Thread.sleep(WAIT_STEP_MS)
                }
            }
        } catch (_: IOException) {
            // The link is probably gone; the caller reports the original failure.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        private const val NOT_CONNECTED = "OBD-II adapter is not connected"
        private const val WAIT_STEP_MS = 10L
        private const val RESYNC_TIMEOUT_MS = 1500L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val PROMPT = '>'.code.toByte()
    }
}
