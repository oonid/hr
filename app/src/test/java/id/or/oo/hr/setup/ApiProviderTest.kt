package id.or.oo.hr.setup

import id.or.oo.hr.HrFiles
import id.or.oo.hr.profile.ProfileStore
import id.or.oo.pr.session.Bind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApiProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val files by lazy { HrFiles(tmp.root) }
    private val store by lazy { ProfileStore(files).also { it.ensureProfile() } }
    private val profile = Bind("/host/profile", "/root/.hermes/profiles/android")
    private val scripts = mutableListOf<String>()
    private val settings by lazy {
        ProfileSettings(
            HermesCommand({ args, _ ->
                scripts += args.last()
                0
            }, profile),
            store,
        )
    }
    private val env get() = File(files.profileDir, ".env")
    private val key = "sk-or-v1-abcdef123456"

    @Test
    fun theKeyGoesOnlyToTheEnvFile() = runBlocking {
        val result = settings.useApiKey(ApiProvider.OpenRouter, key, "openai/gpt-5", null)

        assertTrue(result.isSuccess)
        assertTrue(env.readText().lines().contains("OPENROUTER_API_KEY=$key"))
        assertTrue(scripts.isNotEmpty())
        assertTrue(scripts.none { it.contains(key) })
        assertTrue(scripts.single().contains("model.provider openrouter"))
    }

    @Test
    fun compatibleNeedsABaseUrl() = runBlocking {
        val none = settings.useApiKey(ApiProvider.Compatible, key, "m", null)
        val blank = settings.useApiKey(ApiProvider.Compatible, key, "m", "  ")

        assertTrue(none.isFailure)
        assertTrue(blank.isFailure)
        assertTrue(scripts.isEmpty())
        assertFalse(env.exists())
        assertFalse(none.exceptionOrNull()!!.message!!.contains(key))
    }

    @Test
    fun compatibleReferencesItsKeyFromTheConfig() = runBlocking {
        val url = "https://api.example.com/v1"
        val result = settings.useApiKey(ApiProvider.Compatible, key, "m", url)

        assertTrue(result.isSuccess)
        assertTrue(env.readText().contains("HERMES_CUSTOM_API_KEY=$key"))
        val script = scripts.single()
        assertTrue(script.contains("model.base_url $url"))
        assertTrue(script.contains("hermes config set model.api_key '\${HERMES_CUSTOM_API_KEY}'"))
        assertFalse(script.contains(key))
    }

    @Test
    fun aBlankKeyIsRefused() = runBlocking {
        val result = settings.useApiKey(ApiProvider.OpenAI, "   ", "m", null)

        assertTrue(result.isFailure)
        assertTrue(scripts.isEmpty())
        assertFalse(env.exists())
    }

    @Test
    fun aKeyWithANewlineIsRefused() = runBlocking {
        val bad = listOf("abc\nOTHER=1", "a b", "a=b", "a\"b", "a'b", "a\\b", "a\u0001b", "a\tb")
        for (k in bad) {
            val result = settings.useApiKey(ApiProvider.Anthropic, k, "m", null)
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull()!!.message!!.contains(k))
        }

        assertTrue(scripts.isEmpty())
        assertFalse(env.exists())
    }

    @Test
    fun theKeyIsTrimmed() = runBlocking {
        settings.useApiKey(ApiProvider.Anthropic, "  $key\n", "m", null)

        assertTrue(env.readText().lines().contains("ANTHROPIC_API_KEY=$key"))
    }

    @Test
    fun anInvalidModelWritesNothing() = runBlocking {
        val result = settings.useApiKey(ApiProvider.OpenRouter, key, "bad model;", null)

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()!!.message!!.contains(key))
        assertTrue(scripts.isEmpty())
        assertFalse(env.exists())
    }

    @Test
    fun anInvalidBaseUrlWritesNothing() = runBlocking {
        val result = settings.useApiKey(ApiProvider.Compatible, key, "m", "http://x/$(id)")

        assertTrue(result.isFailure)
        assertTrue(scripts.isEmpty())
        assertFalse(env.exists())
    }

    @Test
    fun aBaseUrlIsIgnoredWhenNotNeeded() = runBlocking {
        settings.useApiKey(ApiProvider.OpenAI, key, "m", "https://x.example")

        assertFalse(scripts.single().contains("base_url https"))
    }

    @Test
    fun openRouterEntry() = entry(ApiProvider.OpenRouter, "OpenRouter", "openrouter", "OPENROUTER_API_KEY", false)

    @Test
    fun openAiEntry() = entry(ApiProvider.OpenAI, "OpenAI", "openai-api", "OPENAI_API_KEY", false)

    @Test
    fun anthropicEntry() = entry(ApiProvider.Anthropic, "Anthropic", "anthropic", "ANTHROPIC_API_KEY", false)

    @Test
    fun compatibleEntry() =
        entry(ApiProvider.Compatible, "OpenAI-compatible", "custom", "HERMES_CUSTOM_API_KEY", true)

    private fun entry(p: ApiProvider, label: String, id: String, variable: String, base: Boolean) {
        assertEquals(label, p.label)
        assertEquals(id, p.id)
        assertEquals(variable, p.keyVariable)
        assertEquals(base, p.needsBaseUrl)
        assertEquals(4, ApiProvider.entries.size)
    }
}
