package id.or.oo.hr.image

import id.or.oo.hr.Hermes
import id.or.oo.hr.HrFiles
import id.or.oo.hr.setup.GatewayRestart
import id.or.oo.hr.writePrivate
import id.or.oo.pr.session.DistroCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

data class InstalledImage(val reference: String, val buildTag: String, val version: String, val installedAt: Long, val alias: String)

class ImageStore(
    private val command: DistroCommand,
    private val registry: ImageRegistry,
    private val files: HrFiles,
    private val scope: CoroutineScope,
    private val inUse: () -> Boolean,
    private val restart: GatewayRestart,
    private val gatewayActive: () -> Boolean,
    private val stopGateway: () -> Unit,
    private val sessionsBlocking: () -> Boolean,
    private val freeBytes: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind { Install, Remove, Update, SwitchBack }

    data class Operation(val kind: Kind, val lines: List<String>, val running: Boolean, val succeeded: Boolean = false)

    private data class Pending(val alias: String, val build: ImageBuild)

    private data class Older(val alias: String, val tag: String)

    private data class Record(val current: InstalledImage?, val previous: InstalledImage?, val pending: Pending?, val older: Older?)

    private val lock = Any()

    private var record = load()

    private var restarting = false

    private val _installed = MutableStateFlow(record.current)
    val installed: StateFlow<InstalledImage?> = _installed

    private val _previous = MutableStateFlow(record.previous)
    val previous: StateFlow<InstalledImage?> = _previous

    private val _interrupted = MutableStateFlow(interruptedOf(record))
    val interrupted: StateFlow<ImageBuild?> = _interrupted

    private val _operation = MutableStateFlow<Operation?>(null)
    val operation: StateFlow<Operation?> = _operation

    private val _containerPresent = MutableStateFlow(containerOnDisk())
    val containerPresent: StateFlow<Boolean> = _containerPresent

    fun currentAlias(): String? = _installed.value?.alias

    val containerExists: Boolean
        get() = _containerPresent.value

    val busy: Boolean
        get() = synchronized(lock) { _operation.value?.running == true }

    val canLaunch: Boolean
        get() = synchronized(lock) { _operation.value?.running != true || restarting }

    fun install(): Boolean = synchronized(lock) {
        if (record.current != null) return false
        start(Kind.Install) { installing() }
    }

    fun update(latest: ImageBuild): Boolean = synchronized(lock) {
        val current = record.current ?: return false
        if (latest.tag == current.buildTag) return false
        start(Kind.Update) { updating(latest) }
    }

    fun switchBack(): Boolean = synchronized(lock) {
        if (record.current == null || record.previous == null) return false
        start(Kind.SwitchBack) { switchingBack() }
    }

    fun removePrevious(): Boolean = synchronized(lock) {
        if (record.previous == null) return false
        start(Kind.Remove) { removingPrevious() }
    }

    fun removeInterrupted(): Boolean = synchronized(lock) {
        if (interruptedOf(record) == null) return false
        start(Kind.Remove) { removingInterrupted() }
    }

    fun remove(): Boolean = synchronized(lock) {
        if (_operation.value?.running == true) return false
        if (inUse()) {
            _operation.value = Operation(Kind.Remove, listOf(IN_USE), running = false)
            return false
        }
        start(Kind.Remove) { removing() }
    }

    fun dismiss(): Boolean = synchronized(lock) {
        if (_operation.value?.running == true) return false
        _operation.value = null
        true
    }

    fun refresh() {
        synchronized(lock) {
            if (_operation.value?.running == true) return
            record = load()
            publish()
        }
        _containerPresent.value = containerOnDisk()
    }

    private fun containerOnDisk(): Boolean = files.containerDir(snapshot().current?.alias ?: recordedAlias()).exists()

    private fun recordedAlias(): String = readRecord()?.getProperty(ALIAS) ?: Hermes.CONTAINER

    private fun start(kind: Kind, work: suspend () -> Boolean): Boolean {
        if (_operation.value?.running == true) return false
        _operation.value = Operation(kind, emptyList(), running = true)
        scope.launch {
            var succeeded = false
            try {
                succeeded = work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append("ERROR: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                publish()
                _containerPresent.value = containerOnDisk()
                _operation.update { it?.copy(running = false, succeeded = succeeded) }
            }
        }
        return true
    }

    private suspend fun installing(): Boolean {
        append("Resolving ${Hermes.REPOSITORY}:${Hermes.PULL_TAG}…")
        val build = withContext(Dispatchers.IO) { registry.resolve(Hermes.PULL_TAG) }
        val reference = "${Hermes.REPOSITORY}:${build.tag}"
        append("Installing $reference as ${Hermes.CONTAINER}…")
        val code = command.run(listOf("install", reference, "--override-alias", Hermes.CONTAINER), ::append)
        if (code != 0) {
            append("pr-cli exited with $code.")
            return false
        }
        val image = InstalledImage(reference, build.tag, build.version, clock(), Hermes.CONTAINER)
        commit(Record(image, null, null, null))
        publish()
        append("Installed ${build.tag}.")
        return true
    }

    private suspend fun updating(latest: ImageBuild): Boolean {
        if (!olderSettled()) return false
        if (blocked()) return false
        val free = freeBytes()
        if (free < MIN_FREE_BYTES) {
            append("Updating needs 2 GB free; ${free / MB} MB are free.")
            return false
        }
        val target = aliasFor(latest)
        val reference = "${Hermes.REPOSITORY}:${latest.tag}"
        val before = snapshot()
        val reusable = before.previous?.takeIf { it.alias == target && present(target) }
            ?: before.pending?.takeIf { it.alias == target && present(target) }
                ?.let { InstalledImage(reference, latest.tag, latest.version, clock(), target) }
        if (reusable != null) return switchTo(reusable, back = false)
        val older = before.pending?.takeIf { it.alias != target && it.alias != before.current?.alias && it.alias != before.previous?.alias }
        if (older != null && onDisk(older.alias) && !removed(older.alias)) return false
        commit(snapshot().copy(pending = Pending(target, latest)))
        _interrupted.value = null
        append("Installing $reference as $target…")
        val code = command.run(listOf("install", reference, "--override-alias", target), ::append)
        if (code != 0) {
            append("pr-cli exited with $code.")
            return false
        }
        return switchTo(InstalledImage(reference, latest.tag, latest.version, clock(), target), back = false)
    }

    private suspend fun switchingBack(): Boolean {
        if (!olderSettled()) return false
        if (blocked()) return false
        return switchTo(snapshot().previous ?: return false, back = true)
    }

    private fun blocked(): Boolean {
        if (!sessionsBlocking()) return false
        append(SESSIONS_BLOCKING)
        return true
    }

    private suspend fun switchTo(target: InstalledImage, back: Boolean): Boolean {
        val before = snapshot()
        val old = checkNotNull(before.current)
        val older = before.previous?.takeIf { it.alias != target.alias }?.let { Older(it.alias, it.buildTag) } ?: before.older
        val pending = before.pending?.takeIf { it.alias != target.alias }
        val wasActive = gatewayActive()
        commit(Record(target, old, pending, older))
        publish()
        val reason = restartGateway()
        val succeeded = if (reason == null) {
            if (!wasActive) stopGateway()
            append(
                if (back) {
                    "Switched back to ${target.buildTag}. The image ${old.buildTag} is kept until you remove it."
                } else {
                    "Updated to ${target.buildTag}. The previous image (${old.buildTag}) is kept until you remove it."
                },
            )
            true
        } else {
            commit(Record(old, target, pending, older))
            publish()
            val again = restartGateway()
            if (!wasActive) stopGateway()
            val line = "The new image did not start ($reason). hr is back on ${old.buildTag}."
            append(if (again == null) line else "$line The previous image did not start either ($again).")
            false
        }
        olderSettled()
        return succeeded
    }

    private suspend fun restartGateway(): String? {
        synchronized(lock) { restarting = true }
        try {
            return restart.restart()
        } finally {
            synchronized(lock) { restarting = false }
        }
    }

    private suspend fun olderSettled(): Boolean {
        val known = snapshot()
        val older = known.older ?: return true
        if (older.alias == known.current?.alias || older.alias == known.previous?.alias) {
            commit(known.copy(older = null))
            return true
        }
        if (onDisk(older.alias)) {
            append("Removing ${older.alias}…")
            val code = command.run(listOf("remove", older.alias), ::append)
            if (code != 0) {
                append("pr-cli exited with $code.")
                if (files.containerDir(older.alias).exists()) return false
            }
        }
        commit(snapshot().copy(older = null))
        append("Removed the older image (${older.tag}).")
        return true
    }

    private suspend fun removingPrevious(): Boolean {
        if (!olderSettled()) return false
        val previous = snapshot().previous ?: return false
        if (!removed(previous.alias)) return false
        commit(snapshot().copy(previous = null))
        append("Removed ${previous.buildTag}.")
        return true
    }

    private suspend fun removingInterrupted(): Boolean {
        val known = snapshot()
        val pending = known.pending?.takeIf { interruptedOf(known) != null } ?: return false
        if (onDisk(pending.alias) && !removed(pending.alias)) return false
        commit(snapshot().copy(pending = null))
        append("Removed ${pending.build.tag}.")
        return true
    }

    private suspend fun removed(alias: String): Boolean {
        append("Removing $alias…")
        val code = command.run(listOf("remove", alias), ::append)
        if (code != 0) {
            append("pr-cli exited with $code.")
            return false
        }
        return true
    }

    private suspend fun removing(): Boolean {
        val known = snapshot()
        val current = known.current?.alias ?: recordedAlias()
        val aliases = (listOfNotNull(known.pending?.alias, known.older?.alias, known.previous?.alias) + leftovers() + recordedAlias() + current)
            .distinct()
            .filter(::onDisk)
            .let { found -> (found - current) + listOfNotNull(current.takeIf { it in found }) }
            .ifEmpty { listOf(current) }
        for (alias in aliases) {
            if (!removed(alias)) {
                synchronized(lock) { record = load() }
                return false
            }
        }
        commit(EMPTY)
        append("Removed. The profile in hr's files is kept.")
        return true
    }

    private fun leftovers(): List<String> {
        val names = containersDir().list() ?: return emptyList()
        return names.mapNotNull { name ->
            val alias = name.removePrefix(".").removeSuffix(MARKER_SUFFIX)
            alias.takeIf { it == Hermes.CONTAINER || it.startsWith(ALIAS_PREFIX) }
        }
    }

    private fun containersDir(): File = files.containerDir(Hermes.CONTAINER).parentFile!!

    private fun marker(alias: String) = File(containersDir(), ".$alias$MARKER_SUFFIX")

    private fun onDisk(alias: String): Boolean = files.containerDir(alias).exists() || marker(alias).exists()

    private fun aliasFor(build: ImageBuild) = "$ALIAS_PREFIX${build.tag}"

    private fun append(line: String) {
        _operation.update { it?.copy(lines = (it.lines + line).takeLast(MAX_LINES)) }
    }

    private fun snapshot(): Record = synchronized(lock) { record }

    private fun publish() {
        val now = snapshot()
        _installed.value = now.current
        _previous.value = now.previous
        _interrupted.value = interruptedOf(now)
    }

    private fun interruptedOf(record: Record): ImageBuild? =
        record.pending?.takeIf { it.alias != record.current?.alias && it.alias != record.previous?.alias }?.build

    private fun load(): Record {
        val properties = readRecord() ?: return EMPTY
        val recorded = properties.image("", Hermes.CONTAINER)?.takeIf { present(it.alias) }
        val kept = properties.image(PREVIOUS, null)?.takeIf { present(it.alias) }
        val pending = properties.getProperty(PENDING + ALIAS)?.let { alias ->
            val tag = properties.getProperty(PENDING + BUILD_TAG) ?: return@let null
            Pending(alias, ImageBuild(tag, properties.getProperty(PENDING + VERSION).orEmpty()))
        }
        val older = properties.getProperty(OLDER + ALIAS)?.let { alias ->
            Older(alias, properties.getProperty(OLDER + BUILD_TAG) ?: return@let null)
        }
        val (current, previous) = if (recorded != null) recorded to kept else kept to null
        return Record(current, previous, pending, older)
    }

    private fun present(alias: String) = files.rootfs(alias).isDirectory && !marker(alias).exists()

    private fun Properties.image(prefix: String, defaultAlias: String?): InstalledImage? = InstalledImage(
        reference = getProperty(prefix + REFERENCE) ?: return null,
        buildTag = getProperty(prefix + BUILD_TAG) ?: return null,
        version = getProperty(prefix + VERSION) ?: return null,
        installedAt = getProperty(prefix + INSTALLED_AT)?.toLongOrNull() ?: return null,
        alias = getProperty(prefix + ALIAS) ?: defaultAlias ?: return null,
    )

    private fun readRecord(): Properties? {
        if (!files.imageRecord.isFile) return null
        return Properties().apply { load(StringReader(files.imageRecord.readText())) }
    }

    private fun commit(next: Record) = synchronized(lock) {
        if (next == EMPTY) {
            files.imageRecord.delete()
        } else {
            val properties = Properties().apply {
                next.current?.let { putImage("", it) }
                next.previous?.let { putImage(PREVIOUS, it) }
                next.pending?.let {
                    setProperty(PENDING + ALIAS, it.alias)
                    setProperty(PENDING + BUILD_TAG, it.build.tag)
                    setProperty(PENDING + VERSION, it.build.version)
                }
                next.older?.let {
                    setProperty(OLDER + ALIAS, it.alias)
                    setProperty(OLDER + BUILD_TAG, it.tag)
                }
            }
            val text = StringWriter().also { properties.store(it, null) }.toString()
            writePrivate(files.imageRecord, text)
        }
        record = next
    }

    private fun Properties.putImage(prefix: String, image: InstalledImage) {
        setProperty(prefix + REFERENCE, image.reference)
        setProperty(prefix + BUILD_TAG, image.buildTag)
        setProperty(prefix + VERSION, image.version)
        setProperty(prefix + INSTALLED_AT, image.installedAt.toString())
        setProperty(prefix + ALIAS, image.alias)
    }

    private companion object {
        val EMPTY = Record(null, null, null, null)
        const val MAX_LINES = 200
        const val IN_USE = "Stop the gateway and end the terminal before removing the image."
        const val SESSIONS_BLOCKING = "End the terminal and wait for the login to finish before updating."
        const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024
        const val MB = 1_048_576L
        const val ALIAS_PREFIX = "${Hermes.CONTAINER}-"
        const val MARKER_SUFFIX = ".pr-cli-installing"
        const val REFERENCE = "reference"
        const val BUILD_TAG = "build_tag"
        const val VERSION = "version"
        const val INSTALLED_AT = "installed_at"
        const val ALIAS = "alias"
        const val PREVIOUS = "previous."
        const val PENDING = "pending."
        const val OLDER = "older."
    }
}
