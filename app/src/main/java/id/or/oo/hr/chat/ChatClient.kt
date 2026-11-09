package id.or.oo.hr.chat

import id.or.oo.hr.redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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

const val BASE = "http://127.0.0.1:8642/p/android/v1"

sealed interface TurnOutcome {
    data class Finished(val reason: String) : TurnOutcome
    data class Rejected(val code: Int, val body: String) : TurnOutcome
    data object NoGateway : TurnOutcome
    data class Broken(val reason: String) : TurnOutcome
}

interface ChatTurns {
    suspend fun send(sessionId: String, text: String, onEvent: (ChatEvent) -> Unit): TurnOutcome

    suspend fun answer(runId: String, requestId: String?, choice: String): Boolean
}

class ChatClient(
    private val key: () -> String,
    private val secrets: () -> List<String>,
    private val base: String = BASE,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : ChatTurns {

    override suspend fun send(sessionId: String, text: String, onEvent: (ChatEvent) -> Unit): TurnOutcome =
        withContext(Dispatchers.IO) {
            val connection = URL("$base/chat/completions").openConnection() as HttpURLConnection
            coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.disconnect()
                    }
                }
                try {
                    exchange(connection, sessionId, text, onEvent)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConnectException) {
                    ensureActive()
                    TurnOutcome.NoGateway
                } catch (e: IOException) {
                    ensureActive()
                    TurnOutcome.Broken(redact(e.message ?: e.javaClass.simpleName, secrets()))
                } finally {
                    watcher.cancel()
                }
            }
        }

    private fun exchange(
        connection: HttpURLConnection,
        sessionId: String,
        text: String,
        onEvent: (ChatEvent) -> Unit,
    ): TurnOutcome {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Authorization", "Bearer ${key()}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("X-Hermes-Session-Id", sessionId)
        connection.doOutput = true
        connection.outputStream.use { it.write(turnBody(text).toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code >= 400) {
            val body = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return TurnOutcome.Rejected(code, redact(body, secrets()).take(BODY_LIMIT))
        }
        var finish: String? = null
        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
            readSse(generateSequence { reader.readLine() }.iterator()) { frame ->
                val event = chatEvent(frame) ?: return@readSse
                if (event is ChatEvent.Finished) finish = event.reason
                onEvent(event)
            }
        }
        val reason = finish ?: return TurnOutcome.Broken("the reply ended early")
        return TurnOutcome.Finished(reason)
    }

    override suspend fun answer(runId: String, requestId: String?, choice: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("choice", choice)
            if (requestId != null) body.put("request_id", requestId)
            try {
                val connection = URL("$base/runs/$runId/approval").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = SHORT_TIMEOUT_MS
                    connection.readTimeout = SHORT_TIMEOUT_MS
                    connection.setRequestProperty("Authorization", "Bearer ${key()}")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    connection.responseCode in 200..299
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                false
            }
        }

    private fun turnBody(text: String): String = JSONObject()
        .put("model", "hermes-agent")
        .put("stream", true)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        .toString()

    private companion object {
        const val BODY_LIMIT = 500
        const val SHORT_TIMEOUT_MS = 15_000
    }
}
