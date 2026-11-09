package id.or.oo.hr.chat

import id.or.oo.hr.Hermes
import id.or.oo.hr.redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

const val BASE = "http://127.0.0.1:8642/p/${Hermes.PROFILE_NAME}/v1"

sealed interface TurnOutcome {
    data class Finished(val reason: String) : TurnOutcome
    data class Rejected(val code: Int, val body: String) : TurnOutcome
    data object NoGateway : TurnOutcome
    data class Broken(val reason: String) : TurnOutcome
}

interface ChatTurns {
    suspend fun send(sessionId: String, turnId: String, text: String, onEvent: (ChatEvent) -> Unit): TurnOutcome

    suspend fun answer(runId: String, requestId: String?, choice: String): Boolean

    suspend fun resolveLost(turnId: String): Boolean
}

class ChatClient(
    private val key: () -> String,
    private val secrets: () -> List<String>,
    private val base: String = BASE,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : ChatTurns {

    override suspend fun send(sessionId: String, turnId: String, text: String, onEvent: (ChatEvent) -> Unit): TurnOutcome =
        withContext(Dispatchers.IO) {
            val connection = URL("$base/chat/completions").openConnection() as HttpURLConnection
            coroutineScope {
                var finish: String? = null
                var done = false
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.disconnect()
                    }
                }
                try {
                    exchange(connection, sessionId, turnId, text, { isActive }) {
                        if (it is ChatEvent.Finished) finish = it.reason
                        if (it is ChatEvent.Done) done = true
                        onEvent(it)
                    }.let { outcome -> outcome ?: finish?.let { TurnOutcome.Finished(it) } ?: if (done) TurnOutcome.Finished("stop") else TurnOutcome.Broken(EARLY) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConnectException) {
                    ensureActive()
                    TurnOutcome.NoGateway
                } catch (e: IOException) {
                    ensureActive()
                    finish?.let { TurnOutcome.Finished(it) } ?: TurnOutcome.Broken(brokenReason(e))
                } catch (e: RuntimeException) {
                    ensureActive()
                    throw e
                } finally {
                    watcher.cancel()
                }
            }
        }

    private fun exchange(
        connection: HttpURLConnection,
        sessionId: String,
        turnId: String,
        text: String,
        active: () -> Boolean,
        onEvent: (ChatEvent) -> Unit,
    ): TurnOutcome? {
        abandonIfCancelled(connection, active)
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Authorization", "Bearer ${key()}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("X-Hermes-Session-Id", sessionId)
        connection.setRequestProperty("Idempotency-Key", turnId)
        connection.doOutput = true
        connection.outputStream.use { it.write(turnBody(text).toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        abandonIfCancelled(connection, active)
        if (code >= 400) {
            val body = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return TurnOutcome.Rejected(code, redact(body, secrets()).take(BODY_LIMIT))
        }
        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
            readSse(generateSequence { reader.readLine() }.iterator()) { frame ->
                chatEvent(frame)?.let(onEvent)
            }
        }
        return null
    }

    private fun abandonIfCancelled(connection: HttpURLConnection, active: () -> Boolean) {
        if (active()) return
        connection.disconnect()
        throw CancellationException("the turn was cancelled")
    }

    private fun brokenReason(e: IOException): String {
        val message = e.message
        return if (message.isNullOrBlank()) LOST else redact(message, secrets())
    }

    override suspend fun answer(runId: String, requestId: String?, choice: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val path = "$base/runs/${pathSegment(runId)}"
                val run = request("GET", path, null)
                if (run.code in 200..299) postAnswer(path, JSONObject(run.body), requestId, choice) else false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                false
            }
        }

    private suspend fun postAnswer(path: String, run: JSONObject, requestId: String?, choice: String): Boolean {
        val controls = run.optJSONArray("pending_controls")
        val prompt = (0 until (controls?.length() ?: 0))
            .mapNotNull { controls?.optJSONObject(it) }
            .firstOrNull { it.opt("kind") == "approval" && (requestId == null || it.opt("prompt_id") == requestId) }
            ?: return true
        val choices = prompt.optJSONArray("choices")
        if ((0 until (choices?.length() ?: 0)).none { choices?.opt(it) == choice }) return false
        val promptId = prompt.opt("prompt_id") as? String ?: return false
        val generation = prompt.opt("execution_generation") as? Number
            ?: run.opt("execution_generation") as? Number
            ?: return false
        val body = JSONObject()
            .put("request_id", promptId)
            .put("execution_generation", generation)
            .put("choice", choice)
        return request("POST", "$path/approval", body).code in 200..299
    }

    override suspend fun resolveLost(turnId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val path = "$base/runs/${pathSegment(runId(turnId))}"
                val run = request("GET", path, null)
                when (run.code) {
                    404 -> true
                    in 200..299 -> resolveIfUnknown(path, JSONObject(run.body))
                    else -> false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                false
            }
        }

    private suspend fun resolveIfUnknown(path: String, run: JSONObject): Boolean {
        if (run.opt("status") != "unknown") return true
        val admission = run.opt("admission_id") as? String ?: return false
        val generation = run.opt("execution_generation") as? Number ?: return false
        val body = JSONObject().put("admission_id", admission).put("execution_generation", generation)
        return request("POST", "$path/resolve-unknown", body).code in 200..299
    }

    private fun runId(turnId: String): String {
        val scope = Hermes.PROFILE_NAME + SCOPE_SEPARATOR + key()
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
        return RUN_PREFIX + ":" + digest.joinToString("") { "%02x".format(it) } + ":" + turnId
    }

    private fun pathSegment(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in PATH_SAFE)) append(ch) else append("%%%02X".format(c))
        }
    }

    private class Reply(val code: Int, val body: String)

    private suspend fun request(method: String, url: String, body: JSONObject?): Reply = coroutineScope {
        val connection = URL(url).openConnection() as HttpURLConnection
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            abandonIfCancelled(connection) { isActive }
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = SHORT_TIMEOUT_MS
            connection.readTimeout = SHORT_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer ${key()}")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val text = if (code in 200..299) connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else ""
            abandonIfCancelled(connection) { isActive }
            Reply(code, text)
        } finally {
            watcher.cancel()
            connection.disconnect()
        }
    }

    private fun turnBody(text: String): String = JSONObject()
        .put("model", "hermes-agent")
        .put("stream", true)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        .toString()

    private companion object {
        const val EARLY = "the reply ended early"
        const val LOST = "the connection to the gateway was lost"
        const val RUN_PREFIX = "chat"
        const val SCOPE_SEPARATOR = "\u0000"
        const val PATH_SAFE = "-._~:"
        const val BODY_LIMIT = 500
        const val SHORT_TIMEOUT_MS = 15_000
    }
}
