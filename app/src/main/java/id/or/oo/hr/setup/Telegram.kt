package id.or.oo.hr.setup

import kotlinx.coroutines.CancellationException

const val TELEGRAM_PRESENT = "TELEGRAM present"

const val TELEGRAM_CHECK_SCRIPT =
    "V=\$(ls -d /root/.hermes/installs/*/environments/*/venv | head -1); " +
        "\"\$V/bin/python3\" -c 'import telegram; print(\"$TELEGRAM_PRESENT\")'"

private const val NO_TELEGRAM = "this Hermes image has no Telegram support; install a newer image"

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
        if (!hasTelegram()) return Result.failure(IllegalStateException(NO_TELEGRAM))
        profiles.writeProfileEnv(values)
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun ProfileSettings.removeTelegram(): Result<Unit> = try {
    profiles.removeFromProfileEnv(setOf("TELEGRAM_BOT_TOKEN", "TELEGRAM_ALLOWED_USERS"))
    Result.success(Unit)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

private suspend fun ProfileSettings.hasTelegram(): Boolean {
    var present = false
    val exit = command.run(TELEGRAM_CHECK_SCRIPT) { if (it.trim() == TELEGRAM_PRESENT) present = true }
    return exit == 0 && present
}
