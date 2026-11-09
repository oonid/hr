package id.or.oo.hr.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileStatusTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun profile(vararg files: Pair<String, String>): File {
        val dir = tmp.newFolder()
        files.forEach { (name, text) -> File(dir, name).writeText(text) }
        return dir
    }

    @Test
    fun aChatGptLoginIsSeen() {
        val pool = """{"version":1,"credential_pool":{"openai-codex":[{"access_token":"dummy-access","refresh_token":"dummy-refresh"}]}}"""
        val providers = """{"providers":{"openai-codex":{"tokens":{"access_token":"dummy-access"}}}}"""

        assertTrue(readStatus(profile("auth.json" to pool)).chatGpt)
        assertTrue(readStatus(profile("auth.json" to providers)).chatGpt)
    }

    @Test
    fun noAuthFileIsNotSignedIn() {
        assertFalse(readStatus(profile()).chatGpt)
        assertFalse(readStatus(File(tmp.root, "absent")).chatGpt)
    }

    @Test
    fun aCorruptAuthFileIsNotSignedIn() {
        val auths = listOf(
            "{not json",
            "",
            "[]",
            """{"credential_pool":{"openai-codex":[]}}""",
            """{"credential_pool":{"openai-codex":"x"}}""",
            """{"credential_pool":[]}""",
            """{"providers":{"openai-codex":"x"}}""",
            """{"providers":{"other":{}}}""",
        )
        for (auth in auths) {
            assertFalse(auth, readStatus(profile("auth.json" to auth)).chatGpt)
        }
    }

    @Test
    fun theModelIsRead() {
        val status = readStatus(
            profile("config.yaml" to "model:\n  default: \"gpt-5.6-luna\"  # main\n  provider: openai-codex\n"),
        )

        assertEquals("openai-codex", status.provider)
        assertEquals("gpt-5.6-luna", status.model)
        assertNull(status.baseUrlHost)
    }

    @Test
    fun noConfigHasNoModel() {
        val status = readStatus(profile())

        assertNull(status.provider)
        assertNull(status.model)
        assertNull(status.baseUrlHost)
    }

    @Test
    fun aCustomEndpointShowsOnlyItsHost() {
        val status = readStatus(
            profile("config.yaml" to "model:\n  provider: custom\n  default: m\n  base_url: https://api.example.com/v1\n"),
        )

        assertEquals("api.example.com", status.baseUrlHost)
        assertFalse(status.toString().contains("/v1"))
    }

    @Test
    fun aBaseUrlWithSecretsShowsOnlyItsHost() {
        val status = readStatus(
            profile("config.yaml" to "model:\n  base_url: https://user:pw@api.example.com:8443/v1?token=abc\n"),
        )

        assertEquals("api.example.com", status.baseUrlHost)
    }

    @Test
    fun anUnparseableBaseUrlHasNoHost() {
        assertNull(readStatus(profile("config.yaml" to "model:\n  base_url: not a url\n")).baseUrlHost)
        assertNull(readStatus(profile("config.yaml" to "model:\n  base_url: /local/path\n")).baseUrlHost)
    }

    @Test
    fun savedKeysAreNamedNotShown() {
        val status = readStatus(
            profile(
                ".env" to "OPENROUTER_API_KEY=dummy-or-value\nexport OPENAI_API_KEY=\"dummy-oa-value\"\n" +
                    "ANTHROPIC_API_KEY=\nHERMES_CUSTOM_API_KEY=   \nAPI_SERVER_KEY=dummy-server-value\n" +
                    "TELEGRAM_BOT_TOKEN=dummy-telegram-value\nTELEGRAM_ALLOWED_USERS=111,222\n",
            ),
        )

        assertEquals(setOf("OPENROUTER_API_KEY", "OPENAI_API_KEY"), status.savedKeys)
        val shown = listOf(status.toString()) + status.savedKeys + listOfNotNull(status.provider, status.model, status.baseUrlHost)
        for (value in listOf("dummy-or-value", "dummy-oa-value", "dummy-server-value", "dummy-telegram-value", "111", "222")) {
            assertTrue(value, shown.none { it.contains(value) })
        }
    }

    @Test
    fun telegramIsCounted() {
        val status = readStatus(profile(".env" to "TELEGRAM_BOT_TOKEN=123:abc\nTELEGRAM_ALLOWED_USERS=1,22,333\n"))

        assertTrue(status.telegramToken)
        assertEquals(3, status.telegramUsers)
    }

    @Test
    fun blankTelegramEntriesAreNotCounted() {
        val status = readStatus(profile(".env" to "TELEGRAM_BOT_TOKEN=\nTELEGRAM_ALLOWED_USERS=1, ,,22,\n"))

        assertFalse(status.telegramToken)
        assertEquals(2, status.telegramUsers)
    }

    @Test
    fun anEmptyProfileHasNothingSetUp() {
        assertEquals(SetupStatus(false, null, null, null, emptySet(), false, 0), readStatus(profile()))
    }
}
