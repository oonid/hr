package id.or.oo.hr.chat

data class SseEvent(val event: String?, val data: String)

fun readSse(lines: Iterator<String>, onEvent: (SseEvent) -> Unit) {
    var name: String? = null
    val data = mutableListOf<String>()

    fun flush() {
        if (data.isNotEmpty()) onEvent(SseEvent(name, data.joinToString("\n")))
        name = null
        data.clear()
    }

    while (lines.hasNext()) {
        val line = lines.next()
        when {
            line.isEmpty() -> flush()
            line.startsWith(":") -> Unit
            else -> {
                val colon = line.indexOf(':')
                val field = if (colon < 0) line else line.substring(0, colon)
                val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
                when (field) {
                    "event" -> name = value
                    "data" -> data += value
                }
            }
        }
    }
    flush()
}
