package id.or.oo.hr.chat

import id.or.oo.hr.MIN_SECRET_LENGTH
import id.or.oo.hr.redact

fun displayText(raw: String, secrets: List<String>): String {
    val shown = redact(raw, secrets)
    val held = secrets
        .filter { it.length >= MIN_SECRET_LENGTH && it.isNotBlank() }
        .maxOfOrNull { secret -> (secret.length - 1 downTo 1).firstOrNull { shown.endsWith(secret.substring(0, it)) } ?: 0 }
        ?: 0
    return shown.dropLast(held)
}

fun finalText(raw: String, secrets: List<String>): String = redact(raw, secrets)
