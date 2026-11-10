package id.or.oo.hr.chat

import id.or.oo.hr.MIN_SECRET_LENGTH

fun redactPrefixes(text: String, secrets: List<String>): String {
    val runs = mutableListOf<IntRange>()
    for (secret in secrets.distinct()) {
        if (secret.length < MIN_SECRET_LENGTH || secret.isBlank()) continue
        val needle = secret.substring(0, MIN_SECRET_LENGTH)
        var at = text.indexOf(needle)
        while (at >= 0) {
            var end = at + MIN_SECRET_LENGTH
            while (end < text.length && end - at < secret.length && text[end] == secret[end - at]) end++
            runs += at until end
            at = text.indexOf(needle, at + 1)
        }
    }
    if (runs.isEmpty()) return text
    runs.sortBy { it.first }
    val out = StringBuilder()
    var copied = 0
    var next = 0
    while (next < runs.size) {
        val start = runs[next].first
        var end = runs[next].last + 1
        next++
        while (next < runs.size && runs[next].first <= end) end = maxOf(end, runs[next++].last + 1)
        out.append(text, copied, start).append("[redacted]")
        copied = end
    }
    return out.append(text, copied, text.length).toString()
}
