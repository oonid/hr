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
        log.append("a\u0007bc\u007fd\u0000\te\n")

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
        assertEquals(start + 1, log.revision.value)
        log.mark("b")
        assertEquals(start + 2, log.revision.value)
        log.append("c\n")
        assertEquals(start + 3, log.revision.value)
    }

    @Test
    fun aKeySplitAcrossReadsNeverShowsAPart() {
        for (cut in 1 until KEY.length) {
            val redacting = LogBuffer(secrets = { listOf(KEY) })
            redacting.append("token ${KEY.take(cut)}")
            assertNoPartOfKey("cut at $cut", redacting.snapshot())
            redacting.append("${KEY.drop(cut)} ok\n")
            assertEquals("cut at $cut", listOf("token [redacted] ok"), redacting.snapshot())
        }
    }

    @Test
    fun aKeyAcrossTheLineLimitNeverLeaks() {
        for (start in 1_930..2_010) {
            val redacting = LogBuffer(secrets = { listOf(KEY) })
            redacting.append("x".repeat(start) + KEY + " tail")
            assertNoPartOfKey("live at $start", redacting.snapshot())
            redacting.append("\n")
            val lines = redacting.snapshot()
            assertNoPartOfKey("stored at $start", lines)
            assertTrue(lines.all { it.length <= LogBuffer.MAX_LINE_CHARS })
        }
    }

    @Test
    fun aMarkIsRedactedAndCut() {
        val small = LogBuffer(maxLineChars = 20, secrets = { listOf(KEY) })
        small.mark("key $KEY and more text")

        assertEquals(listOf("key [redacted] and m"), small.snapshot())
    }

    @Test
    fun secretsAreReadOncePerAppend() {
        var reads = 0
        val counting = LogBuffer(secrets = { reads += 1; listOf(KEY) })
        counting.append("a\nb\nc\nd")
        counting.snapshot()

        assertEquals(1, reads)
    }

    @Test
    fun aLoneCarriageReturnRedrawsTheLine() {
        log.append("10%\r")
        assertEquals(listOf("10%"), log.snapshot())

        log.append("\u001b[K20%\r30%\n")
        assertEquals(listOf("30%"), log.snapshot())
    }

    @Test
    fun aBackspaceDeletesTheLastCharacter() {
        log.append("ab\bc\n")
        log.append("x🚀\by\n")
        log.append("gone\r\bkept\n")

        assertEquals(listOf("ac", "xy", "kept"), log.snapshot())
    }

    @Test
    fun crlfSplitAcrossReadsEndsOneLine() {
        log.append("one\r")
        log.append("\ntwo\n")

        assertEquals(listOf("one", "two"), log.snapshot())
    }

    @Test
    fun aNewlineInsideASequenceEndsItAndTheLine() {
        log.append("a\u001b[12\nb\n")
        log.append("c\u001b]0;title\nd\n")
        log.append("e\u001b(\nf\n")

        assertEquals(listOf("a", "b", "c", "d", "e", "f"), log.snapshot())
    }

    @Test
    fun anUnterminatedOscDoesNotHideLaterText() {
        log.append("before\u001b]0;never ends")
        log.append("\nlater text\n")

        assertEquals(listOf("before", "later text"), log.snapshot())
    }

    @Test
    fun aLongSequenceIsCappedAndTheTextAfterItKept() {
        log.append("\u001b]" + "a".repeat(260) + "visible\n")
        log.append("\u001bP" + "b".repeat(255) + "\u0007shown\n")
        log.append("\u001b[" + "1".repeat(258) + "csi\n")

        assertEquals(listOf("aaaaavisible", "shown", "111csi"), log.snapshot())
    }

    @Test
    fun anEscInsideASequenceRestartsParsing() {
        log.append("a\u001b[12\u001b[31mb\n")
        log.append("c\u001b]0;t\u001b[0md\n")
        log.append("e\u001b#\u001b[1mf\n")

        assertEquals(listOf("ab", "cd", "ef"), log.snapshot())
    }

    @Test
    fun aLoneEscKeepsTheCharacterAfterIt() {
        log.append("a\u001bébc\n")
        log.append("x\u001b\ny\n")
        log.append("p\u001b\u0007q\n")

        assertEquals(listOf("aébc", "x", "y", "pq"), log.snapshot())
    }

    @Test
    fun escEscStartsANewSequence() {
        log.append("a\u001b\u001b[1mb\n")

        assertEquals(listOf("ab"), log.snapshot())
    }

    @Test
    fun stringSequencesAreSkippedUntilTheirEnd() {
        log.append("\u001bPq#0;data\u001b\\a\n")
        log.append("\u001b_apc\u0007b\n")
        log.append("\u001b^pm\u001b\\c\n")
        log.append("\u001bXsos 日本\u001b\\d\n")

        assertEquals(listOf("a", "b", "c", "d"), log.snapshot())
    }

    @Test
    fun threeByteEscapesAreDroppedWhole() {
        log.append("\u001b#8a\u001b Fb\u001b%Gc\u001b(0d\n")

        assertEquals(listOf("abcd"), log.snapshot())
    }

    @Test
    fun aCsiWithIntermediatesIsDroppedWhole() {
        log.append("a\u001b[0 qb\u001b[!pc\u001b[?25hd\u001b[1ée\n")

        assertEquals(listOf("abcdée"), log.snapshot())
    }

    @Test
    fun anOscSplitMidSequenceIsDropped() {
        log.append("a\u001b]8;;https://ex")
        log.append("ample.org\u001b")
        log.append("\\link\u001b]8;;\u001b")
        log.append("\\\n")

        assertEquals(listOf("alink"), log.snapshot())
    }

    @Test
    fun aCutNeverSplitsASurrogatePair() {
        val small = LogBuffer(maxLineChars = 5)
        small.append("abcd🚀")
        assertEquals(listOf("abcd"), small.snapshot())

        small.append("\n")
        assertEquals(listOf("abcd"), small.snapshot())
    }

    private fun assertNoPartOfKey(label: String, lines: List<String>) {
        for (start in 0..KEY.length - 8) {
            val part = KEY.substring(start, start + 8)
            assertTrue("$label: $part", lines.none { part in it })
        }
    }

    private companion object {
        const val KEY = "3f9a0c7e5b1d2468ace0bd13579f24680a1b2c3d4e5f60718293a4b5c6d7e8f9"
    }
}
