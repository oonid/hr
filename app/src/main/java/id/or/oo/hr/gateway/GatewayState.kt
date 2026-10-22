package id.or.oo.hr.gateway

sealed interface GatewayState {
    data class Stopped(val exitCode: Int? = null) : GatewayState
    data class Starting(val since: Long) : GatewayState
    data class Running(val since: Long, val version: String?) : GatewayState
    data object Stopping : GatewayState
    data class StoppedUnexpectedly(val exitCode: Int?, val lastLines: List<String>) : GatewayState
    data class Failed(val reason: String) : GatewayState
}

val GatewayState.active: Boolean
    get() = this is GatewayState.Starting || this is GatewayState.Running || this == GatewayState.Stopping
