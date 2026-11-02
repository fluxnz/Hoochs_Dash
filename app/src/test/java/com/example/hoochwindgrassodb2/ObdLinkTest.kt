package com.example.hoochwindgrassodb2

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ObdLinkTest {
    private class FakeInput : InputStream() {
        private val data = ArrayDeque<Int>()

        @Synchronized
        fun feed(text: String) = text.forEach { data.addLast(it.code) }

        @Synchronized
        override fun available() = data.size

        @Synchronized
        override fun read(): Int = if (data.isEmpty()) -1 else data.removeFirst()
    }

    private fun linkWith(input: FakeInput, out: ByteArrayOutputStream = ByteArrayOutputStream()): ObdLink {
        val link = ObdLink()
        link.attach(input, input, out)
        return link
    }

    @Test
    fun returnsReplyWithoutEchoOrPrompt() {
        val input = FakeInput()
        val out = ByteArrayOutputStream()
        val link = linkWith(input, out)
        // Pre-fed data is drained before sending, so feed from another thread after the write.
        Thread {
            while (out.size() == 0) Thread.sleep(5)
            input.feed("010C\r41 0C 1A F8\r\r>")
        }.start()
        assertEquals("41 0C 1A F8", link.send("010C", 2000))
        assertEquals("010C\r", out.toString(Charsets.US_ASCII))
        assertNotNull(link.millisSinceLastReply())
    }

    @Test
    fun timesOutWhenNoPrompt() {
        val link = linkWith(FakeInput())
        try {
            link.send("0100", 100)
            fail("expected timeout")
        } catch (_: ObdLink.TimeoutException) {
            assertTrue(link.isOpen)
        }
    }

    @Test
    fun abortStopsWaiting() {
        val link = linkWith(FakeInput())
        link.requestAbort()
        try {
            link.send("0100", 5000)
            fail("expected abort")
        } catch (_: ObdLink.AbortedException) {
            link.clearAbort()
        }
    }

    @Test
    fun sendAfterCloseFails() {
        val link = linkWith(FakeInput())
        link.close()
        try {
            link.send("ATZ")
            fail("expected failure")
        } catch (_: java.io.IOException) {
            assertTrue(!link.isOpen)
        }
    }
}
