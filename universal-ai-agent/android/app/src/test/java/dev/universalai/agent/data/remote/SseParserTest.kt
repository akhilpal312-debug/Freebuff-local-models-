package dev.universalai.agent.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    @Test
    fun `single data line produces an event on blank line`() {
        val parser = SseParser()
        assertNull(parser.feed("data: {\"type\":\"delta\"}"))
        val event = parser.feed("")
        assertEquals("{\"type\":\"delta\"}", event?.data)
        assertNull(event?.event)
    }

    @Test
    fun `multiple data lines are joined with newlines`() {
        val parser = SseParser()
        parser.feed("data: line one")
        parser.feed("data: line two")
        val event = parser.feed("")
        assertEquals("line one\nline two", event?.data)
    }

    @Test
    fun `event name is captured and reset`() {
        val parser = SseParser()
        parser.feed("event: done")
        parser.feed("data: {}")
        val event = parser.feed("")
        assertEquals("done", event?.event)
        assertEquals("{}", event?.data)
        assertNull(parser.feed(""))
    }

    @Test
    fun `comments and unknown fields are ignored`() {
        val parser = SseParser()
        assertNull(parser.feed(": keep-alive"))
        assertNull(parser.feed("id: 42"))
        assertNull(parser.feed("retry: 3000"))
        assertNull(parser.feed(""))
    }

    @Test
    fun `CRLF endings are accepted`() {
        val parser = SseParser()
        assertNull(parser.feed("data: hello\r"))
        val event = parser.feed("\r")
        assertEquals("hello", event?.data)
    }

    @Test
    fun `flush emits buffered data without a trailing blank line`() {
        val parser = SseParser()
        parser.feed("data: tail")
        val event = parser.flush()
        assertEquals("tail", event?.data)
        assertNull(parser.flush())
    }
}
