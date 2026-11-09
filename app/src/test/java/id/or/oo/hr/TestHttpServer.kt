package id.or.oo.hr

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class TestHttpServer(private val respond: (path: String, headers: Map<String, String>) -> String) : AutoCloseable {

    class Exchange(
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        private val out: OutputStream,
        private val socket: Socket,
    ) {
        fun abort() {
            socket.setSoLinger(true, 0)
            socket.close()
        }

        fun write(text: String): Boolean = write(text.toByteArray())

        fun write(bytes: ByteArray): Boolean = try {
            out.write(bytes)
            out.flush()
            true
        } catch (e: IOException) {
            false
        }

        fun eventStreamHead(code: Int = 200): Boolean =
            write("HTTP/1.1 $code OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val clients = CopyOnWriteArrayList<Socket>()
    val port: Int get() = socket.localPort
    val requests = CopyOnWriteArrayList<String>()
    val methods = CopyOnWriteArrayList<String>()
    val bodies = CopyOnWriteArrayList<String>()
    val seenHeaders = CopyOnWriteArrayList<Map<String, String>>()

    @Volatile
    var stream: ((Exchange) -> Unit)? = null

    init {
        thread(isDaemon = true, name = "test-http") {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    return@thread
                }
                clients += client
                thread(isDaemon = true, name = "test-http-conn") {
                    try {
                        client.use(::serve)
                    } catch (e: IOException) {
                        Unit
                    } finally {
                        clients -= client
                    }
                }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream().buffered()
        val requestLine = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val parts = requestLine.split(' ')
        val path = parts[1]
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        val body = bytes.copyOf(read).toString(Charsets.UTF_8)
        methods += parts[0]
        bodies += body
        seenHeaders += headers
        requests += path
        val handler = stream
        if (handler != null) {
            handler(Exchange(path, headers, body, client.getOutputStream(), client))
            return
        }
        client.getOutputStream().apply {
            write(respond(path, headers).toByteArray())
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8)
            if (b == '\n'.code) return line.toString(Charsets.UTF_8).removeSuffix("\r")
            line.write(b)
        }
    }

    override fun close() {
        socket.close()
        clients.forEach { runCatching { it.close() } }
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
