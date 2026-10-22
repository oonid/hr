package id.or.oo.hr.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayTextTest {

    @Test
    fun durationsReadInTheirLargestUnits() {
        assertEquals("0 s", formatDuration(-5))
        assertEquals("27 s", formatDuration(27_400))
        assertEquals("3 min 5 s", formatDuration(185_000))
        assertEquals("2 h 4 min", formatDuration(7_440_000))
        assertEquals("3 d 1 h", formatDuration(262_800_000))
    }

    @Test
    fun aStopAfterEndReadsAsStoppedWhateverTheGatewaysExitCode() {
        assertEquals("Stopped", describe(GatewayState.Stopped(), 0))
        assertEquals("Stopped", describe(GatewayState.Stopped(1), 0))
        assertEquals("Stopped", describe(GatewayState.Stopped(0), 0))
        assertEquals("Stopped", describe(GatewayState.Stopped(143), 0))
        assertEquals("Stopped (exit 2)", describe(GatewayState.Stopped(2), 0))
    }

    @Test
    fun aKilledGatewayIsCalledOut() {
        assertEquals("Stopped. The gateway did not exit after SIGTERM and was killed.", describe(GatewayState.Stopped(137), 0))
    }

    @Test
    fun startingAndRunningCountFromTheStart() {
        assertEquals("Starting… 12 s", describe(GatewayState.Starting(1_000), 13_000))
        assertEquals("Running for 1 h 0 min", describe(GatewayState.Running(0, "v0.22.3"), 3_600_000))
    }

    @Test
    fun theOtherStatesSayWhatHappened() {
        assertEquals(
            "Stopping… Hermes finishes its running work first, which can take up to a minute.",
            describe(GatewayState.Stopping, 0),
        )
        assertEquals("Stopped unexpectedly (exit 78)", describe(GatewayState.StoppedUnexpectedly(78, emptyList()), 0))
        assertEquals("Stopped unexpectedly (exit unknown)", describe(GatewayState.StoppedUnexpectedly(null, emptyList()), 0))
        assertEquals("Couldn't start: no pr-cli", describe(GatewayState.Failed("no pr-cli"), 0))
    }
}
