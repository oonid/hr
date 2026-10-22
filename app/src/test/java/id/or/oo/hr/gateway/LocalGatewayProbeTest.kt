package id.or.oo.hr.gateway

import id.or.oo.hr.TestHttpServer
import id.or.oo.hr.TestHttpServer.Companion.response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class LocalGatewayProbeTest {

    private var reply = response(200, """{"status": "ok", "platform": "hermes-agent", "version": "0.22.3"}""")
    private val server = TestHttpServer { _, _ -> reply }
    private val probe = LocalGatewayProbe(port = server.port)

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun aHealthyGatewayReportsItsVersion() {
        assertEquals(Health("0.22.3"), probe.health())
        assertEquals(listOf("/health"), server.requests)
    }

    @Test
    fun aHealthAnswerWithoutAVersionIsStillHealthy() {
        reply = response(200, """{"status": "ok"}""")

        assertEquals(Health(null), probe.health())
    }

    @Test
    fun anythingButAnOkStatusIsNotHealthy() {
        reply = response(200, """{"status": "starting"}""")
        assertNull(probe.health())

        reply = response(503, """{"status": "ok"}""")
        assertNull(probe.health())

        reply = response(200, "<html>proxy</html>")
        assertNull(probe.health())
    }

    @Test
    fun aListeningPortIsInUse() {
        assertTrue(probe.portInUse())
    }

    @Test
    fun aClosedPortIsFreeAndHasNoHealth() {
        val port = ServerSocket(0).use { it.localPort }
        val closed = LocalGatewayProbe(port = port)

        assertFalse(closed.portInUse())
        assertNull(closed.health())
    }
}
