package id.or.oo.hr.chat

import id.or.oo.hr.writePrivate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val tools: List<String> = emptyList(),
    val interrupted: Boolean = false,
)

data class Conversation(
    val id: String,
    val sessionId: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
)

private val ID_PATTERN = Regex("^[A-Za-z0-9-]{1,64}$")
private const val EXTENSION = ".json"
private const val TITLE_LIMIT = 40

class ConversationStore(private val dir: File) {

    fun list(): List<Conversation> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(EXTENSION) }
            .mapNotNull { file ->
                val id = file.name.removeSuffix(EXTENSION)
                if (ID_PATTERN.matches(id)) read(file, id)?.copy(messages = emptyList()) else null
            }
            .sortedByDescending { it.updatedAt }
    }

    fun load(id: String): Conversation? = read(fileFor(id), id)

    fun save(c: Conversation) {
        writePrivate(fileFor(c.id), encode(c).toString())
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    private fun fileFor(id: String): File {
        require(ID_PATTERN.matches(id)) { "invalid conversation id" }
        return File(dir, id + EXTENSION)
    }

    private fun read(file: File, id: String): Conversation? {
        val text = try {
            file.readText()
        } catch (e: java.io.IOException) {
            return null
        }
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }
        return decode(json, id)
    }
}

private fun encode(c: Conversation): JSONObject = JSONObject()
    .put("id", c.id)
    .put("sessionId", c.sessionId)
    .put("title", c.title)
    .put("updatedAt", c.updatedAt)
    .put("messages", JSONArray(c.messages.map { encode(it) }))

private fun encode(m: ChatMessage): JSONObject {
    val json = JSONObject().put("fromUser", m.fromUser).put("text", m.text)
    if (m.tools.isNotEmpty()) json.put("tools", JSONArray(m.tools))
    if (m.interrupted) json.put("interrupted", true)
    return json
}

private fun decode(json: JSONObject, expectedId: String): Conversation? {
    val id = json.opt("id") as? String ?: return null
    if (id != expectedId) return null
    val sessionId = json.opt("sessionId") as? String ?: return null
    val title = json.opt("title") as? String ?: return null
    val updatedAt = (json.opt("updatedAt") as? Number)?.toLong() ?: return null
    val array = json.opt("messages") as? JSONArray ?: return null
    val messages = ArrayList<ChatMessage>(array.length())
    for (i in 0 until array.length()) {
        messages.add(decodeMessage(array.opt(i) as? JSONObject ?: return null) ?: return null)
    }
    return Conversation(id, sessionId, title, updatedAt, messages)
}

private fun decodeMessage(json: JSONObject): ChatMessage? {
    val fromUser = json.opt("fromUser") as? Boolean ?: return null
    val text = json.opt("text") as? String ?: return null
    val tools = when (val raw = json.opt("tools")) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> (0 until raw.length()).map { raw.opt(it) as? String ?: return null }
        else -> return null
    }
    val interrupted = when (val raw = json.opt("interrupted")) {
        null, JSONObject.NULL -> false
        is Boolean -> raw
        else -> return null
    }
    return ChatMessage(fromUser, text, tools, interrupted)
}

fun titleFor(firstMessage: String): String {
    val collapsed = firstMessage.trim().replace(Regex("\\s+"), " ")
    if (collapsed.isEmpty()) return "New chat"
    if (collapsed.length <= TITLE_LIMIT) return collapsed
    var end = TITLE_LIMIT
    if (Character.isHighSurrogate(collapsed[end - 1]) && Character.isLowSurrogate(collapsed[end])) end--
    return collapsed.substring(0, end) + "…"
}
