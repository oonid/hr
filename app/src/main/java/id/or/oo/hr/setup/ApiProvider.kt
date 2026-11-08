package id.or.oo.hr.setup

import kotlinx.coroutines.CancellationException

enum class ApiProvider(
    val label: String,
    val id: String,
    val keyVariable: String,
    val needsBaseUrl: Boolean,
) {
    OpenRouter("OpenRouter", "openrouter", "OPENROUTER_API_KEY", false),
    OpenAI("OpenAI", "openai-api", "OPENAI_API_KEY", false),
    Anthropic("Anthropic", "anthropic", "ANTHROPIC_API_KEY", false),
    Compatible("OpenAI-compatible", "custom", "HERMES_CUSTOM_API_KEY", true),
}

internal fun String.corruptsAnEnvLine() =
    any { it.isWhitespace() || it.isISOControl() || it == '=' || it == '"' || it == '\'' || it == '\\' }

suspend fun ProfileSettings.useApiKey(
    provider: ApiProvider,
    key: String,
    model: String,
    baseUrl: String?,
): Result<Unit> {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) {
        return Result.failure(IllegalArgumentException("the API key is empty"))
    }
    if (trimmed.corruptsAnEnvLine()) {
        return Result.failure(IllegalArgumentException("the API key has characters that are not allowed"))
    }
    val url = if (provider.needsBaseUrl) baseUrl?.trim() else null
    if (provider.needsBaseUrl && url.isNullOrEmpty()) {
        return Result.failure(IllegalArgumentException("the base URL is required"))
    }
    checkValues(provider.id, model, url)?.let { return Result.failure(it) }
    return try {
        profiles.writeProfileEnv(mapOf(provider.keyVariable to trimmed))
        useModel(provider.id, model, url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
