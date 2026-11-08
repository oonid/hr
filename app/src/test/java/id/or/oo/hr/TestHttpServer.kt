package id.or.oo.hr

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class TestHttpServer(private val respond: (path: String, headers: Map<String, String>) -> String) : AutoCloseable {

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val requests = CopyOnWriteArrayList<String>()
    val methods = CopyOnWriteArrayList<String>()
    val bodies = CopyOnWriteArrayList<String>()
    val seenHeaders = CopyOnWriteArrayList<Map<String, String>>()

    init {
        thread(isDaemon = true, name = "test-http") {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    return@thread
                }
                client.use(::serve)
            }
        }
    }

    private fun serve(client: Socket) {
        val reader = client.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val parts = requestLine.split(' ')
        val path = parts[1]
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = CharArray(length).also { reader.read(it, 0, length) }.concatToString()
        methods += parts[0]
        bodies += body
        seenHeaders += headers
        requests += path
        client.getOutputStream().apply {
            write(respond(path, headers).toByteArray())
            flush()
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        fun response(code: Int, body: String = "", headers: Map<String, String> = emptyMap()): String {
            val length = body.toByteArray().size
            val head = (headers + mapOf("Content-Length" to "$length", "Connection" to "close"))
                .entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            return "HTTP/1.1 $code Status\r\n$head\r\n$body"
        }
    }
}
