package id.or.oo.hr.chat

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

sealed interface ChatEvent {
    data class Text(val delta: String) : ChatEvent
    data class Tool(val callId: String, val label: String, val running: Boolean) : ChatEvent
    data class Approval(
        val runId: String,
        val requestId: String?,
        val command: String,
        val description: String,
        val choices: List<String>,
    ) : ChatEvent
    data class Finished(val reason: String) : ChatEvent
    data object Done : ChatEvent
}

private val DEFAULT_CHOICES = listOf("once", "deny")

fun chatEvent(e: SseEvent): ChatEvent? {
    if (e.data == "[DONE]") return ChatEvent.Done
    val json = try {
        JSONObject(e.data)
    } catch (ex: JSONException) {
        return null
    }
    return when (e.event) {
        null -> chunk(json)
        "hermes.tool.progress" -> tool(json)
        "approval.request" -> approval(json)
        else -> null
    }
}

private fun chunk(json: JSONObject): ChatEvent? {
    val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
    val content = choice.optJSONObject("delta")?.opt("content") as? String
    if (!content.isNullOrEmpty()) return ChatEvent.Text(content)
    val reason = choice.opt("finish_reason") as? String ?: return null
    return ChatEvent.Finished(reason)
}

private fun tool(json: JSONObject): ChatEvent? {
    val callId = json.opt("toolCallId") as? String ?: return null
    if (json.opt("status") != "running") return ChatEvent.Tool(callId, "", false)
    val label = json.opt("label") as? String ?: json.opt("tool") as? String ?: ""
    val emoji = json.opt("emoji") as? String
    val text = if (emoji.isNullOrEmpty()) label else "$emoji $label"
    return ChatEvent.Tool(callId, text, true)
}

private fun approval(json: JSONObject): ChatEvent? {
    val runId = json.opt("run_id") as? String ?: return null
    val command = json.opt("command") as? String ?: return null
    return ChatEvent.Approval(
        runId = runId,
        requestId = json.opt("request_id") as? String,
        command = command,
        description = json.opt("description") as? String ?: "",
        choices = choices(json.optJSONArray("choices")),
    )
}

private fun choices(array: JSONArray?): List<String> {
    if (array == null) return DEFAULT_CHOICES
    val out = (0 until array.length()).mapNotNull { array.opt(it) as? String }
    return out.ifEmpty { DEFAULT_CHOICES }
}
