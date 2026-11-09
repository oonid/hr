package id.or.oo.hr

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactTest {

    @Test
    fun everySecretIsReplaced() {
        assertEquals("a [redacted] b [redacted]", redact("a secret-one b secret-two", listOf("secret-one", "secret-two")))
    }

    @Test
    fun blankSecretsAreIgnored() {
        assertEquals("keep it", redact("keep it", listOf("", "  ")))
    }

    @Test
    fun theLongestSecretGoesFirst() {
        assertEquals("[redacted]", redact("abcdefghij", listOf("abcdefgh", "abcdefghij")))
    }

    @Test
    fun secretsShorterThanEightCharactersAreIgnored() {
        assertEquals("FOO_TOKEN=1 and [redacted]", redact("FOO_TOKEN=1 and 12345678", listOf("1", "12345678")))
    }

    @Test
    fun aTextWithoutSecretsIsUnchanged() {
        assertEquals("plain", redact("plain", listOf("x")))
    }
}
