package id.or.oo.hr.setup

import id.or.oo.hr.gateway.GatewayController
import id.or.oo.hr.gateway.GatewayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections

class ControllerRestartTest {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val state = MutableStateFlow<GatewayState>(GatewayState.Running(1, "v"))
    private val calls = Collections.synchronizedList(mutableListOf<String>())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun stopsThen(next: GatewayState): () -> Boolean = {
        calls += "stop"
        state.value = GatewayState.Stopping
        scope.launch { state.value = next }
        true
    }

    private fun startsThen(next: GatewayState?): () -> Boolean = {
        calls += "start"
        state.value = GatewayState.Starting(2)
        if (next != null) scope.launch { state.value = next }
        true
    }

    private fun restart(
        stop: () -> Boolean = stopsThen(GatewayState.Stopped(0)),
        start: () -> Boolean,
        stopMs: Long = 5_000,
        startMs: Long = 5_000,
    ) = runBlocking { ControllerRestart(state, stop, start, stopMs, startMs).restart() }

    @Test
    fun restartsAndWaitsForRunning() {
        val reason = restart(start = startsThen(GatewayState.Running(2, "v")))

        assertNull(reason)
        assertEquals(listOf("stop", "start"), calls)
        assertEquals(GatewayState.Running(2, "v"), state.value)
    }

    @Test
    fun aFailedStartGivesItsReason() {
        val reason = restart(start = startsThen(GatewayState.Failed(GatewayController.PORT_IN_USE)))

        assertEquals(GatewayController.PORT_IN_USE, reason)
    }

    @Test
    fun aStartThatNeverRunsTimesOut() {
        val reason = restart(start = startsThen(null), startMs = 50)

        assertEquals("the gateway did not start within 1 s", reason)
    }

    @Test
    fun aStopThatNeverEndsTimesOut() {
        val reason = restart(stop = stopsThen(GatewayState.Stopping), start = startsThen(null), stopMs = 50)

        assertEquals("the gateway did not stop within 1 s", reason)
        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun anUnexpectedStopNamesTheExitCode() {
        val reason = restart(start = startsThen(GatewayState.StoppedUnexpectedly(3, listOf("boom"))))

        assertEquals("the gateway stopped with exit code 3", reason)
    }

    @Test
    fun anUnexpectedStopWithoutACodeSaysSo() {
        val reason = restart(start = startsThen(GatewayState.StoppedUnexpectedly(null, emptyList())))

        assertEquals("the gateway stopped unexpectedly", reason)
    }

    @Test
    fun aStoppedGatewayIsStartedWithoutWaitingForAStop() {
        state.value = GatewayState.Stopped(0)
        val reason = restart(
            stop = { calls += "stop"; false },
            start = startsThen(GatewayState.Running(2, "v")),
            stopMs = 50,
        )

        assertNull(reason)
        assertEquals(listOf("stop", "start"), calls)
    }

    @Test
    fun aRefusedStartNamesTheState() {
        val reason = restart(start = { calls += "start"; false })

        assertEquals("the gateway stopped", reason)
    }

    @Test
    fun aRefusedStartWhileAnotherStartRunsWaitsForIt() {
        val reason = restart(
            start = {
                state.value = GatewayState.Starting(3)
                scope.launch { state.value = GatewayState.Running(3, "v") }
                false
            },
        )

        assertNull(reason)
    }
}
