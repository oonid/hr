package id.or.oo.hr

const val MIN_SECRET_LENGTH = 8

fun redact(text: String, secrets: List<String>): String =
    secrets.filter { it.length >= MIN_SECRET_LENGTH && it.isNotBlank() }
        .sortedByDescending { it.length }
        .fold(text) { out, secret -> out.replace(secret, "[redacted]") }
