package id.or.oo.hr.image

import id.or.oo.hr.Hermes
import id.or.oo.hr.HrFiles
import id.or.oo.hr.setup.GatewayRestart
import id.or.oo.hr.writePrivate
import id.or.oo.pr.session.DistroCommand
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

    private data class Record(val current: InstalledImage?, val previous: InstalledImage?, val pending: Pending?)

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
            val succeeded = try {
                work()
            } catch (e: Exception) {
                append("ERROR: ${e.message ?: e.javaClass.simpleName}")
                false
            }
            publish()
            _containerPresent.value = containerOnDisk()
            _operation.update { it?.copy(running = false, succeeded = succeeded) }
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
        commit(snapshot().copy(current = image))
        publish()
        append("Installed ${build.tag}.")
        return true
    }

    private suspend fun updating(latest: ImageBuild): Boolean {
        if (blocked()) return false
        val free = freeBytes()
        if (free < MIN_FREE_BYTES) {
            append("Updating needs 2 GB free; ${free / MB} MB are free.")
            return false
        }
        val target = aliasFor(latest)
        val reference = "${Hermes.REPOSITORY}:${latest.tag}"
        val before = snapshot()
        val reusable = before.previous?.takeIf { it.alias == target && files.rootfs(target).isDirectory }
            ?: before.pending?.takeIf { it.alias == target && files.rootfs(target).isDirectory && !marker(target).exists() }
                ?.let { InstalledImage(reference, latest.tag, latest.version, clock(), target) }
        if (reusable != null) return switchTo(reusable)
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
        return switchTo(InstalledImage(reference, latest.tag, latest.version, clock(), target))
    }

    private suspend fun switchingBack(): Boolean {
        if (blocked()) return false
        return switchTo(snapshot().previous ?: return false)
    }

    private fun blocked(): Boolean {
        if (!sessionsBlocking()) return false
        append(SESSIONS_BLOCKING)
        return true
    }

    private suspend fun switchTo(target: InstalledImage): Boolean {
        val before = snapshot()
        val old = checkNotNull(before.current)
        val older = before.previous?.takeIf { it.alias != target.alias }
        val pending = before.pending?.takeIf { it.alias != target.alias }
        val wasActive = gatewayActive()
        commit(Record(target, old, pending))
        publish()
        val reason = restartGateway()
        val succeeded = if (reason == null) {
            if (!wasActive) stopGateway()
            append("Updated to ${target.buildTag}. The previous image (${old.buildTag}) is kept until you remove it.")
            true
        } else {
            commit(Record(old, target, pending))
            publish()
            val again = restartGateway()
            if (again == null && !wasActive) stopGateway()
            val back = "The new image did not start ($reason). hr is back on ${old.buildTag}."
            append(if (again == null) back else "$back The previous image did not start either ($again).")
            false
        }
        if (older != null) removeOlder(older)
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

    private suspend fun removeOlder(older: InstalledImage) {
        if (onDisk(older.alias)) {
            val code = command.run(listOf("remove", older.alias), ::append)
            if (code != 0) {
                append("pr-cli exited with $code.")
                return
            }
        }
        append("Removed the older image (${older.buildTag}).")
    }

    private suspend fun removingPrevious(): Boolean {
        val previous = snapshot().previous ?: return false
        if (!removed(previous.alias)) return false
        commit(snapshot().copy(previous = null))
        return true
    }

    private suspend fun removingInterrupted(): Boolean {
        val known = snapshot()
        val pending = known.pending?.takeIf { interruptedOf(known) != null } ?: return false
        if (onDisk(pending.alias) && !removed(pending.alias)) return false
        commit(snapshot().copy(pending = null))
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
        val aliases = (listOfNotNull(known.pending?.alias, known.previous?.alias) + leftovers() + recordedAlias() + current)
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
        commit(Record(null, null, null))
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
        val properties = readRecord() ?: return Record(null, null, null)
        val recorded = properties.image("", Hermes.CONTAINER)?.takeIf(::present)
        val kept = properties.image(PREVIOUS, null)?.takeIf(::present)
        val pending = properties.getProperty(PENDING + ALIAS)?.let { alias ->
            val tag = properties.getProperty(PENDING + BUILD_TAG) ?: return@let null
            Pending(alias, ImageBuild(tag, properties.getProperty(PENDING + VERSION).orEmpty()))
        }
        return if (recorded != null) Record(recorded, kept, pending) else Record(kept, null, pending)
    }

    private fun present(image: InstalledImage) = files.rootfs(image.alias).isDirectory

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
        if (next == Record(null, null, null)) {
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
    }
}
