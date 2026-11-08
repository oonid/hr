package id.or.oo.hr.profile

import id.or.oo.hr.Hermes
import id.or.oo.hr.HrFiles
import id.or.oo.hr.writePrivate
import id.or.oo.pr.session.Bind
import java.io.File
import java.security.SecureRandom

class ProfileStore(private val files: HrFiles, private val random: SecureRandom = SecureRandom()) {

    private val lock = Any()
    private var key: String? = null
    private var envStamp: Pair<Long, Long>? = null
    private var envSecrets: List<String> = emptyList()

    fun ensureProfile(): File {
        val dir = files.profileDir
        check(dir.isDirectory || dir.mkdirs()) { "could not create the profile directory $dir" }
        return dir
    }

    fun bind(): Bind = Bind(files.profileDir.absolutePath, Hermes.PROFILE_GUEST)

    fun apiServerKey(): String = synchronized(lock) {
        key ?: (readKey() ?: newKey()).also { key = it }
    }

    fun secrets(): List<String> = synchronized(lock) {
        val env = File(files.profileDir, ".env")
        val stamp = if (env.isFile) env.lastModified() to env.length() else null
        if (stamp != envStamp || stamp == null) {
            envStamp = stamp
            envSecrets = if (stamp == null) emptyList() else secretValues(env.readText())
        }
        listOf(apiServerKey()) + envSecrets
    }

    fun writeRootEnv(rootfs: File) {
        check(rootfs.isDirectory) { "the Hermes container has no root at $rootfs" }
        val env = File(rootfs, Hermes.ROOT_ENV)
        val existing = if (env.isFile) env.readText() else ""
        writePrivate(env, mergeEnv(existing, listenerSettings()))
    }

    fun writeProfileEnv(values: Map<String, String>) {
        val dir = ensureProfile()
        synchronized(lock) {
            val env = File(dir, ".env")
            val existing = if (env.isFile) env.readText() else ""
            writePrivate(env, mergeEnv(existing, values + ("API_SERVER_KEY" to apiServerKey())))
        }
    }

    fun hasProvider(): Boolean {
        val config = File(files.profileDir, CONFIG)
        if (!config.isFile) return false
        var inModel = false
        var childIndent = -1
        for (raw in config.readLines()) {
            val line = raw.substringBefore(" #").trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val indent = line.length - line.trimStart().length
            if (indent == 0) {
                inModel = line == "model:"
                childIndent = -1
                continue
            }
            if (!inModel) continue
            if (childIndent < 0) childIndent = indent
            if (indent != childIndent) continue
            val entry = line.trim()
            if (!entry.startsWith("provider:")) continue
            val value = entry.removePrefix("provider:").trim().removeSurrounding("\"").removeSurrounding("'")
            if (value.isNotEmpty()) return true
        }
        return false
    }

    private fun listenerSettings(): Map<String, String> = linkedMapOf(
        "API_SERVER_ENABLED" to "true",
        "API_SERVER_KEY" to apiServerKey(),
        "API_SERVER_HOST" to Hermes.HOST,
        "API_SERVER_PORT" to Hermes.PORT.toString(),
    )

    private fun readKey(): String? =
        files.keyFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { KEY_FORMAT.matches(it) }

    private fun newKey(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        writePrivate(files.keyFile, generated)
        return generated
    }

    private fun secretValues(text: String): List<String> =
        text.replace("\r", "").split('\n').mapNotNull { line ->
            val assignment = line.trim().removePrefix("export ").trimStart()
            val name = assignment.substringBefore('=').trim()
            val value = assignment.substringAfter('=', "").trim().trim('"', '\'')
            value.takeIf { it.isNotEmpty() && SECRET_SUFFIXES.any { name.endsWith(it) } }
        }

    private companion object {
        val KEY_FORMAT = Regex("[0-9a-f]{64}")
        const val CONFIG = "config.yaml"
        val SECRET_SUFFIXES = listOf("_KEY", "_TOKEN", "_PASSWORD", "_SECRET")
    }
}
