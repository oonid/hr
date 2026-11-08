package id.or.oo.hr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class KeepAwake(
    private val store: Store,
    running: StateFlow<List<String>>,
    awake: StateFlow<Boolean>,
    private val apply: (Boolean) -> Unit,
    scope: CoroutineScope,
) {
    interface Store {
        var remembered: Boolean
    }

    init {
        scope.launch {
            running.collect { names ->
                if (names.isNotEmpty() && store.remembered && !awake.value) apply(true)
            }
        }
    }

    fun set(on: Boolean) {
        store.remembered = on
        apply(on)
    }
}
