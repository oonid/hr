package id.or.oo.hr.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamRedactionTest {

    private val secret = "hrsecret12345"

    @Test
    fun noSecretsLeavesTextAlone() {
        assertEquals("key is hrsecret12345", redactPrefixes("key is hrsecret12345", emptyList()))
        assertEquals("", redactPrefixes("", listOf(secret)))
    }

    @Test
    fun aCompleteSecretIsRedacted() {
        assertEquals("key is [redacted] done", redactPrefixes("key is hrsecret12345 done", listOf(secret)))
        assertEquals("[redacted]", redactPrefixes("hrsecret12345", listOf(secret)))
        assertEquals("a [redacted] b [redacted]", redactPrefixes("a hrsecret12345 b hrsecret12345", listOf(secret, secret)))
    }

    @Test
    fun anAbbreviatedSecretMidTextIsRedacted() {
        assertEquals("key [redacted]… ok", redactPrefixes("key hrsecret12… ok", listOf(secret)))
        assertEquals("key is [redacted]", redactPrefixes("key is hrsecret", listOf(secret)))
        assertEquals("[redacted]6 x", redactPrefixes("hrsecret123456 x", listOf(secret)))
    }

    @Test
    fun aSevenCharacterPrefixIsLeftAlone() {
        assertEquals("key is hrsecre", redactPrefixes("key is hrsecre", listOf(secret)))
        assertEquals("hrsecre ok hrsection", redactPrefixes("hrsecre ok hrsection", listOf(secret)))
    }

    @Test
    fun aSecretShorterThanTheMinimumIsIgnored() {
        assertEquals("say short12", redactPrefixes("say short12", listOf("short12")))
        assertEquals("blank          ", redactPrefixes("blank          ", listOf("          ")))
    }

    @Test
    fun twoSecretsSharingAPrefix() {
        val secrets = listOf(secret, "hrsecond9999")
        assertEquals("x [redacted] y [redacted] z", redactPrefixes("x hrsecond9 y hrsecret1 z", secrets))
        assertEquals("x hrseco", redactPrefixes("x hrseco", secrets))
    }

    @Test
    fun overlappingSecretsAreRedactedAsOneRun() {
        assertEquals("[redacted]", redactPrefixes("abcdefghijklmnopqrst", listOf("abcdefghij", "ijklmnopqrst")))
        assertEquals("is [redacted]!", redactPrefixes("is abcdefghijklm!", listOf("abcdefgh", "fghijklm")))
        assertEquals("[redacted]", redactPrefixes("abcdefghijklmnop", listOf("abcdefgh", "ijklmnop")))
    }
}
