package id.or.oo.hr.profile

import id.or.oo.hr.Hermes
import id.or.oo.hr.HrFiles
import id.or.oo.hr.writePrivate
import id.or.oo.pr.session.Bind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

class ProfileStore(private val files: HrFiles, private val random: SecureRandom = SecureRandom()) {

    private val lock = Any()
    private var key: String? = null
    private var envStamp: Pair<Long, Long>? = null
    private var envSecrets: List<String> = emptyList()
    private var authStamp: Pair<Long, Long>? = null
    private var authSecrets: List<String> = emptyList()

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
        val auth = File(files.profileDir, "auth.json")
        val authNow = if (auth.isFile) auth.lastModified() to auth.length() else null
        if (authNow != authStamp || authNow == null) {
            authStamp = authNow
            authSecrets = if (authNow == null) emptyList() else loginTokens(auth)
        }
        (listOf(apiServerKey()) + envSecrets + authSecrets).distinct()
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

    fun removeFromProfileEnv(names: Set<String>) {
        synchronized(lock) {
            val env = File(files.profileDir, ".env")
            if (!env.isFile) return
            writePrivate(env, withoutEnvKeys(env.readText(), names))
        }
    }

    fun hasProvider(): Boolean = !modelSettings(File(files.profileDir, CONFIG))["provider"].isNullOrEmpty()

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
        parseEnv(text).mapNotNull { (name, value) ->
            value.takeIf { it.isNotEmpty() && SECRET_SUFFIXES.any { suffix -> name.endsWith(suffix) } }
        }

    private fun loginTokens(auth: File): List<String> {
        val found = linkedSetOf<String>()
        try {
            collectTokens(JSONObject(auth.readText()), found)
        } catch (e: Exception) {
            return emptyList()
        }
        return found.toList()
    }

    private fun collectTokens(node: Any?, into: MutableSet<String>) {
        when (node) {
            is JSONObject -> node.keys().forEach { name ->
                val value = node.opt(name)
                if (name in TOKEN_KEYS && value is String && value.isNotBlank()) into.add(value)
                collectTokens(value, into)
            }
            is JSONArray -> for (i in 0 until node.length()) collectTokens(node.opt(i), into)
        }
    }

    private companion object {
        val TOKEN_KEYS = setOf("access_token", "refresh_token", "agent_key", "api_key")
        val KEY_FORMAT = Regex("[0-9a-f]{64}")
        val SECRET_SUFFIXES = listOf("_KEY", "_TOKEN", "_PASSWORD", "_SECRET")
    }
}
