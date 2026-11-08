package id.or.oo.hr.setup

import id.or.oo.hr.profile.ProfileStore
import kotlinx.coroutines.CancellationException

private val SAFE_VALUE = Regex("^[A-Za-z0-9._:/@+-]+$")

class ProfileSettings(private val command: HermesCommand, private val profiles: ProfileStore) {

    suspend fun useModel(provider: String, model: String, baseUrl: String? = null): Result<Unit> {
        val settings = listOfNotNull(
            "provider" to provider,
            "default" to model,
            baseUrl?.let { "base_url" to it },
        )
        settings.firstOrNull { (_, value) -> !SAFE_VALUE.matches(value) }?.let { (key, _) ->
            return Result.failure(IllegalArgumentException("the model $key has characters that are not allowed"))
        }
        val script = settings.joinToString(" && ") { (key, value) -> "hermes config set model.$key $value" }
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
}
