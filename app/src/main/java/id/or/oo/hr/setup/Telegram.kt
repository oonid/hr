package id.or.oo.hr.setup

import kotlinx.coroutines.CancellationException

private val USER_IDS = Regex("^[0-9]+(,[0-9]+)*$")

suspend fun ProfileSettings.useTelegram(token: String, allowedUsers: String): Result<Unit> {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) {
        return Result.failure(IllegalArgumentException("the bot token is empty"))
    }
    if (trimmed.corruptsAnEnvLine()) {
        return Result.failure(IllegalArgumentException("the bot token has characters that are not allowed"))
    }
    val users = allowedUsers.filterNot { it.isWhitespace() }
    if (users.isNotEmpty() && !USER_IDS.matches(users)) {
        return Result.failure(IllegalArgumentException("the allowed users must be numeric ids separated by commas"))
    }
    val values = linkedMapOf("TELEGRAM_BOT_TOKEN" to trimmed)
    if (users.isNotEmpty()) values["TELEGRAM_ALLOWED_USERS"] = users
    return try {
        profiles.writeProfileEnv(values)
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
