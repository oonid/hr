package id.or.oo.hr.image

import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val headers: Map<String, String>, val body: String)

fun interface Http {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

class UrlHttp(private val timeoutMs: Int = TIMEOUT_MS) : Http {

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val names = connection.headerFields
                .filterKeys { it != null }
                .map { (name, values) -> name.lowercase() to values.lastOrNull().orEmpty() }
                .toMap()
            return HttpResponse(code, names, body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
