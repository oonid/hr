package id.or.oo.hr.setup

import id.or.oo.hr.HrFiles
import id.or.oo.hr.profile.ProfileStore
import id.or.oo.pr.session.Bind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val files by lazy { HrFiles(tmp.root) }
    private val store by lazy { ProfileStore(files).also { it.ensureProfile() } }
    private val profile = Bind("/host/profile", "/root/.hermes/profiles/android")
    private val scripts = mutableListOf<String>()

    private fun settings(exit: Int = 0, error: Throwable? = null, vararg lines: String) =
        ProfileSettings(
            HermesCommand({ args, onLine ->
                scripts += args.last()
                error?.let { throw it }
                lines.forEach(onLine)
                exit
            }, profile),
            store,
        )

    private val env get() = File(files.profileDir, ".env")

    @Test
    fun useModelSetsProviderAndModelThroughHermes() = runBlocking {
        val result = settings().useModel("openai-codex", "gpt-5.6-luna")

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("hermes config set model.provider openai-codex && hermes config set model.default gpt-5.6-luna"),
            scripts,
        )
        assertTrue(env.readText().contains("API_SERVER_KEY="))
    }

    @Test
    fun aBaseUrlIsAppended() = runBlocking {
        val url = "https://api.example.com/v1"
        settings().useModel("custom", "m", url)

        assertEquals(
            "hermes config set model.provider custom && hermes config set model.default m" +
                " && hermes config set model.base_url $url",
            scripts.single(),
        )
    }

    @Test
    fun aValueWithShellCharactersIsRefused() = runBlocking {
        val bad = settings().useModel("openai-codex", "gpt;rm -rf /")

        assertTrue(bad.isFailure)
        assertTrue(bad.exceptionOrNull() is IllegalArgumentException)
        assertTrue(bad.exceptionOrNull()!!.message!!.contains("model"))
        assertTrue(settings().useModel("a b", "m").exceptionOrNull()!!.message!!.contains("provider"))
        assertTrue(settings().useModel("p", "m", "http://x/$(id)").exceptionOrNull()!!.message!!.contains("base"))
        assertTrue(scripts.isEmpty())
        assertFalse(env.exists())
    }

    @Test
    fun aFailingConfigSetIsAFailureWithItsLastLine() = runBlocking {
        val result = settings(2, null, "first", "no such key", "").useModel("p", "m")

        assertEquals("no such key", result.exceptionOrNull()!!.message)
        assertFalse(env.exists())
    }

    @Test
    fun aSilentFailureNamesTheExitCode() = runBlocking {
        val result = settings(3).useModel("p", "m")

        assertEquals("hermes config set exited with code 3", result.exceptionOrNull()!!.message)
    }

    @Test
    fun aThrownErrorIsAFailure() = runBlocking {
        val boom = IllegalStateException("boom")

        assertEquals(boom, settings(0, boom).useModel("p", "m").exceptionOrNull())
    }

    @Test
    fun cancellationPropagates() {
        assertThrows(CancellationException::class.java) {
            runBlocking { settings(0, CancellationException("stop")).useModel("p", "m") }
        }
    }
}
