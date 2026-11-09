package id.or.oo.hr.profile

import id.or.oo.hr.setup.ApiProvider
import org.json.JSONObject
import java.io.File
import java.net.URI

data class SetupStatus(
    val chatGpt: Boolean,
    val provider: String?,
    val model: String?,
    val baseUrlHost: String?,
    val savedKeys: Set<String>,
    val telegramToken: Boolean,
    val telegramUsers: Int,
)

fun readStatus(profileDir: File): SetupStatus {
    val env = attempt { parseEnv(File(profileDir, ".env").readText()).toMap() }.orEmpty()
    val model = attempt { modelSettings(File(profileDir, CONFIG)) }.orEmpty()
    return SetupStatus(
        chatGpt = hasChatGptLogin(File(profileDir, "auth.json")),
        provider = model["provider"],
        model = model["default"],
        baseUrlHost = model["base_url"]?.let { attempt { URI(it).host } },
        savedKeys = ApiProvider.entries.map { it.keyVariable }.filter { !env[it].isNullOrBlank() }.toSet(),
        telegramToken = !env["TELEGRAM_BOT_TOKEN"].isNullOrBlank(),
        telegramUsers = env["TELEGRAM_ALLOWED_USERS"].orEmpty().split(',').count { it.isNotBlank() },
    )
}

internal const val CONFIG = "config.yaml"

internal fun modelSettings(config: File): Map<String, String> {
    if (!config.isFile) return emptyMap()
    val found = linkedMapOf<String, String>()
    var inModel = false
    var childIndent = -1
    for (raw in attempt { config.readLines() }.orEmpty()) {
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
        val name = entry.substringBefore(':').trim()
        val value = entry.substringAfter(':', "").trim().removeSurrounding("\"").removeSurrounding("'")
        if (value.isNotEmpty() && name !in found) found[name] = value
    }
    return found
}

private fun hasChatGptLogin(auth: File): Boolean = attempt {
    val root = JSONObject(auth.readText())
    val pool = root.optJSONObject("credential_pool")?.optJSONArray(CODEX)
    pool != null && pool.length() > 0 || root.optJSONObject("providers")?.optJSONObject(CODEX) != null
} ?: false

private const val CODEX = "openai-codex"

private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (e: Exception) {
    null
}
