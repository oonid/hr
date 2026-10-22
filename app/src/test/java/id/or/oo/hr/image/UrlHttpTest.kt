package id.or.oo.hr.image

import id.or.oo.hr.TestHttpServer
import id.or.oo.hr.TestHttpServer.Companion.response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlHttpTest {

    private val server = TestHttpServer { path, headers ->
        when (path) {
            "/ok" -> response(200, "accept=${headers["accept"]}", mapOf("Docker-Content-Digest" to "sha256:abc"))
            "/moved" -> response(307, headers = mapOf("Location" to "https://example.org/blob"))
            else -> response(404, "not here")
        }
    }
    private val base = "http://127.0.0.1:${server.port}"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun aGetSendsTheHeadersAndReturnsTheBodyAndLowercaseHeaders() {
        val response = UrlHttp().get("$base/ok", mapOf("Accept" to "application/json"))

        assertEquals(200, response.code)
        assertEquals("accept=application/json", response.body)
        assertEquals("sha256:abc", response.headers["docker-content-digest"])
    }

    @Test
    fun aRedirectIsReturnedNotFollowed() {
        val response = UrlHttp().get("$base/moved", emptyMap())

        assertEquals(307, response.code)
        assertEquals("https://example.org/blob", response.headers["location"])
        assertEquals(listOf("/moved"), server.requests)
    }

    @Test
    fun anErrorStatusKeepsItsBody() {
        val response = UrlHttp().get("$base/missing", emptyMap())

        assertEquals(404, response.code)
        assertEquals("not here", response.body)
    }
}
