package id.or.oo.hr.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamRedactionTest {

    private val secret = "hrsecret12345"

    @Test
    fun noSecretsLeavesTextAlone() {
        assertEquals("key is hrsec", displayText("key is hrsec", emptyList()))
        assertEquals("key is hrsec", finalText("key is hrsec", emptyList()))
        assertEquals("", displayText("", listOf(secret)))
    }

    @Test
    fun aCompleteSecretIsRedacted() {
        assertEquals("key is [redacted] done", displayText("key is hrsecret12345 done", listOf(secret)))
        assertEquals("key is [redacted]", displayText("key is hrsecret12345", listOf(secret)))
        assertEquals("key is [redacted] done", finalText("key is hrsecret12345 done", listOf(secret)))
    }

    @Test
    fun aTrailingPrefixIsHeldBack() {
        assertEquals("key is ", displayText("key is h", listOf(secret)))
        assertEquals("key is ", displayText("key is hrsecret", listOf(secret)))
        assertEquals("key is ", displayText("key is hrsecret1234", listOf(secret)))
        assertEquals("", displayText("hrsec", listOf(secret)))
        assertEquals("key is hrsecret1234", finalText("key is hrsecret1234", listOf(secret)))
        assertEquals("key is hrsection", displayText("key is hrsection", listOf(secret)))
    }

    @Test
    fun aTrailingPrefixShorterThanTheMinimumIsNotHeld() {
        assertEquals("say short1", displayText("say short1", listOf("short12")))
        assertEquals("say short12", displayText("say short12", listOf("short12")))
        assertEquals("blank ", displayText("blank ", listOf("          ")))
    }

    @Test
    fun twoSecretsOverlappingPrefixes() {
        val secrets = listOf(secret, "hrsecond9999")
        assertEquals("x ", displayText("x hrsec", secrets))
        assertEquals("x ", displayText("x hrseco", secrets))
        assertEquals("x ", displayText("x hrsecr", secrets))
        assertEquals("x [redacted] y ", displayText("x hrsecond9999 y hrsecre", secrets))
        assertEquals("x hrsecz", displayText("x hrsecz", secrets))
    }

    @Test
    fun aRedactedSecretIsNotCutOpenByAnotherSecretsPrefix() {
        val secrets = listOf("abcdefgh", "fghijklm")
        assertEquals("is [redacted]", displayText("is abcdefgh", secrets))
        assertEquals("is [redacted] ", displayText("is abcdefgh fgh", secrets))
    }
}
