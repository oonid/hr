package id.or.oo.hr.gateway

import id.or.oo.hr.Hermes
import id.or.oo.hr.profile.ProfileStore
import id.or.oo.pr.session.ManagedSession
import id.or.oo.pr.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        scope.launch { run(since) }
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
        if (probe.portInUse()) return settle(GatewayState.Failed(PORT_IN_USE))
        val factory = try {
            profile.ensureProfile()
            launch.prepare()
        } catch (e: Exception) {
            return settle(GatewayState.Failed(e.message ?: e.javaClass.simpleName))
        }
        if (synchronized(lock) { stopRequested }) return settle(GatewayState.Stopped())
        log.mark(START_MARK)
        val session = when (val result = sessions.open(Hermes.GATEWAY_KEY, factory)) {
            is SessionManager.OpenResult.Failed -> return settle(GatewayState.Failed(result.reason))
            is SessionManager.OpenResult.Ending -> return settle(GatewayState.Failed(STILL_ENDING))
            is SessionManager.OpenResult.Opened -> result.session
        }
        if (synchronized(lock) { stopRequested }) session.end()
        follow(session, since)
    }

    private suspend fun follow(session: ManagedSession, since: Long) = coroutineScope {
        val ready = launch {
            while (isActive) {
                val health = probe.health()
                if (health != null) {
                    _state.compareAndSet(GatewayState.Starting(since), GatewayState.Running(since, health.version))
                    break
                }
                delay(pollMs)
            }
        }
        val exit = session.exit.filterNotNull().first()
        ready.cancel()
        settle(
            if (session.endRequested) GatewayState.Stopped(exit.code)
            else GatewayState.StoppedUnexpectedly(exit.code, log.tail(LAST_LINES)),
        )
    }

    private fun settle(state: GatewayState) {
        synchronized(lock) { _state.value = state }
    }

    companion object {
        const val POLL_MS = 1_000L
        const val LAST_LINES = 20
        const val START_MARK = "Starting the gateway"
        const val PORT_IN_USE = "something already answers on 127.0.0.1:8642, perhaps a gateway started " +
            "in another app; hr does not start a second one"
        const val STILL_ENDING = "the previous gateway is still stopping; try again when it has stopped"
    }
}
