package id.or.oo.hr.setup

import id.or.oo.pr.session.Bind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelCatalogTest {

    private val profile = Bind("/host/profile", "/root/.hermes/profiles/android")

    private fun catalog(exit: Int = 0, error: Throwable? = null, vararg lines: String) =
        ModelCatalog(
            HermesCommand({ _, onLine ->
                error?.let { throw it }
                lines.forEach(onLine)
                exit
            }, profile),
        )

    @Test
    fun theModelsLineIsParsed() = runBlocking {
        val models = catalog(0, null, "noise", "MODELS [\"gpt-6.1-sol\", \"gpt-5.6-luna\"]").codexModels()

        assertEquals(listOf("gpt-6.1-sol", "gpt-5.6-luna"), models)
    }

    @Test
    fun noModelsLineIsNull() = runBlocking {
        assertNull(catalog(0, null, "hello").codexModels())
    }

    @Test
    fun aFailingScriptIsNull() = runBlocking {
        assertNull(catalog(1, null, "MODELS [\"a\"]").codexModels())
    }

    @Test
    fun anUnparsableListIsNull() = runBlocking {
        assertNull(catalog(0, null, "MODELS not json").codexModels())
        assertNull(catalog(0, null, "MODELS [1, 2]").codexModels())
        assertNull(catalog(0, null, "MODELS {\"a\": 1}").codexModels())
    }

    @Test
    fun anEmptyListIsNull() = runBlocking {
        assertNull(catalog(0, null, "MODELS []").codexModels())
    }

    @Test
    fun aThrowingCommandIsNull() = runBlocking {
        assertNull(catalog(0, IllegalStateException("boom")).codexModels())
    }

    @Test
    fun cancellationPropagates() {
        assertThrows(CancellationException::class.java) {
            runBlocking { catalog(0, CancellationException("stop")).codexModels() }
        }
    }

    @Test
    fun theScriptReadsTheCodexCatalog() = runBlocking {
        var script = ""
        ModelCatalog(HermesCommand({ args, _ -> script = args.last(); 0 }, profile)).codexModels()

        assertEquals(CODEX_MODELS_SCRIPT, script)
    }
}
