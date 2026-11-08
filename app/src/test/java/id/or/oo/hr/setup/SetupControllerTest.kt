package id.or.oo.hr.setup

import id.or.oo.hr.HrFiles
import id.or.oo.hr.gateway.GatewayController
import id.or.oo.hr.image.Http
import id.or.oo.hr.image.HttpResponse
import id.or.oo.hr.profile.ProfileStore
import id.or.oo.pr.session.Bind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

class SetupControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val profile = Bind("/host/profile", "/root/.hermes/profiles/android")
    private val calls = Collections.synchronizedList(mutableListOf<String>())
    private val scripts = Collections.synchronizedList(mutableListOf<String>())

    private var loggedIn = false
    private var checkFailure: Exception? = null
    private var loginExit = CompletableDeferred<Int>()
    private var checkGate: CompletableDeferred<Unit>? = null
    private val checkCancelled = CompletableDeferred<Unit>()
    private val loginCancelled = CompletableDeferred<Unit>()
    private var models: String? = "MODELS [\"gpt-6.1-sol\", \"gpt-5.6-luna\"]"
    private val modelsGate = CompletableDeferred<Unit>()
    private var configExit = 0
    private var restartWith: suspend () -> String? = { null }
    private var configured = true

    private val command = HermesCommand(
        { args, onLine ->
            val script = args.last()
            when (script) {
                "hermes auth status openai-codex" -> {
                    calls += "check"
                    checkFailure?.let { throw it }
                    try {
                        checkGate?.await()
                    } catch (e: CancellationException) {
                        checkCancelled.complete(Unit)
                        throw e
                    }
                    if (loggedIn) onLine("openai-codex: logged in")
                    0
                }
                LOGIN_SCRIPT -> {
                    calls += "login"
                    onLine("https://auth.openai.com/codex/device")
                    onLine("6RTM-9MWDR")
                    try {
                        loginExit.await()
                    } catch (e: CancellationException) {
                        loginCancelled.complete(Unit)
                        throw e
                    }
                }
                CODEX_MODELS_SCRIPT -> {
                    calls += "models"
                    modelsGate.await()
                    models?.let(onLine)
                    0
                }
                else -> {
                    calls += "config"
                    scripts += script
                    configExit
                }
            }
        },
        profile,
    )

    private val http = object : Http {
        override fun get(url: String, headers: Map<String, String>) = error("unused")
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            calls += "send"
            return HttpResponse(200, emptyMap(), """{"choices": [{"message": {"content": "ok"}}]}""")
        }
    }

    private val login = CodexLogin(command, { _, _ -> AutoCloseable {} }, scope)
    private val store by lazy { ProfileStore(HrFiles(tmp.root)) }
    private val controller by lazy {
        SetupController(
            login,
            ModelCatalog(command),
            ProfileSettings(command, store),
            TestRequest(http, { "k" }),
            { calls += "restart"; restartWith() },
            { configured },
            scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun await(match: (SetupStep) -> Boolean): SetupStep = runBlocking {
        withTimeout(5_000) { controller.step.first(match) }
    }

    private fun waitFor(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) kotlinx.coroutines.yield() }
    }

    private val answered = SetupStep.Done(TestResult.Answered("ok"))

    @Test
    fun loggingInThenSavingTestsThroughTheGateway() {
        controller.chooseChatGpt()
        await { it is SetupStep.ChatGpt && it.login is LoginState.Waiting }
        loginExit.complete(0)
        assertEquals(
            SetupStep.ChatGpt(LoginState.LoggedIn, null, true),
            await { it is SetupStep.ChatGpt && it.loadingModels },
        )
        modelsGate.complete(Unit)
        await { it is SetupStep.ChatGpt && it.models != null }
        assertEquals(
            SetupStep.ChatGpt(LoginState.LoggedIn, listOf("gpt-6.1-sol", "gpt-5.6-luna"), false),
            controller.step.value,
        )

        controller.saveCodexModel("gpt-5.6-luna")

        assertEquals(answered, await { it is SetupStep.Done })
        assertEquals(listOf("check", "login", "models", "config", "restart", "send"), calls)
        assertTrue(scripts.single().startsWith("hermes config set model.provider openai-codex"))
        assertTrue(scripts.single().contains("model.default gpt-5.6-luna"))
    }

    @Test
    fun modelsUnavailableStillAllowsATypedModel() {
        loggedIn = true
        models = null
        modelsGate.complete(Unit)
        controller.chooseChatGpt()
        await { it is SetupStep.ChatGpt && it.login == LoginState.LoggedIn && !it.loadingModels && "models" in calls }
        assertEquals(SetupStep.ChatGpt(LoginState.LoggedIn, null, false), controller.step.value)

        controller.saveCodexModel("gpt-typed")

        assertEquals(answered, await { it is SetupStep.Done })
        assertTrue(scripts.single().contains("model.default gpt-typed"))
    }

    @Test
    fun aRestartThatFailsIsAnErrorWithTheGatewaysReason() {
        restartWith = { GatewayController.PORT_IN_USE }

        controller.test()

        val error = await { it is SetupStep.Error } as SetupStep.Error
        assertTrue(error.reason.contains(GatewayController.PORT_IN_USE))
        assertEquals("the gateway did not start: ${GatewayController.PORT_IN_USE}", error.reason)
        assertFalse("send" in calls)
    }

    @Test
    fun testWithoutAnyProviderIsAnError() {
        configured = false

        controller.test()

        assertEquals(SetupStep.Error("choose a provider and save it first"), await { it is SetupStep.Error })
        assertFalse("restart" in calls)
    }

    @Test
    fun aTestAloneRestartsAndSends() {
        controller.test()

        assertEquals(answered, await { it is SetupStep.Done })
        assertEquals(listOf("restart", "send"), calls)
    }

    @Test
    fun anExistingLoginSkipsSignIn() {
        loggedIn = true
        modelsGate.complete(Unit)

        controller.chooseChatGpt()

        assertEquals(
            SetupStep.ChatGpt(LoginState.LoggedIn, listOf("gpt-6.1-sol", "gpt-5.6-luna"), false),
            await { it is SetupStep.ChatGpt && it.models != null },
        )
        assertEquals(listOf("check", "models"), calls)
        assertEquals(LoginState.Idle, login.state.value)
    }

    @Test
    fun aSaveThatFailsIsAnError() {
        controller.chooseApiKey(ApiProvider.OpenRouter, "   ", "openai/gpt-5", null)

        assertEquals(SetupStep.Error("the API key is empty"), await { it is SetupStep.Error })
        assertFalse("restart" in calls)
    }

    @Test
    fun aFailingCodexSaveIsAnError() {
        configExit = 2

        controller.saveCodexModel("gpt-5.6-luna")

        assertEquals(
            SetupStep.Error("hermes config set exited with code 2"),
            await { it is SetupStep.Error },
        )
        assertFalse("restart" in calls)
    }

    @Test
    fun anApiKeyIsSavedAndTestedWithoutTheKeyInAnyStep() {
        val key = "sk-or-v1-abcdef123456"
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val watch = scope.launch { controller.step.collect { seen += it.toString() } }
        waitFor { seen.isNotEmpty() }

        controller.chooseApiKey(ApiProvider.OpenRouter, key, "openai/gpt-5", null)

        assertEquals(answered, await { it is SetupStep.Done })
        watch.cancel()
        assertEquals(listOf("config", "restart", "send"), calls)
        assertTrue(seen.none { it.contains(key) })
    }

    @Test
    fun telegramWithoutAProviderIsSaved() {
        configured = false

        controller.saveTelegram("123:abc", "1")

        assertEquals(SetupStep.Saved("Telegram"), await { it is SetupStep.Saved })
        assertFalse("restart" in calls)
    }

    @Test
    fun telegramWithAProviderIsTested() {
        controller.saveTelegram("123:abc", "1")

        assertEquals(answered, await { it is SetupStep.Done })
        assertEquals(listOf("restart", "send"), calls)
    }

    @Test
    fun aBadTelegramTokenIsAnError() {
        controller.saveTelegram("  ", "1")

        assertEquals(SetupStep.Error("the bot token is empty"), await { it is SetupStep.Error })
    }

    @Test
    fun actionsWhileTestingAreIgnored() {
        val gate = CompletableDeferred<String?>()
        restartWith = { gate.await() }
        controller.test()
        await { it == SetupStep.Testing }
        waitFor { "restart" in calls }

        controller.chooseChatGpt()
        controller.saveCodexModel("m")
        controller.chooseApiKey(ApiProvider.OpenAI, "sk-x", "m", null)
        controller.saveTelegram("123:abc", "")
        controller.test()
        controller.back()

        assertEquals(SetupStep.Testing, controller.step.value)
        gate.complete(null)
        assertEquals(answered, await { it is SetupStep.Done })
        assertEquals(listOf("restart", "send"), calls)
    }

    @Test
    fun backCancelsTheLogin() {
        controller.chooseChatGpt()
        await { it is SetupStep.ChatGpt && it.login is LoginState.Waiting }

        controller.back()

        assertEquals(SetupStep.Choose, controller.step.value)
        runBlocking { withTimeout(5_000) { loginCancelled.await() } }
        assertEquals(LoginState.Cancelled, login.state.value)
        assertEquals(SetupStep.Choose, controller.step.value)
    }

    @Test
    fun cancelLoginShowsCancelled() {
        controller.chooseChatGpt()
        await { it is SetupStep.ChatGpt && it.login is LoginState.Waiting }

        controller.cancelLogin()

        assertEquals(
            SetupStep.ChatGpt(LoginState.Cancelled, null, false),
            await { it is SetupStep.ChatGpt && it.login == LoginState.Cancelled },
        )
    }

    @Test
    fun backFromAnErrorChooses() {
        configured = false
        controller.test()
        await { it is SetupStep.Error }

        controller.back()

        assertEquals(SetupStep.Choose, controller.step.value)
    }

    @Test
    fun aFailingCheckIsAnError() {
        checkFailure = IllegalStateException("no container")

        controller.chooseChatGpt()

        assertEquals(SetupStep.Error("no container"), await { it is SetupStep.Error })
        assertFalse("login" in calls)
    }

    @Test
    fun anExceptionWithoutAMessageIsNamedByItsClass() {
        restartWith = { throw IllegalStateException() }

        controller.test()

        assertEquals(SetupStep.Error("IllegalStateException"), await { it is SetupStep.Error })
    }

    private fun checks() = calls.count { it == "check" }

    @Test
    fun theFirstStepIsStartingEvenAfterAnEarlierLogin() {
        val starting = SetupStep.ChatGpt(LoginState.Starting, null, false)
        modelsGate.complete(Unit)
        loginExit.complete(1)
        controller.chooseChatGpt()
        await { it is SetupStep.ChatGpt && it.login is LoginState.Failed }
        controller.back()

        val failedGate = CompletableDeferred<Unit>().also { checkGate = it }
        controller.chooseChatGpt()
        waitFor { checks() == 2 }
        assertTrue(login.state.value is LoginState.Failed)
        assertEquals(starting, controller.step.value)

        loginExit = CompletableDeferred(0)
        failedGate.complete(Unit)
        await { it is SetupStep.ChatGpt && it.models != null }
        controller.back()

        checkGate = CompletableDeferred()
        controller.chooseChatGpt()
        waitFor { checks() == 3 }
        assertEquals(LoginState.LoggedIn, login.state.value)
        assertEquals(starting, controller.step.value)
    }

    @Test
    fun cancelDuringTheCheckStartsNoLogin() {
        checkGate = CompletableDeferred()
        controller.chooseChatGpt()
        waitFor { "check" in calls }

        controller.cancelLogin()

        assertEquals(SetupStep.ChatGpt(LoginState.Cancelled, null, false), controller.step.value)
        runBlocking { withTimeout(5_000) { checkCancelled.await() } }
        assertFalse("login" in calls)
        assertEquals(LoginState.Idle, login.state.value)
        assertEquals(SetupStep.ChatGpt(LoginState.Cancelled, null, false), controller.step.value)
    }
}
