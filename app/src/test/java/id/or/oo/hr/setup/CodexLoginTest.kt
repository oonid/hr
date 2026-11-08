package id.or.oo.hr.setup

import id.or.oo.pr.session.Bind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CodexLoginTest {

    private val profile = Bind("/host/profile", "/root/.hermes/profiles/android")
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private class Holder : TaskHolder {
        val begunCount = AtomicInteger()
        val closedCount = AtomicInteger()
        val begun get() = begunCount.get()
        val closed get() = closedCount.get()
        var label = ""
        var onCancel: () -> Unit = {}
        override fun begin(label: String, onCancel: () -> Unit): AutoCloseable {
            begunCount.incrementAndGet()
            this.label = label
            this.onCancel = onCancel
            return AutoCloseable { closedCount.incrementAndGet() }
        }
    }

    private class Fake {
        val lines = Channel<String>(Channel.UNLIMITED)
        val exit = CompletableDeferred<Int>()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val scripts = mutableListOf<String>()
        var failure: Exception? = null

        fun command(profile: Bind) = HermesCommand(
            { args, onLine ->
                scripts += args.last()
                started.complete(Unit)
                failure?.let { throw it }
                try {
                    var code: Int? = null
                    while (code == null) {
                        val line = lines.tryReceive().getOrNull()
                        if (line != null) onLine(line) else if (exit.isCompleted) code = exit.await()
                        else kotlinx.coroutines.yield()
                    }
                    code
                } catch (e: CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
            },
            profile,
        )
    }

    private val fake = Fake()
    private val holder = Holder()
    private val login = CodexLogin(fake.command(profile), holder, scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun await(match: (LoginState) -> Boolean) = runBlocking {
        withTimeout(5_000) { login.state.first(match) }
    }

    private fun feedLogin() {
        fake.lines.trySend("Starting device code login")
        fake.lines.trySend("  https://auth.openai.com/codex/device")
        fake.lines.trySend("     6RTM-9MWDR")
    }

    @Test
    fun theUrlAndTheCodeAreShownWhileWaiting() {
        login.start()
        assertEquals(LoginState.Starting, login.state.value)
        feedLogin()
        val waiting = await { it is LoginState.Waiting }
        assertEquals(LoginState.Waiting("https://auth.openai.com/codex/device", "6RTM-9MWDR"), waiting)
        assertEquals(listOf(LOGIN_SCRIPT), fake.scripts)
    }

    @Test
    fun theUrlAloneIsNotWaitingYet() {
        login.start()
        fake.lines.trySend("https://auth.openai.com/codex/device")
        fake.lines.trySend("something else")
        fake.exit.complete(1)
        assertEquals(LoginState.Failed("something else"), await { it is LoginState.Failed })
    }

    @Test
    fun theAddedLineAndExit0AreLoggedIn() {
        login.start()
        feedLogin()
        fake.lines.trySend("Added openai-codex OAuth credential #1: \"openai-codex-oauth-1\"")
        fake.exit.complete(0)
        assertEquals(LoginState.LoggedIn, await { it == LoginState.LoggedIn })
    }

    @Test
    fun aNonZeroExitIsFailedWithTheLastLine() {
        login.start()
        feedLogin()
        fake.lines.trySend("Error: device code expired")
        fake.lines.trySend("   ")
        fake.exit.complete(1)
        assertEquals(LoginState.Failed("Error: device code expired"), await { it is LoginState.Failed })
    }

    @Test
    fun aNonZeroExitWithoutOutputNamesTheCode() {
        login.start()
        fake.exit.complete(3)
        assertEquals(
            LoginState.Failed("hermes auth exited with code 3"),
            await { it is LoginState.Failed },
        )
    }

    @Test
    fun anExceptionIsFailedWithItsMessage() {
        fake.failure = IllegalStateException("no container")
        login.start()
        assertEquals(LoginState.Failed("no container"), await { it is LoginState.Failed })
        waitFor { holder.closed == 1 }
        assertEquals(1, holder.closed)
    }

    @Test
    fun anExceptionWithoutAMessageIsFailedWithItsClassName() {
        fake.failure = IllegalStateException()
        login.start()
        assertEquals(LoginState.Failed("IllegalStateException"), await { it is LoginState.Failed })
    }

    @Test
    fun cancelStopsTheCommandAndSaysCancelled() {
        login.start()
        feedLogin()
        await { it is LoginState.Waiting }
        login.cancel()
        runBlocking { withTimeout(5_000) { fake.cancelled.await() } }
        assertEquals(LoginState.Cancelled, login.state.value)
    }

    @Test
    fun theTasksCancelStopsTheLogin() {
        login.start()
        feedLogin()
        await { it is LoginState.Waiting }
        holder.onCancel()
        runBlocking { withTimeout(5_000) { fake.cancelled.await() } }
        assertEquals(LoginState.Cancelled, login.state.value)
    }

    @Test
    fun cancelWhenNothingRunsDoesNothing() {
        login.cancel()
        assertEquals(LoginState.Idle, login.state.value)
    }

    @Test
    fun aTaskIsHeldWhileTheLoginRuns() {
        login.start()
        assertEquals(1, holder.begun)
        assertEquals("Signing in to ChatGPT…", holder.label)
        feedLogin()
        await { it is LoginState.Waiting }
        assertEquals(0, holder.closed)
        fake.exit.complete(0)
        await { it == LoginState.LoggedIn }
        waitFor { holder.closed == 1 }
        assertEquals(1, holder.closed)
    }

    @Test
    fun theTaskIsClosedOnceWhenCancelled() {
        login.start()
        runBlocking { withTimeout(5_000) { fake.started.await() } }
        login.cancel()
        runBlocking { withTimeout(5_000) { fake.cancelled.await() } }
        waitFor { holder.closed == 1 }
        assertEquals(1, holder.closed)
    }

    @Test
    fun startWhileRunningDoesNothing() {
        login.start()
        login.start()
        feedLogin()
        await { it is LoginState.Waiting }
        login.start()
        assertEquals(1, holder.begun)
        assertEquals(1, fake.scripts.size)
    }

    private class Run {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Int>()
    }

    private fun stubbornLogin(vararg runs: Run): CodexLogin {
        val queue = ArrayDeque(runs.toList())
        val cmd = HermesCommand(
            { _, _ ->
                val run = synchronized(queue) { queue.removeFirst() }
                run.started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    run.cancelled.complete(Unit)
                    withContext(NonCancellable) { run.release.await() }
                }
            },
            profile,
        )
        return CodexLogin(cmd, holder, scope)
    }

    private fun released(run: Run) = runBlocking { withTimeout(5_000) { run.cancelled.await() } }

    @Test
    fun anExitAfterCancelDoesNotReplaceCancelled() {
        for (code in listOf(0, 1)) {
            val run = Run()
            val stubborn = stubbornLogin(run)
            val before = holder.closed
            stubborn.start()
            runBlocking { withTimeout(5_000) { run.started.await() } }
            stubborn.cancel()
            released(run)
            run.release.complete(code)
            waitFor { holder.closed == before + 1 }
            assertEquals(LoginState.Cancelled, stubborn.state.value)
        }
    }

    @Test
    fun anOldLoginsLateExitDoesNotChangeTheNewLogin() {
        val first = Run()
        val second = Run()
        val stubborn = stubbornLogin(first, second)
        stubborn.start()
        runBlocking { withTimeout(5_000) { first.started.await() } }
        stubborn.cancel()
        released(first)
        stubborn.start()
        runBlocking { withTimeout(5_000) { second.started.await() } }
        first.release.complete(0)
        waitFor { holder.closed == 1 }
        assertEquals(LoginState.Starting, stubborn.state.value)
    }

    @Test
    fun aCancelledLoginCanStartAgain() {
        val first = Run()
        val second = Run()
        val again = stubbornLogin(first, second)
        again.start()
        runBlocking { withTimeout(5_000) { first.started.await() } }
        again.cancel()
        released(first)
        first.release.complete(1)
        waitFor { holder.closed == 1 }
        again.start()
        runBlocking { withTimeout(5_000) { second.started.await() } }
        assertEquals(2, holder.begun)
        assertEquals(LoginState.Starting, again.state.value)
    }

    @Test
    fun checkIsTrueOnlyForLoggedIn() {
        assertTrue(checkWith("  openai-codex: logged in  ", 0))
        assertFalse(checkWith("openai-codex: logged out", 0))
        assertFalse(checkWith("not openai-codex: logged in", 0))
        assertFalse(checkWith("", 1))
    }

    private fun checkWith(line: String, code: Int): Boolean {
        val scripts = mutableListOf<String>()
        val cmd = HermesCommand({ args, onLine -> scripts += args.last(); onLine(line); code }, profile)
        val checked = CodexLogin(cmd, holder, scope)
        val result = runBlocking { checked.check() }
        assertEquals(LoginState.Idle, checked.state.value)
        assertEquals(listOf("hermes auth status openai-codex"), scripts)
        return result
    }

    private fun waitFor(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) kotlinx.coroutines.yield() }
    }
}
