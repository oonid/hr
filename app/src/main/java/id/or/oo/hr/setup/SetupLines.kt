package id.or.oo.hr.setup

import id.or.oo.hr.profile.SetupStatus

private const val CODEX = "openai-codex"

fun chatGptLine(status: SetupStatus): String = when {
    !status.chatGpt -> "Not signed in"
    status.provider == CODEX -> status.model?.let { "Signed in · $it" } ?: "Signed in"
    status.provider == null -> "Signed in · choose a model"
    else -> "Signed in (another provider is active)"
}

fun chatGptButton(status: SetupStatus): String = when {
    !status.chatGpt -> "Sign in with ChatGPT"
    status.provider == CODEX -> "Change model"
    else -> "Use ChatGPT"
}

fun otherProviderLine(status: SetupStatus): String? {
    val provider = status.provider ?: return null
    if (provider == CODEX || ApiProvider.entries.any { it.id == provider }) return null
    return "Active provider: $provider" + (status.model?.let { " · $it" } ?: "")
}
