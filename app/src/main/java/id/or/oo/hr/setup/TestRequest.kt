package id.or.oo.hr.setup

import id.or.oo.hr.chat.redactPrefixes
import id.or.oo.hr.image.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

sealed interface TestResult {
    data class Answered(val text: String) : TestResult
    data class Rejected(val code: Int, val body: String) : TestResult
    data object NoGateway : TestResult
    data class Failed(val reason: String) : TestResult
}

class TestRequest(
    private val http: Http,
    private val key: () -> String,
    private val url: String = URL,
    private val secrets: () -> List<String> = { listOf(key()) },
) {

    fun send(): TestResult {
        val body = JSONObject()
            .put("model", "hermes-agent")
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with exactly one word: ok")),
            )
            .toString()
        val response = try {
            http.post(url, mapOf("Authorization" to "Bearer ${key()}", "Content-Type" to "application/json"), body)
        } catch (e: ConnectException) {
            return TestResult.NoGateway
        } catch (e: SocketTimeoutException) {
            return TestResult.Failed(TIMEOUT)
        } catch (e: IOException) {
            return TestResult.Failed(e.message?.let { redactPrefixes(it, secrets()) } ?: e.javaClass.simpleName)
        }
        if (response.code >= 400) return TestResult.Rejected(response.code, redactPrefixes(response.body, secrets()).take(BODY_LIMIT))
        val text = answer(response.body)
            ?: return TestResult.Failed("the gateway answered something hr cannot read")
        return TestResult.Answered(redactPrefixes(text.trim(), secrets()))
    }

    private fun answer(body: String): String? = try {
        JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").opt("content") as? String
    } catch (e: org.json.JSONException) {
        null
    }

    companion object {
        const val URL = "http://127.0.0.1:8642/p/android/v1/chat/completions"
        private const val BODY_LIMIT = 500
        private const val TIMEOUT = "the gateway did not answer in time; the model endpoint may be unreachable" +
            " — check the provider's base URL and key"
    }
}
