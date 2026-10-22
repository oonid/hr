package id.or.oo.hr.gateway

import id.or.oo.hr.Hermes
import id.or.oo.hr.image.Http
import id.or.oo.hr.image.HttpResponse
import id.or.oo.hr.image.UrlHttp
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

data class Health(val version: String?)

interface GatewayProbe {
    fun portInUse(): Boolean
    fun health(): Health?
}

class LocalGatewayProbe(
    private val host: String = Hermes.HOST,
    private val port: Int = Hermes.PORT,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val http: Http = UrlHttp(timeoutMs),
) : GatewayProbe {

    override fun portInUse(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: IOException) {
        false
    }

    override fun health(): Health? = try {
        parseHealth(http.get("http://$host:$port/health", emptyMap()))
    } catch (e: IOException) {
        null
    }

    private companion object {
        const val TIMEOUT_MS = 2_000
    }
}

internal fun parseHealth(response: HttpResponse): Health? {
    if (response.code != 200) return null
    val body = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
    if (body.optString("status") != "ok") return null
    return Health(body.optString("version").ifEmpty { null })
}
