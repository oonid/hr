package id.or.oo.hr.image

import id.or.oo.hr.gateway.GatewayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageExclusionTest {

    private val running = ImageStore.Operation(ImageStore.Kind.Remove, emptyList(), running = true)
    private val finished = running.copy(running = false)

    @Test
    fun theImageIsInUseWhileASessionRuns() {
        assertTrue(imageInUse(sessionsRunning = true, gateway = GatewayState.Stopped()))
    }

    @Test
    fun theImageIsInUseWhileTheGatewayIsActiveEvenWithoutASession() {
        val states = listOf(GatewayState.Starting(1), GatewayState.Running(1, null), GatewayState.Stopping)
        states.forEach { assertTrue("$it", imageInUse(sessionsRunning = false, gateway = it)) }
    }

    @Test
    fun theImageIsFreeWhenNothingRunsAndTheGatewayIsSettled() {
        val states = listOf(GatewayState.Stopped(), GatewayState.StoppedUnexpectedly(1, emptyList()), GatewayState.Failed("x"))
        states.forEach { assertFalse("$it", imageInUse(sessionsRunning = false, gateway = it)) }
    }

    @Test
    fun removeNeedsAFreeImageAndNoOperation() {
        assertTrue(canRemoveImage(inUse = false, operation = null))
        assertTrue(canRemoveImage(inUse = false, operation = finished))
        assertFalse(canRemoveImage(inUse = true, operation = null))
        assertFalse(canRemoveImage(inUse = false, operation = running))
    }

    @Test
    fun sessionsCannotStartWhileAnOperationRuns() {
        assertTrue(canStartSession(null))
        assertTrue(canStartSession(finished))
        assertFalse(canStartSession(running))
    }

    @Test
    fun grantWatcherReportsOnlyNewGrants() {
        val watcher = GrantWatcher(notifications = false, exempt = false)
        assertFalse(watcher.update(notifications = false, exempt = false))
        assertTrue(watcher.update(notifications = true, exempt = false))
        assertFalse(watcher.update(notifications = true, exempt = false))
        assertTrue(watcher.update(notifications = true, exempt = true))
        assertFalse(watcher.update(notifications = false, exempt = true))
        assertTrue(watcher.update(notifications = true, exempt = true))
    }

    @Test
    fun grantWatcherStartsFromTheValuesItWasGiven() {
        val watcher = GrantWatcher(notifications = true, exempt = true)
        assertFalse(watcher.update(notifications = true, exempt = true))
        assertEquals(false, watcher.update(notifications = true, exempt = true))
    }
}
