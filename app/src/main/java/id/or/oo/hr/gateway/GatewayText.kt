package id.or.oo.hr.gateway

fun formatDuration(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3_600 -> "${seconds / 60} min ${seconds % 60} s"
        seconds < 86_400 -> "${seconds / 3_600} h ${seconds % 3_600 / 60} min"
        else -> "${seconds / 86_400} d ${seconds % 86_400 / 3_600} h"
    }
}

fun describe(state: GatewayState, now: Long): String = when (state) {
    is GatewayState.Stopped -> when (state.exitCode) {
        null, 0, 1, 143 -> "Stopped"
        137 -> "Stopped. The gateway did not exit after SIGTERM and was killed."
        else -> "Stopped (exit ${state.exitCode})"
    }
    is GatewayState.Starting -> "Starting… ${formatDuration(now - state.since)}"
    is GatewayState.Running -> "Running for ${formatDuration(now - state.since)}"
    GatewayState.Stopping -> "Stopping… Hermes finishes its running work first, which can take up to a minute."
    is GatewayState.StoppedUnexpectedly -> "Stopped unexpectedly (exit ${state.exitCode ?: "unknown"})"
    is GatewayState.Failed -> "Couldn't start: ${state.reason}"
}
