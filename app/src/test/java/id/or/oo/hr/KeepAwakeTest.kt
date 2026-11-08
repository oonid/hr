package id.or.oo.hr

import id.or.oo.pr.session.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class KeepAwakeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = MutableStateFlow<List<String>>(emptyList())
    private val awake = MutableStateFlow(false)
    private val calls = CopyOnWriteArrayList<Boolean>()
    private val store = object : KeepAwake.Store {
        override var remembered = false
    }

    private fun keepAwake() = KeepAwake(store, running, awake, { on -> calls += on; awake.value = on }, scope)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun theSwitchIsRememberedAndApplied() {
        val keep = keepAwake()
        running.value = listOf("hermes-gateway")
        keep.set(true)
        assertTrue(store.remembered)
        assertEquals(listOf(true), calls)
        keep.set(false)
        assertFalse(store.remembered)
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun aRememberedChoiceComesBackWhenSessionsRunAgain() {
        store.remembered = true
        keepAwake()
        running.value = listOf("hermes-gateway")
        eventually { awake.value }
        running.value = emptyList()
        awake.value = false
        running.value = listOf("hermes-gateway", "hermes")
        eventually { awake.value }
        assertEquals(listOf(true, true), calls)
    }

    @Test
    fun withoutARememberedChoiceNothingIsSwitchedOn() {
        keepAwake()
        running.value = listOf("hermes-gateway")
        Thread.sleep(200)
        assertTrue(calls.isEmpty())
        assertFalse(awake.value)
    }
}
