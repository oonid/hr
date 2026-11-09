package id.or.oo.hr.chat

import id.or.oo.hr.TestHttpServer
import id.or.oo.hr.TestHttpServer.Companion.response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatClientTest {

    private val server = TestHttpServer { _, _ -> response(200) }
    private val base get() = "http://127.0.0.1:${server.port}/p/android/v1"
    private val events = CopyOnWriteArrayList<ChatEvent>()

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(readTimeoutMs: Int = 5_000, secrets: List<String> = listOf("sk-secret")) =
        ChatClient({ "sk-secret" }, { secrets }, base, 2_000, readTimeoutMs)

    private fun chunk(text: String) =
        "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text)))) + "\n\n"

    private fun finish(reason: String = "stop") =
        "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", reason))) + "\n\n"

    private fun send(client: ChatClient = client(), session: String = "s1", text: String = "hi") =
        runBlocking { client.send(session, text) { events += it } }

    @Test
    fun aStreamedReplyArrivesAsEvents() {
        server.stream = { x ->
            x.eventStreamHead()
            x.write(chunk("Hel"))
            x.write(chunk("lo"))
            x.write(finish())
            x.write("data: [DONE]\n\n")
        }
        val outcome = send()
        assertEquals(TurnOutcome.Finished("stop"), outcome)
        assertEquals(
            listOf(ChatEvent.Text("Hel"), ChatEvent.Text("lo"), ChatEvent.Finished("stop"), ChatEvent.Done),
            events.toList(),
        )
        assertEquals("/p/android/v1/chat/completions", server.requests.single())
        assertEquals("POST", server.methods.single())
        val headers = server.seenHeaders.single()
        assertEquals("Bearer sk-secret", headers["authorization"])
        assertEquals("application/json", headers["content-type"])
        assertEquals("text/event-stream", headers["accept"])
        val body = JSONObject(server.bodies.single())
        assertEquals("hermes-agent", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("hi", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test
    fun theSessionIdIsSent() {
        server.stream = { x ->
            x.eventStreamHead()
            x.write(finish())
        }
        send(session = "abc-123")
        assertEquals("abc-123", server.seenHeaders.single()["x-hermes-session-id"])
    }

    @Test
    fun aMessageWithQuotesAndNewlinesIsEncoded() {
        val text = "say \"hi\"\nthen \\ a tab\t and 👋"
        server.stream = { x ->
            x.eventStreamHead()
            x.write(finish())
        }
        send(text = text)
        val sent = JSONObject(server.bodies.single()).getJSONArray("messages").getJSONObject(0).getString("content")
        assertEquals(text, sent)
    }

    @Test
    fun aSlowTurnWithKeepalivesCompletes() {
        server.stream = { x ->
            x.eventStreamHead()
            repeat(10) {
                x.write(": keepalive\n\n")
                Thread.sleep(100)
            }
            x.write(chunk("done"))
            x.write(finish())
        }
        assertEquals(TurnOutcome.Finished("stop"), send(client(readTimeoutMs = 300)))
        assertEquals(listOf(ChatEvent.Text("done"), ChatEvent.Finished("stop")), events.toList())
    }

    @Test
    fun aStreamCutShortIsBroken() {
        server.stream = { x ->
            x.eventStreamHead()
            x.write(chunk("partial"))
        }
        assertEquals(TurnOutcome.Broken("the reply ended early"), send())
        assertEquals(listOf<ChatEvent>(ChatEvent.Text("partial")), events.toList())
    }

    @Test
    fun aSilentServerPastTheReadTimeoutIsBroken() {
        val release = CountDownLatch(1)
        server.stream = { x ->
            x.eventStreamHead()
            release.await(5, TimeUnit.SECONDS)
        }
        val outcome = send(client(readTimeoutMs = 200))
        release.countDown()
        assertTrue(outcome is TurnOutcome.Broken)
    }

    @Test
    fun theLastFinishFrameWins() {
        server.stream = { x ->
            x.eventStreamHead()
            x.write(finish("tool_calls"))
            x.write(finish("stop"))
        }
        assertEquals(TurnOutcome.Finished("stop"), send())
    }

    @Test
    fun a401IsRejectedAndRedacted() {
        server.stream = null
        val rejecting = TestHttpServer { _, _ -> response(401, """{"error": "bad key sk-secret"}""") }
        rejecting.use {
            val c = ChatClient({ "sk-secret" }, { listOf("sk-secret") }, "http://127.0.0.1:${it.port}/p/android/v1", 2_000, 2_000)
            val outcome = runBlocking { c.send("s", "hi") { } }
            assertEquals(TurnOutcome.Rejected(401, """{"error": "bad key [redacted]"}"""), outcome)
        }
    }

    @Test
    fun aRejectedBodyIsCutAtFiveHundredCharacters() {
        TestHttpServer { _, _ -> response(500, "x".repeat(900)) }.use {
            val c = ChatClient({ "k" }, { emptyList() }, "http://127.0.0.1:${it.port}/p/android/v1", 2_000, 2_000)
            val outcome = runBlocking { c.send("s", "hi") { } } as TurnOutcome.Rejected
            assertEquals(500, outcome.code)
            assertEquals(500, outcome.body.length)
        }
    }

    @Test
    fun noServerIsNoGateway() {
        val port = ServerSocket(0).use { it.localPort }
        val c = ChatClient({ "k" }, { emptyList() }, "http://127.0.0.1:$port/p/android/v1", 2_000, 2_000)
        assertEquals(TurnOutcome.NoGateway, runBlocking { c.send("s", "hi") { } })
    }

    @Test
    fun cancellingDisconnects() {
        val first = CountDownLatch(1)
        val writeFailed = CountDownLatch(1)
        server.stream = { x ->
            x.eventStreamHead()
            x.write(chunk("start"))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                if (!x.write(": keepalive\n\n")) {
                    writeFailed.countDown()
                    break
                }
                Thread.sleep(20)
            }
        }
        runBlocking {
            val job = launch(Dispatchers.Default) {
                client(readTimeoutMs = 30_000).send("s", "hi") { if (it is ChatEvent.Text) first.countDown() }
            }
            assertTrue(first.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
        assertTrue(writeFailed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun textSplitAcrossWritesKeepsMultiByteCharacters() {
        server.stream = { x ->
            x.eventStreamHead()
            val bytes = chunk("hi 👋 there").toByteArray()
            val at = String(bytes, Charsets.UTF_8).indexOf("👋").let { String(bytes, Charsets.UTF_8).substring(0, it).toByteArray().size + 2 }
            x.write(bytes.copyOfRange(0, at))
            Thread.sleep(50)
            x.write(bytes.copyOfRange(at, bytes.size))
            x.write(finish())
        }
        assertEquals(TurnOutcome.Finished("stop"), send())
        assertEquals(ChatEvent.Text("hi 👋 there"), events.first())
    }

    @Test
    fun anApprovalAnswerIsPosted() {
        val ok = runBlocking { client().answer("run-1", "req-9", "once") }
        assertTrue(ok)
        assertEquals("/p/android/v1/runs/run-1/approval", server.requests.single())
        assertEquals("POST", server.methods.single())
        assertEquals("Bearer sk-secret", server.seenHeaders.single()["authorization"])
        val body = JSONObject(server.bodies.single())
        assertEquals("once", body.getString("choice"))
        assertEquals("req-9", body.getString("request_id"))
    }

    @Test
    fun anApprovalWithoutARequestIdOmitsIt() {
        runBlocking { client().answer("run-1", null, "deny") }
        assertFalse(JSONObject(server.bodies.single()).has("request_id"))
    }

    @Test
    fun aRefusedApprovalIsFalse() {
        TestHttpServer { _, _ -> response(404) }.use {
            val c = ChatClient({ "k" }, { emptyList() }, "http://127.0.0.1:${it.port}/p/android/v1", 2_000, 2_000)
            assertFalse(runBlocking { c.answer("r", null, "once") })
        }
    }

    @Test
    fun anApprovalWithNoServerIsFalse() {
        val port = ServerSocket(0).use { it.localPort }
        val c = ChatClient({ "k" }, { emptyList() }, "http://127.0.0.1:$port/p/android/v1", 2_000, 2_000)
        assertFalse(runBlocking { c.answer("r", null, "once") })
    }

    @Test
    fun anApprovalCanBeAnsweredWhileTheStreamIsOpen() {
        val approved = CountDownLatch(1)
        val asked = CountDownLatch(1)
        server.stream = { x ->
            if (x.path.endsWith("/approval")) {
                x.write(response(200, "{}"))
                approved.countDown()
            } else {
                x.eventStreamHead()
                val frame = JSONObject().put("run_id", "r1").put("command", "ls")
                x.write("event: approval.request\ndata: $frame\n\n")
                approved.await(5, TimeUnit.SECONDS)
                x.write(chunk("ok"))
                x.write(finish())
            }
        }
        val c = client()
        runBlocking {
            val turn = async(Dispatchers.Default) {
                c.send("s", "hi") {
                    events += it
                    if (it is ChatEvent.Approval) asked.countDown()
                }
            }
            assertTrue(asked.await(5, TimeUnit.SECONDS))
            assertTrue(c.answer("r1", null, "once"))
            assertEquals(TurnOutcome.Finished("stop"), turn.await())
        }
        assertTrue(events.first() is ChatEvent.Approval)
    }
}
