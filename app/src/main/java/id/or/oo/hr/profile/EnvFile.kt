package id.or.oo.hr.profile

fun mergeEnv(existing: String, values: Map<String, String>): String {
    val kept = if (existing.isEmpty()) {
        emptyList()
    } else {
        existing.replace("\r", "").removeSuffix("\n").split('\n').filter { envKey(it) !in values }
    }
    return (kept + values.map { (key, value) -> "$key=$value" }).joinToString("\n", postfix = "\n")
}

private fun envKey(line: String): String? {
    val assignment = line.trim().removePrefix("export ").trimStart()
    val eq = assignment.indexOf('=')
    return if (eq > 0) assignment.substring(0, eq).trim() else null
}
