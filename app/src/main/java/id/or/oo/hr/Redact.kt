package id.or.oo.hr

fun redact(text: String, secrets: List<String>): String =
    secrets.filter { it.isNotBlank() }
        .sortedByDescending { it.length }
        .fold(text) { out, secret -> out.replace(secret, "[redacted]") }
