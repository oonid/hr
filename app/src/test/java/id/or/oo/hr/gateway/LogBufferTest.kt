package id.or.oo.hr.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {

    private val log = LogBuffer()

    private fun LogBuffer.append(text: String) {
        val bytes = text.toByteArray()
        append(bytes, 0, bytes.size)
    }

    @Test
    fun ptyLineEndsBecomeLines() {
        log.append("one\r\ntwo\r\n")

        assertEquals(listOf("one", "two"), log.snapshot())
    }

    @Test
    fun utf8SplitAnywhereAcrossReadsDecodesWhole() {
        val text = "naïve ✓ 日本 🚀\n"
        val bytes = text.toByteArray()
        for (cut in 1 until bytes.size) {
            val buffer = LogBuffer()
            buffer.append(bytes, 0, cut)
            buffer.append(bytes, cut, bytes.size - cut)
            assertEquals("cut at $cut", listOf(text.trimEnd('\n')), buffer.snapshot())
        }
    }

    @Test
    fun malformedBytesBecomeReplacementCharacters() {
        log.append(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte(), '\n'.code.toByte()), 0, 4)

        assertEquals(listOf("a�b"), log.snapshot())
    }

    @Test
    fun ansiEscapesAreStrippedEvenWhenSplitAcrossReads() {
        log.append("\u001b[3")
        log.append("2mINFO\u001b[0m ready\r\n")
        log.append("\u001b]0;hermes\u0007title gone\n")
        log.append("\u001b]8;;https://example.org\u001b\\link\u001b]8;;\u001b\\ text\n")
        log.append("\u001b(Bcharset\n")
        log.append("\u001b7saved\u001b8\n")

        assertEquals(listOf("INFO ready", "title gone", "link text", "charset", "saved"), log.snapshot())
    }

    @Test
    fun otherControlCharactersAreDroppedAndTabsKept() {
        log.append("a\u0007b\bc\u007fd\te\n")

        assertEquals(listOf("abcd\te"), log.snapshot())
    }

    @Test
    fun theBufferKeepsOnlyTheNewestLines() {
        val small = LogBuffer(maxLines = 3)
        small.append("1\n2\n3\n4\n5\n")

        assertEquals(listOf("3", "4", "5"), small.snapshot())
    }

    @Test
    fun aLongLineIsCutAtTheLimit() {
        val small = LogBuffer(maxLineChars = 5)
        small.append("abcdefgh\nij\n")

        assertEquals(listOf("abcde", "ij"), small.snapshot())
    }

    @Test
    fun anUnfinishedLineIsShownUntilItEnds() {
        log.append("loading")
        assertEquals(listOf("loading"), log.snapshot())

        log.append(" done\n")
        assertEquals(listOf("loading done"), log.snapshot())
    }

    @Test
    fun tailReturnsTheLastLines() {
        log.append("1\n2\n3\nfour")

        assertEquals(listOf("3", "four"), log.tail(2))
    }

    @Test
    fun secretsAreRedacted() {
        val secret = "0123456789abcdef"
        val redacting = LogBuffer(secrets = { listOf(secret, "") })
        redacting.append("key=$secret ok\nunfinished $secret")

        assertEquals(listOf("key=[redacted] ok", "unfinished [redacted]"), redacting.snapshot())
    }

    @Test
    fun aMarkEndsTheUnfinishedLineAndForgetsAHalfEscape() {
        log.append("half\u001b[3")
        log.mark("Starting the gateway")
        log.append("1mnext\n")

        assertEquals(listOf("half", "Starting the gateway", "1mnext"), log.snapshot())
    }

    @Test
    fun theBufferCopiesWhatItKeeps() {
        val buf = "first\n".toByteArray()
        log.append(buf, 0, buf.size)
        buf.fill('x'.code.toByte())

        assertEquals(listOf("first"), log.snapshot())
    }

    @Test
    fun onlyTheGivenRangeIsRead() {
        val buf = "xxabc\nyy".toByteArray()
        log.append(buf, 2, 4)

        assertEquals(listOf("abc"), log.snapshot())
    }

    @Test
    fun everyChangeMovesTheRevision() {
        val start = log.revision.value
        log.append("a")
        log.mark("b")

        assertTrue(log.revision.value >= start + 2)
    }
}
