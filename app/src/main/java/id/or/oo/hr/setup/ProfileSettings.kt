package id.or.oo.hr.setup

import id.or.oo.hr.profile.ProfileStore
import kotlinx.coroutines.CancellationException

private const val CUSTOM = "custom"

private val SAFE_VALUE = Regex("^[A-Za-z0-9._:/@+][A-Za-z0-9._:/@+-]*$")

class ProfileSettings(private val command: HermesCommand, internal val profiles: ProfileStore) {

    suspend fun useModel(provider: String, model: String, baseUrl: String? = null): Result<Unit> {
        checkValues(provider, model, baseUrl)?.let { return Result.failure(it) }
        val settings = listOfNotNull(
            "provider" to provider,
            "default" to model,
            baseUrl?.let { "base_url" to it },
        )
        val tail = if (provider == CUSTOM) {
            " && hermes config set model.api_key '\${HERMES_CUSTOM_API_KEY}'"
        } else {
            " && { hermes config unset model.base_url >/dev/null 2>&1;" +
                " hermes config unset model.api_key >/dev/null 2>&1; true; }"
        }
        val script = settings.joinToString(" && ") { (key, value) -> "hermes config set model.$key $value" } + tail
        return try {
            var last: String? = null
            val exit = command.run(script) { line -> if (line.isNotBlank()) last = line }
            if (exit != 0) {
                Result.failure(IllegalStateException(last ?: "hermes config set exited with code $exit"))
            } else {
                profiles.writeProfileEnv(emptyMap())
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun checkValues(provider: String, model: String, baseUrl: String?): Throwable? =
        listOfNotNull("provider" to provider, "default" to model, baseUrl?.let { "base_url" to it })
            .firstOrNull { (_, value) -> !SAFE_VALUE.matches(value) }
            ?.let { (key, _) -> IllegalArgumentException("the model $key has characters that are not allowed") }
}
