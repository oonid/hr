package id.or.oo.hr.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SseTest {

    private fun read(vararg lines: String): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        readSse(lines.iterator()) { events += it }
        return events
    }

    @Test
    fun framesSplitOnBlankLines() {
        val events = read("data: one", "", "data: two", "")
        assertEquals(listOf(SseEvent(null, "one"), SseEvent(null, "two")), events)
    }

    @Test
    fun aNamedEventKeepsItsName() {
        val events = read("event: hermes.tool.progress", "data: {\"status\": \"running\"}", "")
        assertEquals(listOf(SseEvent("hermes.tool.progress", "{\"status\": \"running\"}")), events)
    }

    @Test
    fun multiLineDataJoins() {
        assertEquals(listOf(SseEvent(null, "a\nb")), read("data: a", "data: b", ""))
    }

    @Test
    fun keepaliveCommentsAreIgnored() {
        val events = read(": keepalive", "", "data: x", ": keepalive", "")
        assertEquals(listOf(SseEvent(null, "x")), events)
    }

    @Test
    fun aTrailingEventWithoutABlankLineIsDelivered() {
        assertEquals(listOf(SseEvent(null, "tail")), read("data: tail"))
    }

    @Test
    fun anEventWithoutDataIsNotDelivered() {
        assertEquals(emptyList<SseEvent>(), read("event: ping", "", "id: 3", ""))
    }

    @Test
    fun dataWithoutASpaceIsKeptAsIs() {
        assertEquals(listOf(SseEvent(null, " x")), read("data:  x", ""))
        assertEquals(listOf(SseEvent(null, "x")), read("data:x", ""))
    }

    @Test
    fun theEventNameResetsBetweenFrames() {
        val events = read("event: a", "data: 1", "", "data: 2", "")
        assertEquals(listOf(SseEvent("a", "1"), SseEvent(null, "2")), events)
    }
}
