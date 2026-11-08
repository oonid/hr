package id.or.oo.hr.setup

import id.or.oo.pr.session.Bind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HermesCommandTest {

    private val profile = Bind("/data/x/files/hr/profile", "/root/.hermes/profiles/android")

    @Test
    fun runsTheScriptInHermesWithTheProfileBound() {
        val seen = mutableListOf<List<String>>()
        val cmd = HermesCommand({ args, _ -> seen += args; 0 }, profile)
        runBlocking { cmd.run("hermes auth status openai-codex") {} }
        assertEquals(
            listOf(
                "login", "hermes", "--user", "root",
                "--custom-bind", "/data/x/files/hr/profile:/root/.hermes/profiles/android",
                "--", "hermes auth status openai-codex",
            ),
            seen.single(),
        )
    }

    @Test
    fun linesArriveWithoutEscapeCodes() {
        assertEquals("     6RTM-9MWDR", stripAnsi("     \u001b[94m6RTM-9MWDR\u001b[0m"))
    }

    @Test
    fun linesPassedToTheCallerAreStripped() {
        val got = mutableListOf<String>()
        val cmd = HermesCommand({ _, onLine -> onLine("\u001b[1mok\u001b[0m"); 0 }, profile)
        runBlocking { cmd.run("x") { got += it } }
        assertEquals(listOf("ok"), got)
    }

    @Test
    fun theExitCodeIsReturned() {
        val cmd = HermesCommand({ _, _ -> 3 }, profile)
        assertEquals(3, runBlocking { cmd.run("x") {} })
    }
}
