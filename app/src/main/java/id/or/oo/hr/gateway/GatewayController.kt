package id.or.oo.hr.gateway

import id.or.oo.hr.Hermes
import id.or.oo.hr.profile.ProfileStore
import id.or.oo.pr.session.ManagedSession
import id.or.oo.pr.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

fun interface GatewayLaunch {
    fun prepare(): SessionManager.SessionFactory
}

class GatewayController(
    private val sessions: SessionManager,
    private val launch: GatewayLaunch,
    private val probe: GatewayProbe,
    private val profile: ProfileStore,
    private val log: LogBuffer,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
    private val endTimeoutMs: Long = ManagedSession.endTimeoutMs(Hermes.GATEWAY_SIGNAL_WAIT_MS),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private var stopRequested = false

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Stopped())
    val state: StateFlow<GatewayState> = _state

    fun start(): Boolean {
        val since = synchronized(lock) {
            if (_state.value.active) return false
            stopRequested = false
            clock().also { _state.value = GatewayState.Starting(it) }
        }
        scope.launch {
            try {
                run(since)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessions.end(Hermes.GATEWAY_KEY)
                settle(GatewayState.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
        return true
    }

    fun stop(): Boolean {
        synchronized(lock) {
            val current = _state.value
            if (current !is GatewayState.Starting && current !is GatewayState.Running) return false
            stopRequested = true
            _state.value = GatewayState.Stopping
        }
        sessions.end(Hermes.GATEWAY_KEY)
        return true
    }

    private suspend fun run(since: Long) {
        if (io { probe.portInUse() }) return settle(GatewayState.Failed(PORT_IN_USE))
        val factory = try {
            io {
                profile.ensureProfile()
                launch.prepare()
            }
        } catch (e: Exception) {
            return settle(GatewayState.Failed(e.message ?: e.javaClass.simpleName))
        }
        if (stopWasRequested()) return settle(GatewayState.Stopped())
        log.mark(START_MARK)
        val session = open(factory) ?: return
        if (stopWasRequested()) session.end()
        follow(session, since)
    }

    private suspend fun open(factory: SessionManager.SessionFactory): ManagedSession? {
        var result = io { sessions.open(Hermes.GATEWAY_KEY, factory) }
        val previous = (result as? SessionManager.OpenResult.Ending)?.session
        if (previous != null) {
            val gone = withTimeoutOrNull(endTimeoutMs) { previous.exit.filterNotNull().first() }
            if (gone == null) return null.also { settle(GatewayState.Failed(CANNOT_STOP)) }
            if (stopWasRequested()) return null.also { settle(GatewayState.Stopped()) }
            result = io { sessions.open(Hermes.GATEWAY_KEY, factory) }
        }
        return when (result) {
            is SessionManager.OpenResult.Opened -> result.session
            is SessionManager.OpenResult.Ending -> null.also { settle(GatewayState.Failed(CANNOT_STOP)) }
            is SessionManager.OpenResult.Failed -> null.also { settle(GatewayState.Failed(result.reason)) }
        }
    }

    private suspend fun follow(session: ManagedSession, since: Long) = coroutineScope {
        val ready = launch {
            while (_state.value == GatewayState.Starting(since)) {
                val health = io { probe.health() }
                if (health != null) {
                    _state.compareAndSet(GatewayState.Starting(since), GatewayState.Running(since, health.version))
                    break
                }
                delay(pollMs)
            }
        }
        sessions.ending.combine(session.exit) { ending, exit -> exit != null || Hermes.GATEWAY_KEY in ending }
            .first { it }
        markStopping()
        val exit = withTimeoutOrNull(endTimeoutMs) { session.exit.filterNotNull().first() }
        ready.cancel()
        settle(
            when {
                exit == null -> GatewayState.Failed(CANNOT_STOP)
                session.endRequested -> GatewayState.Stopped(exit.code)
                else -> GatewayState.StoppedUnexpectedly(exit.code, log.tail(LAST_LINES))
            },
        )
    }

    private fun markStopping() {
        synchronized(lock) {
            val current = _state.value
            if (current is GatewayState.Starting || current is GatewayState.Running) _state.value = GatewayState.Stopping
        }
    }

    private fun stopWasRequested() = synchronized(lock) { stopRequested }

    private suspend fun <T> io(block: () -> T): T = withContext(dispatcher) { block() }

    private fun settle(state: GatewayState) {
        synchronized(lock) { _state.value = state }
    }

    companion object {
        const val POLL_MS = 1_000L
        const val LAST_LINES = 20
        const val START_MARK = "Starting the gateway"
        const val PORT_IN_USE = "something already answers on 127.0.0.1:8642, perhaps a gateway started " +
            "in another app; hr does not start a second one"
        const val CANNOT_STOP = "couldn't stop the gateway; it did not exit in time"
    }
}
