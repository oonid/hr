package id.or.oo.hr.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

const val LOGIN_SCRIPT = "PYTHONUNBUFFERED=1 hermes auth add openai-codex --no-browser"
private const val STATUS_SCRIPT = "hermes auth status openai-codex"
private const val LOGGED_IN_LINE = "openai-codex: logged in"
const val LIMIT_MS = 20 * 60_000L
private const val EXPIRED = "the sign-in code expired; sign in again"
private const val TASK_LABEL = "Signing in to ChatGPT…"

private val CODE_LINE = Regex("^[A-Z0-9]{4}-[A-Z0-9]{4,6}$")
private val URL_LINE = Regex("^https://\\S+$")

fun interface TaskHolder {
    fun begin(label: String, onCancel: () -> Unit): AutoCloseable
}

sealed interface LoginState {
    data object Idle : LoginState
    data object Starting : LoginState
    data class Waiting(val url: String, val code: String) : LoginState
    data object LoggedIn : LoginState
    data object Cancelled : LoginState
    data class Failed(val reason: String) : LoginState
}

class CodexLogin(
    private val command: HermesCommand,
    private val tasks: TaskHolder,
    private val scope: CoroutineScope,
    private val limitMs: Long = LIMIT_MS,
) {
    private val lock = Any()
    private var job: Job? = null
    private val mutableState = MutableStateFlow<LoginState>(LoginState.Idle)

    val state: StateFlow<LoginState> = mutableState

    fun start() {
        synchronized(lock) {
            if (job != null) return
            mutableState.value = LoginState.Starting
            val held = tasks.begin(TASK_LABEL, ::cancel)
            job = scope.launch(start = CoroutineStart.ATOMIC) { run(held) }
        }
    }

    fun cancel() {
        val running = synchronized(lock) {
            val current = job ?: return
            job = null
            mutableState.value = LoginState.Cancelled
            current
        }
        running.cancel()
    }

    suspend fun check(): Boolean {
        var loggedIn = false
        command.run(STATUS_SCRIPT) { if (it.trim() == LOGGED_IN_LINE) loggedIn = true }
        return loggedIn
    }

    private suspend fun run(held: AutoCloseable) {
        val me = kotlin.coroutines.coroutineContext[Job]
        var url: String? = null
        var code: String? = null
        var last: String? = null
        try {
            val exit = withTimeoutOrNull(limitMs) {
                command.run(LOGIN_SCRIPT) { raw ->
                    val line = raw.trim()
                    if (line.isEmpty()) return@run
                    last = line
                    if (url == null && URL_LINE.matches(line)) url = line
                    else if (code == null && CODE_LINE.matches(line)) code = line
                    val u = url
                    val c = code
                    if (u != null && c != null) settle(me, LoginState.Waiting(u, c))
                }
            }
            if (exit == null) return settle(me, LoginState.Failed(EXPIRED))
            settle(
                me,
                if (exit == 0) LoginState.LoggedIn
                else LoginState.Failed(last ?: "hermes auth exited with code $exit"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            settle(me, LoginState.Failed(e.message ?: e::class.java.simpleName))
        } finally {
            held.close()
        }
    }

    private fun settle(me: Job?, next: LoginState) {
        synchronized(lock) {
            if (job !== me) return
            mutableState.value = next
            if (next !is LoginState.Waiting) job = null
        }
    }
}
