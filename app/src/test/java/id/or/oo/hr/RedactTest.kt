package id.or.oo.hr

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactTest {

    @Test
    fun everySecretIsReplaced() {
        assertEquals("a [redacted] b [redacted]", redact("a one b two", listOf("one", "two")))
    }

    @Test
    fun blankSecretsAreIgnored() {
        assertEquals("keep it", redact("keep it", listOf("", "  ")))
    }

    @Test
    fun theLongestSecretGoesFirst() {
        assertEquals("[redacted]", redact("abcdef", listOf("abc", "abcdef")))
    }

    @Test
    fun aTextWithoutSecretsIsUnchanged() {
        assertEquals("plain", redact("plain", listOf("x")))
    }
}
