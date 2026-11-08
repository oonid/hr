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

    fun ensureProfile(): File {
        val dir = files.profileDir
        check(dir.isDirectory || dir.mkdirs()) { "could not create the profile directory $dir" }
        return dir
    }

    fun bind(): Bind = Bind(files.profileDir.absolutePath, Hermes.PROFILE_GUEST)

    fun apiServerKey(): String = synchronized(lock) {
        key ?: (readKey() ?: newKey()).also { key = it }
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

    private companion object {
        val KEY_FORMAT = Regex("[0-9a-f]{64}")
    }
}
