package id.or.oo.hr.setup

import id.or.oo.hr.profile.SetupStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun interface GatewayRestart {
    suspend fun restart(): String?
}

sealed interface SetupStep {
    val busy: Boolean get() = this == Saving || this == Testing

    data object Choose : SetupStep
    data class ChatGpt(val login: LoginState, val models: List<String>?, val loadingModels: Boolean) : SetupStep
    data object Saving : SetupStep
    data object Testing : SetupStep
    data class Saved(val message: String) : SetupStep
    data class Done(val result: TestResult) : SetupStep
    data class Error(val reason: String) : SetupStep
}

class SetupController(
    private val login: CodexLogin,
    private val catalog: ModelCatalog,
    private val settings: ProfileSettings,
    private val request: TestRequest,
    private val restart: GatewayRestart,
    private val configured: () -> Boolean,
    private val scope: CoroutineScope,
    private val readStatus: () -> SetupStatus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val imageBusy: () -> Boolean = { false },
) {
    private val lock = Any()
    private val statusLock = Any()
    private var issued = 0L
    private var published = 0L
    private var job: Job? = null
    private var checking = false
    private val mutableStep = MutableStateFlow<SetupStep>(SetupStep.Choose)

    private val mutableStatus = MutableStateFlow(SetupStatus(false, null, null, null, emptySet(), false, 0))

    val step: StateFlow<SetupStep> = mutableStep
    val status: StateFlow<SetupStatus> = mutableStatus

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        scope.launch(io) { refresh() }
    }

    fun chooseChatGpt() = begin(SetupStep.ChatGpt(LoginState.Starting, null, false), checks = true) { followLogin(it) }

    fun cancelLogin() {
        synchronized(lock) {
            if (checking && mutableStep.value is SetupStep.ChatGpt) {
                job?.cancel()
                job = null
                checking = false
                mutableStep.value = SetupStep.ChatGpt(LoginState.Cancelled, null, false)
                return
            }
        }
        login.cancel()
    }

    fun saveCodexModel(model: String) = begin(SetupStep.Saving) { me ->
        save(me, false) { settings.useModel(CODEX, model) }
    }

    fun chooseApiKey(provider: ApiProvider, key: String, model: String, baseUrl: String?) =
        begin(SetupStep.Saving) { me -> save(me, false) { settings.useApiKey(provider, key, model, baseUrl) } }

    fun saveTelegram(token: String, allowedUsers: String) = begin(SetupStep.Saving) { me ->
        save(me, true) { settings.useTelegram(token, allowedUsers) }
    }

    fun removeTelegram() = begin(SetupStep.Saving) { me ->
        val failure = withContext(io) { settings.removeTelegram() }.exceptionOrNull()
        if (failure != null) return@begin settle(me, SetupStep.Error(failure.message ?: failure.javaClass.simpleName))
        refresh()
        if (withContext(io) { configured() }) {
            settle(me, SetupStep.Testing)
            runTest(me)
        } else {
            settle(me, SetupStep.Saved("Telegram removed. It takes effect when the gateway restarts."))
        }
    }

    fun test() = begin(SetupStep.Testing) { me ->
        if (withContext(io) { configured() }) runTest(me) else settle(me, SetupStep.Error(NO_PROVIDER))
    }

    fun back() {
        val wasChatGpt = synchronized(lock) {
            val current = mutableStep.value
            if (current.busy) return
            job?.cancel()
            job = null
            checking = false
            mutableStep.value = SetupStep.Choose
            current is SetupStep.ChatGpt
        }
        if (wasChatGpt) login.cancel()
    }

    private fun begin(first: SetupStep, checks: Boolean = false, work: suspend (Job) -> Unit) {
        synchronized(lock) {
            if (mutableStep.value.busy || imageBusy()) return
            job?.cancel()
            checking = checks
            mutableStep.value = first
            val next = scope.launch(start = CoroutineStart.LAZY) {
                val me = coroutineContext[Job]!!
                try {
                    work(me)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    settle(me, SetupStep.Error(e.message ?: e.javaClass.simpleName))
                }
            }
            job = next
            next.start()
        }
    }

    private suspend fun followLogin(me: Job) {
        val loggedIn = login.check()
        synchronized(lock) {
            if (job !== me) return
            checking = false
            if (!loggedIn) login.start()
        }
        if (loggedIn) {
            onChatGpt(me) { it.copy(login = LoginState.LoggedIn) }
        } else {
            login.state.first { state ->
                onChatGpt(me) { it.copy(login = state) }
                state == LoginState.LoggedIn
            }
        }
        refresh()
        onChatGpt(me) { it.copy(loadingModels = true) }
        val models = catalog.codexModels()
        onChatGpt(me) { it.copy(models = models, loadingModels = false) }
    }

    private suspend fun save(me: Job, telegram: Boolean, write: suspend () -> Result<Unit>) {
        val failure = withContext(io) { write() }.exceptionOrNull()
        refresh()
        when {
            failure != null -> settle(me, SetupStep.Error(failure.message ?: failure.javaClass.simpleName))
            telegram && !withContext(io) { configured() } -> settle(me, SetupStep.Saved("Telegram saved."))
            else -> {
                settle(me, SetupStep.Testing)
                runTest(me)
            }
        }
    }

    private suspend fun refresh() {
        val ticket = synchronized(statusLock) { ++issued }
        val read = withContext(io) { readStatus() }
        synchronized(statusLock) {
            if (ticket > published) {
                published = ticket
                mutableStatus.value = read
            }
        }
    }

    private suspend fun runTest(me: Job) {
        val reason = restart.restart()
        if (reason != null) return settle(me, SetupStep.Error("the gateway did not start: $reason"))
        settle(me, SetupStep.Done(withContext(io) { request.send() }))
    }

    private fun onChatGpt(me: Job, change: (SetupStep.ChatGpt) -> SetupStep.ChatGpt) {
        synchronized(lock) {
            val current = mutableStep.value
            if (job === me && current is SetupStep.ChatGpt) mutableStep.value = change(current)
        }
    }

    private fun settle(me: Job, next: SetupStep) {
        synchronized(lock) {
            if (job === me) mutableStep.value = next
        }
    }

    private companion object {
        const val CODEX = "openai-codex"
        const val NO_PROVIDER = "choose a provider and save it first"
    }
}
