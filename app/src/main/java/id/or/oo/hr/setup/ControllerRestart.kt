package id.or.oo.hr.setup

import id.or.oo.hr.gateway.GatewayState
import id.or.oo.hr.gateway.active
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class ControllerRestart(
    private val state: StateFlow<GatewayState>,
    private val stop: () -> Boolean,
    private val start: () -> Boolean,
    private val stopMs: Long = STOP_MS,
    private val startMs: Long = START_MS,
) : GatewayRestart {

    override suspend fun restart(): String? {
        stop()
        withTimeoutOrNull(stopMs) { state.first { !it.active } }
            ?: return "the gateway did not stop within ${seconds(stopMs)} s"
        if (!start()) {
            val now = state.value
            if (!now.active) return reason(now)
        }
        val settled = withTimeoutOrNull(startMs) { state.first { it is GatewayState.Running || !it.active } }
            ?: return "the gateway did not start within ${seconds(startMs)} s"
        return if (settled is GatewayState.Running) null else reason(settled)
    }

    private fun reason(state: GatewayState): String = when (state) {
        is GatewayState.Failed -> state.reason
        is GatewayState.StoppedUnexpectedly ->
            state.exitCode?.let { "the gateway stopped with exit code $it" } ?: "the gateway stopped unexpectedly"
        else -> "the gateway stopped"
    }

    private fun seconds(ms: Long) = (ms + 999) / 1000

    private companion object {
        const val STOP_MS = 65_000L
        const val START_MS = 120_000L
    }
}
