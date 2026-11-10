package id.or.oo.hr.image

import id.or.oo.hr.Hermes
import id.or.oo.hr.HrFiles
import id.or.oo.hr.writePrivate
import id.or.oo.pr.session.DistroCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind { Install, Remove }

    data class Operation(val kind: Kind, val lines: List<String>, val running: Boolean, val succeeded: Boolean = false)

    private val lock = Any()

    private val _installed = MutableStateFlow(load())
    val installed: StateFlow<InstalledImage?> = _installed

    private val _operation = MutableStateFlow<Operation?>(null)
    val operation: StateFlow<Operation?> = _operation

    private val _containerPresent = MutableStateFlow(containerOnDisk())
    val containerPresent: StateFlow<Boolean> = _containerPresent

    fun currentAlias(): String? = _installed.value?.alias

    val containerExists: Boolean
        get() = _containerPresent.value

    val busy: Boolean
        get() = synchronized(lock) { _operation.value?.running == true }

    fun install(): Boolean = synchronized(lock) {
        if (_installed.value != null) return false
        start(Kind.Install) { installing() }
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
        _installed.value = load()
        _containerPresent.value = containerOnDisk()
    }

    private fun containerOnDisk(): Boolean = files.containerDir(recordedAlias()).exists()

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
        save(image)
        _installed.value = image
        append("Installed ${build.tag}.")
        return true
    }

    private suspend fun removing(): Boolean {
        val alias = recordedAlias()
        append("Removing $alias…")
        val code = command.run(listOf("remove", alias), ::append)
        if (code != 0) {
            append("pr-cli exited with $code.")
            return false
        }
        files.imageRecord.delete()
        _installed.value = null
        append("Removed. The profile in hr's files is kept.")
        return true
    }

    private fun append(line: String) {
        _operation.update { it?.copy(lines = (it.lines + line).takeLast(MAX_LINES)) }
    }

    private fun load(): InstalledImage? {
        val properties = readRecord() ?: return null
        val alias = properties.getProperty(ALIAS) ?: Hermes.CONTAINER
        if (!files.rootfs(alias).isDirectory) return null
        return InstalledImage(
            reference = properties.getProperty(REFERENCE) ?: return null,
            buildTag = properties.getProperty(BUILD_TAG) ?: return null,
            version = properties.getProperty(VERSION) ?: return null,
            installedAt = properties.getProperty(INSTALLED_AT)?.toLongOrNull() ?: return null,
            alias = alias,
        )
    }

    private fun readRecord(): Properties? {
        if (!files.imageRecord.isFile) return null
        return Properties().apply { load(StringReader(files.imageRecord.readText())) }
    }

    private fun save(image: InstalledImage) {
        val properties = Properties().apply {
            setProperty(REFERENCE, image.reference)
            setProperty(BUILD_TAG, image.buildTag)
            setProperty(VERSION, image.version)
            setProperty(INSTALLED_AT, image.installedAt.toString())
            setProperty(ALIAS, image.alias)
        }
        val text = StringWriter().also { properties.store(it, null) }.toString()
        writePrivate(files.imageRecord, text)
    }

    private companion object {
        const val MAX_LINES = 200
        const val IN_USE = "Stop the gateway and end the terminal before removing the image."
        const val REFERENCE = "reference"
        const val BUILD_TAG = "build_tag"
        const val VERSION = "version"
        const val INSTALLED_AT = "installed_at"
        const val ALIAS = "alias"
    }
}
