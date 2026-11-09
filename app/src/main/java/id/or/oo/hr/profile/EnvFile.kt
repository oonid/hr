package id.or.oo.hr.profile

fun mergeEnv(existing: String, values: Map<String, String>): String {
    val kept = if (existing.isEmpty()) {
        emptyList()
    } else {
        existing.replace("\r", "").removeSuffix("\n").split('\n').filter { envKey(it) !in values }
    }
    return (kept + values.map { (key, value) -> "$key=$value" }).joinToString("\n", postfix = "\n")
}

internal fun envKey(line: String): String? {
    val assignment = line.trim().removePrefix("export ").trimStart()
    val eq = assignment.indexOf('=')
    return if (eq > 0) assignment.substring(0, eq).trim() else null
}

internal fun parseEnv(text: String): List<Pair<String, String>> =
    text.replace("\r", "").split('\n').mapNotNull { line ->
        val assignment = line.trim().removePrefix("export ").trimStart()
        val name = assignment.substringBefore('=').trim()
        val value = assignment.substringAfter('=', "").trim().trim('"', '\'')
        name.takeIf { it.isNotEmpty() }?.let { it to value }
    }

internal fun withoutEnvKeys(existing: String, names: Set<String>): String {
    val kept = existing.replace("\r", "").removeSuffix("\n").split('\n').filter { envKey(it) !in names }
    return if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n")
}
