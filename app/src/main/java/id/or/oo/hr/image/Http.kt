package id.or.oo.hr.image

import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val headers: Map<String, String>, val body: String)

fun interface Http {
    fun get(url: String, headers: Map<String, String>): HttpResponse

    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
        throw UnsupportedOperationException("this Http does not post")
}

class UrlHttp(private val timeoutMs: Int = TIMEOUT_MS) : Http {

    override fun get(url: String, headers: Map<String, String>): HttpResponse =
        exchange(url, headers, null, timeoutMs)

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
        exchange(url, headers, body, POST_READ_TIMEOUT_MS)

    private fun exchange(url: String, headers: Map<String, String>, body: String?, readTimeoutMs: Int): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = readTimeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val names = connection.headerFields
                .filterKeys { it != null }
                .map { (name, values) -> name.lowercase() to values.lastOrNull().orEmpty() }
                .toMap()
            return HttpResponse(code, names, text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val POST_READ_TIMEOUT_MS = 120_000
    }
}
