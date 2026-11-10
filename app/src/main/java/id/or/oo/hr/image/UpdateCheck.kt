package id.or.oo.hr.image

import id.or.oo.hr.Hermes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val build: String) : UpdateState
    data class Available(val installed: String, val latest: ImageBuild) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

class UpdateCheck(
    private val registry: ImageRegistry,
    private val installed: StateFlow<InstalledImage?>,
    private val store: Store,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    interface Store {
        var lastCheckedAt: Long
        var lastLatestTag: String?
        var lastLatestVersion: String?
    }

    private val lock = Any()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state

    init {
        scope.launch {
            installed.collect { image ->
                synchronized(lock) {
                    if (_state.value != UpdateState.Checking) _state.value = stored(image)
                }
            }
        }
    }

    fun check(force: Boolean) {
        val image: InstalledImage
        synchronized(lock) {
            if (_state.value == UpdateState.Checking) return
            image = installed.value ?: return
            if (!force && clock() - store.lastCheckedAt < DAY_MS) {
                _state.value = stored(image)
                return
            }
            _state.value = UpdateState.Checking
        }
        scope.launch {
            val outcome = runCatching { withContext(io) { registry.resolve(Hermes.PULL_TAG) } }
            synchronized(lock) {
                outcome.onSuccess { latest ->
                    store.lastLatestTag = latest.tag
                    store.lastLatestVersion = latest.version
                    store.lastCheckedAt = clock()
                }
                _state.value = outcome.fold(
                    { stored(installed.value) },
                    { UpdateState.Failed(it.message ?: it::class.java.simpleName) },
                )
            }
        }
    }

    private fun stored(image: InstalledImage?): UpdateState {
        val tag = store.lastLatestTag
        if (image == null || tag == null) return UpdateState.Unknown
        if (tag == image.buildTag) return UpdateState.UpToDate(image.buildTag)
        return UpdateState.Available(image.buildTag, ImageBuild(tag, store.lastLatestVersion.orEmpty()))
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
