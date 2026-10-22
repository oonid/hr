package id.or.oo.hr.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvFileTest {

    private val values = linkedMapOf("API_SERVER_ENABLED" to "true", "API_SERVER_KEY" to "k".repeat(64))

    @Test
    fun anEmptyFileGetsTheValuesInOrder() {
        assertEquals("API_SERVER_ENABLED=true\nAPI_SERVER_KEY=${"k".repeat(64)}\n", mergeEnv("", values))
    }

    @Test
    fun theValuesReplaceTheirKeysAndEveryOtherLineStays() {
        val existing = "# set by hand\nTELEGRAM_BOT_TOKEN=abc\nAPI_SERVER_KEY=old\n\nexport API_SERVER_ENABLED=false\nOTHER=x"

        assertEquals(
            "# set by hand\nTELEGRAM_BOT_TOKEN=abc\n\nOTHER=x\nAPI_SERVER_ENABLED=true\nAPI_SERVER_KEY=${"k".repeat(64)}\n",
            mergeEnv(existing, values),
        )
    }

    @Test
    fun aKeyThatOnlyStartsLikeAManagedOneStays() {
        assertEquals(
            "API_SERVER_KEYS=list\nAPI_SERVER_ENABLED=true\nAPI_SERVER_KEY=${"k".repeat(64)}\n",
            mergeEnv("API_SERVER_KEYS=list\n", values),
        )
    }

    @Test
    fun carriageReturnsAreDropped() {
        assertEquals(
            "A=1\nAPI_SERVER_ENABLED=true\nAPI_SERVER_KEY=${"k".repeat(64)}\n",
            mergeEnv("A=1\r\nAPI_SERVER_KEY=old\r\n", values),
        )
    }
}
