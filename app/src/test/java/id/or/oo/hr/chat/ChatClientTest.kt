package id.or.oo.hr.chat

import id.or.oo.hr.TestHttpServer
import id.or.oo.hr.TestHttpServer.Companion.response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
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
        runBlocking { client.send(session, "turn-1", text) { events += it } }

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
    fun doneWithoutAFinishFrameIsFinished() {
        server.stream = { x ->
            x.eventStreamHead()
            x.write(chunk("ok"))
            x.write("data: [DONE]\n\n")
        }
        assertEquals(TurnOutcome.Finished("stop"), send())
        assertEquals(listOf(ChatEvent.Text("ok"), ChatEvent.Done), events.toList())
    }

    @Test
    fun aStreamCutShortIsBroken() {
        server.stream = { x ->
            x.eventStreamHead()
            x.write(chunk("partial"))
        }
        assertEquals(TurnOutcome.Broken("the reply ended early", lost = true), send())
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
        assertFalse((outcome as TurnOutcome.Broken).lost)
    }

    @Test
    fun toolLabelsAreRedacted() {
        server.stream = { x ->
            x.eventStreamHead()
            val running = JSONObject().put("toolCallId", "c1").put("status", "running").put("label", "curl -H 'Bearer sk-secret' and tg-token")
            x.write("event: hermes.tool.progress\ndata: $running\n\n")
            x.write(finish())
        }
        send(client(secrets = listOf("sk-secret", "tg-token")))
        assertEquals(ChatEvent.Tool("c1", "curl -H 'Bearer [redacted]' and [redacted]", true), events.first())
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
            val outcome = runBlocking { c.send("s", "turn-1", "hi") { } }
            assertEquals(TurnOutcome.Rejected(401, """{"error": "bad key [redacted]"}"""), outcome)
        }
    }

    @Test
    fun aRejectedBodyIsCutAtFiveHundredCharacters() {
        TestHttpServer { _, _ -> response(500, "x".repeat(900)) }.use {
            val c = ChatClient({ "k" }, { emptyList() }, "http://127.0.0.1:${it.port}/p/android/v1", 2_000, 2_000)
            val outcome = runBlocking { c.send("s", "turn-1", "hi") { } } as TurnOutcome.Rejected
            assertEquals(500, outcome.code)
            assertEquals(500, outcome.body.length)
        }
    }

    @Test
    fun noServerIsNoGateway() {
        val port = ServerSocket(0).use { it.localPort }
        val c = ChatClient({ "k" }, { emptyList() }, "http://127.0.0.1:$port/p/android/v1", 2_000, 2_000)
        assertEquals(TurnOutcome.NoGateway, runBlocking { c.send("s", "turn-1", "hi") { } })
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
                client(readTimeoutMs = 30_000).send("s", "turn-1", "hi") { if (it is ChatEvent.Text) first.countDown() }
            }
            assertTrue(first.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
        }
        assertTrue(writeFailed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun aCancelBeforeTheResponseStartsSendsNothingFurther() {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writeFailed = CountDownLatch(1)
        server.stream = { x ->
            requested.countDown()
            release.await(10, TimeUnit.SECONDS)
            x.eventStreamHead()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                if (!x.write(chunk("late"))) {
                    writeFailed.countDown()
                    break
                }
                Thread.sleep(20)
            }
        }
        val started = System.nanoTime()
        runBlocking {
            val job = launch(Dispatchers.Default) {
                client(readTimeoutMs = 30_000).send("s", "turn-1", "hi") { events += it }
            }
            assertTrue(requested.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()
        }
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
        assertTrue(writeFailed.await(10, TimeUnit.SECONDS))
        assertTrue(events.isEmpty())
    }

    @Test
    fun aFinishedReplyCutAfterTheFinishFrameIsFinished() {
        server.stream = { x ->
            x.eventStreamHead()
            x.write(chunk("ok"))
            x.write(finish())
            x.abort()
        }
        assertEquals(TurnOutcome.Finished("stop"), send())
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

    private fun pending(id: String, generation: Int, vararg choices: String) = JSONObject()
        .put("kind", "approval")
        .put("prompt_id", id)
        .put("execution_generation", generation)
        .put("command", "ls")
        .put("choices", org.json.JSONArray(choices.toList()))

    private fun runWith(vararg controls: JSONObject) = JSONObject()
        .put("object", "hermes.run")
        .put("pending_controls", org.json.JSONArray(controls.toList()))
        .put("execution_generation", 2)
        .put("status", "started")
        .toString()

    private fun serveRun(run: String, postCode: Int = 200) {
        server.stream = { x ->
            if (x.path.endsWith("/approval")) x.write(response(postCode, "{}")) else x.write(response(200, run))
        }
    }

    @Test
    fun anApprovalAnswerSendsTheExactBodyHermesRequires() {
        serveRun(runWith(pending("p1", 6, "once", "session", "deny")))
        val ok = runBlocking { client().answer("run-1", "p1", "deny") }
        assertTrue(ok)
        assertEquals(listOf("/p/android/v1/runs/run-1", "/p/android/v1/runs/run-1/approval"), server.requests.toList())
        assertEquals(listOf("GET", "POST"), server.methods.toList())
        assertEquals("Bearer sk-secret", server.seenHeaders.last()["authorization"])
        val body = JSONObject(server.bodies.last())
        assertEquals(setOf("request_id", "execution_generation", "choice"), body.keys().asSequence().toSet())
        assertEquals("p1", body.getString("request_id"))
        assertEquals(6, body.get("execution_generation"))
        assertEquals("deny", body.getString("choice"))
    }

    @Test
    fun anApprovalAnswerMatchesTheEventsRequestId() {
        serveRun(runWith(pending("p1", 3, "once", "deny"), pending("p2", 4, "once", "deny")))
        assertTrue(runBlocking { client().answer("run-1", "p2", "once") })
        val body = JSONObject(server.bodies.last())
        assertEquals("p2", body.getString("request_id"))
        assertEquals(4, body.get("execution_generation"))
    }

    @Test
    fun anApprovalWithoutARequestIdAnswersTheFirstPending() {
        serveRun(runWith(pending("p1", 3, "once", "deny"), pending("p2", 4, "once", "deny")))
        assertTrue(runBlocking { client().answer("run-1", null, "once") })
        assertEquals("p1", JSONObject(server.bodies.last()).getString("request_id"))
    }

    @Test
    fun anApprovalFallsBackToTheRunsGeneration() {
        val entry = pending("p1", 3, "once").also { it.remove("execution_generation") }
        serveRun(runWith(entry))
        assertTrue(runBlocking { client().answer("run-1", "p1", "once") })
        assertEquals(2, JSONObject(server.bodies.last()).get("execution_generation"))
    }

    @Test
    fun anAlreadyResolvedApprovalCountsAsAnswered() {
        serveRun(runWith())
        assertTrue(runBlocking { client().answer("run-1", "p1", "once") })
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aChoiceHermesDoesNotOfferIsRefused() {
        serveRun(runWith(pending("p1", 6, "once", "deny")))
        assertFalse(runBlocking { client().answer("run-1", "p1", "session") })
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aRejectedAnswerIsFalse() {
        serveRun(runWith(pending("p1", 6, "once", "deny")), postCode = 400)
        assertFalse(runBlocking { client().answer("run-1", "p1", "once") })
        assertEquals(2, server.requests.size)
    }

    @Test
    fun anUnreadableRunIsFalse() {
        serveRun("not json")
        assertFalse(runBlocking { client().answer("run-1", "p1", "once") })
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aRunIdIsEncodedInTheApprovalPath() {
        serveRun(runWith(pending("p1", 6, "once")))
        assertTrue(runBlocking { client().answer("chatcmpl-a/b c", "p1", "once") })
        assertEquals("/p/android/v1/runs/chatcmpl-a%2Fb%20c/approval", server.requests.last())
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
            } else if (x.path.endsWith("/runs/r1")) {
                x.write(response(200, runWith(pending("p1", 1, "once"))))
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
                c.send("s", "turn-1", "hi") {
                    events += it
                    if (it is ChatEvent.Approval) asked.countDown()
                }
            }
            assertTrue(asked.await(5, TimeUnit.SECONDS))
            assertTrue(c.answer("r1", "p1", "once"))
            assertEquals(TurnOutcome.Finished("stop"), turn.await())
        }
        assertTrue(events.first() is ChatEvent.Approval)
    }

    private fun runPath(turnId: String) =
        "/p/android/v1/runs/chat:2c52a9be01bb1414de9f9b32d99cabe6c317289ca667fa6120c7227baa0afb8c:$turnId"

    private fun run(status: String) = JSONObject()
        .put("object", "hermes.run")
        .put("pending_controls", org.json.JSONArray())
        .put("run_id", "r")
        .put("status", status)
        .put("admission_id", "2a34bbbaaf014a42882c85c908d4af87")
        .put("execution_generation", 6)
        .toString()

    @Test
    fun theTurnIdIsSentAsIdempotencyKey() {
        server.stream = { x ->
            x.eventStreamHead()
            x.write(finish())
        }
        runBlocking { client().send("s", "0f6c3a52-7d1e-4b8a-9c3f-2e5d6a7b8c9d", "hi") { } }
        assertEquals("0f6c3a52-7d1e-4b8a-9c3f-2e5d6a7b8c9d", server.seenHeaders.single()["idempotency-key"])
    }

    @Test
    fun aLostTurnIsResolved() {
        server.stream = { x ->
            if (x.path.endsWith("/resolve-unknown")) {
                x.write(response(200, """{"status": "terminal", "outcome": "interrupted"}"""))
            } else {
                x.write(response(200, run("unknown")))
            }
        }
        assertTrue(runBlocking { client().resolveLost("hrprobe1794209209") })
        val path = runPath("hrprobe1794209209")
        assertEquals(listOf(path, "$path/resolve-unknown"), server.requests.toList())
        assertEquals(listOf("GET", "POST"), server.methods.toList())
        assertTrue(server.seenHeaders.all { it["authorization"] == "Bearer sk-secret" })
        val body = JSONObject(server.bodies[1])
        assertEquals("2a34bbbaaf014a42882c85c908d4af87", body.getString("admission_id"))
        assertEquals(6, body.getInt("execution_generation"))
        assertEquals(2, body.length())
        assertTrue(server.requests.none { it.contains("sk-secret") })
    }

    @Test
    fun aTurnThatIsNotUnknownIsLeftAlone() {
        server.stream = { x -> x.write(response(200, run("completed"))) }
        assertTrue(runBlocking { client().resolveLost("t1") })
        assertEquals(listOf(runPath("t1")), server.requests.toList())
    }

    @Test
    fun aMissingRunCountsAsResolved() {
        server.stream = { x -> x.write(response(404, """{"error": "not found"}""")) }
        assertTrue(runBlocking { client().resolveLost("a/b c") })
        assertEquals(listOf(runPath("a%2Fb%20c")), server.requests.toList())
    }

    @Test
    fun noGatewayMeansNotResolved() {
        val port = ServerSocket(0).use { it.localPort }
        val c = ChatClient({ "k" }, { emptyList() }, "http://127.0.0.1:$port/p/android/v1", 2_000, 2_000)
        assertFalse(runBlocking { c.resolveLost("t1") })
    }

    @Test
    fun aGatewayThatFailsMeansNotResolved() {
        val replies = mapOf(
            "lookup fails" to { _: String -> response(500, "boom") },
            "resolve fails" to { path: String -> if (path.endsWith("/resolve-unknown")) response(409, "{}") else response(200, run("unknown")) },
            "not json" to { _: String -> response(200, "<html>") },
            "no admission" to { _: String -> response(200, JSONObject().put("status", "unknown").toString()) },
        )
        for ((name, reply) in replies) {
            server.stream = { x -> x.write(reply(x.path)) }
            assertFalse(name, runBlocking { client().resolveLost("t1") })
        }
    }

    @Test
    fun aBlankIOExceptionSaysTheConnectionWasLost() {
        for (thrown in listOf(EOFException(), IOException("  "))) {
            server.stream = { x ->
                x.eventStreamHead()
                x.write(chunk("part"))
                x.write(finish())
            }
            val outcome = runBlocking { client().send("s", "t1", "hi") { if (it is ChatEvent.Text) throw thrown } }
            assertEquals(TurnOutcome.Broken("the connection to the gateway was lost", lost = true), outcome)
        }
    }

    @Test
    fun aDroppedConnectionIsLostAndRedacted() {
        server.stream = { x ->
            x.eventStreamHead()
            x.write(chunk("part"))
            x.write(finish())
        }
        val thrown = IOException("reset while sending sk-secret")
        val outcome = runBlocking { client().send("s", "t1", "hi") { if (it is ChatEvent.Text) throw thrown } }
        assertEquals(TurnOutcome.Broken("reset while sending [redacted]", lost = true), outcome)
    }

    @Test
    fun aReadTimeoutIsNotALostConnection() {
        server.stream = { x ->
            x.eventStreamHead()
            x.write(chunk("part"))
            x.write(finish())
        }
        val thrown = SocketTimeoutException("Read timed out")
        val outcome = runBlocking { client().send("s", "t1", "hi") { if (it is ChatEvent.Text) throw thrown } }
        assertEquals(TurnOutcome.Broken("Read timed out", lost = false), outcome)
    }

    @Test
    fun cancellingAResolveDisconnectsAtOnce() {
        val asked = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.stream = { x ->
            asked.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        try {
            runBlocking {
                val job = launch(Dispatchers.Default) { client().resolveLost("t1") }
                assertTrue(asked.await(5, TimeUnit.SECONDS))
                job.cancel()
                withTimeout(5_000) { job.join() }
                assertTrue(job.isCancelled)
            }
        } finally {
            release.countDown()
        }
        assertEquals(listOf(runPath("t1")), server.requests.toList())
    }

    @Test
    fun aResolveCancelledDuringTheLookupSendsNoResolve() {
        val asked = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.stream = { x ->
            if (x.path.endsWith("/resolve-unknown")) {
                x.write(response(200, "{}"))
            } else {
                asked.countDown()
                release.await(30, TimeUnit.SECONDS)
                x.write(response(200, run("unknown")))
            }
        }
        runBlocking {
            val job = async(Dispatchers.Default) { client().resolveLost("t1") }
            assertTrue(asked.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals(listOf(runPath("t1")), server.requests.toList())
    }
}
