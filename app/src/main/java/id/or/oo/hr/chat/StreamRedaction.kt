package id.or.oo.hr.chat

import id.or.oo.hr.MIN_SECRET_LENGTH
import id.or.oo.hr.redact

fun displayText(raw: String, secrets: List<String>): String {
    val shown = redact(raw, secrets)
    return shown.dropLast(held(shown, secrets))
}

fun finalText(raw: String, secrets: List<String>): String {
    val shown = redact(raw, secrets)
    val held = held(shown, secrets)
    return if (held >= MIN_SECRET_LENGTH) shown.dropLast(held) + "[redacted]" else shown
}

private fun held(shown: String, secrets: List<String>): Int = secrets
    .filter { it.length >= MIN_SECRET_LENGTH && it.isNotBlank() }
    .maxOfOrNull { secret -> (secret.length - 1 downTo 1).firstOrNull { shown.endsWith(secret.substring(0, it)) } ?: 0 }
    ?: 0
