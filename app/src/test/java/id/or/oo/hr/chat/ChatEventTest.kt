package id.or.oo.hr.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatEventTest {

    private fun chunk(delta: String, finish: String = "null", extra: String = ""): SseEvent = SseEvent(
        null,
        "{\"id\": \"chatcmpl-b19a\", \"object\": \"chat.completion.chunk\", \"created\": 1794202697, " +
            "\"model\": \"hermes-agent\", \"choices\": [{\"index\": 0, \"delta\": $delta, " +
            "\"finish_reason\": $finish}]$extra}",
    )

    @Test
    fun contentDeltasAreText() {
        assertEquals(ChatEvent.Text("\n\n202"), chatEvent(chunk("{\"content\": \"\\n\\n202\"}")))
    }

    @Test
    fun roleAndReasoningAreIgnored() {
        assertNull(chatEvent(chunk("{\"role\": \"assistant\"}")))
        assertNull(chatEvent(chunk("{\"reasoning\": \"thinking\"}")))
        assertNull(chatEvent(chunk("{\"content\": null}")))
        assertNull(chatEvent(SseEvent("hermes.status", "{\"status\": \"thinking\"}")))
    }

    @Test
    fun toolRunningAndCompleted() {
        val running = SseEvent(
            "hermes.tool.progress",
            "{\"tool\": \"terminal\", \"emoji\": \"💻\", \"label\": \"date +%Y\", " +
                "\"toolCallId\": \"call_1\", \"status\": \"running\"}",
        )
        val completed = SseEvent(
            "hermes.tool.progress",
            "{\"tool\": \"terminal\", \"toolCallId\": \"call_1\", \"status\": \"completed\"}",
        )
        assertEquals(ChatEvent.Tool("call_1", "💻 date +%Y", true), chatEvent(running))
        assertEquals(ChatEvent.Tool("call_1", "", false), chatEvent(completed))
    }

    @Test
    fun aToolWithoutAnEmojiUsesItsLabel() {
        val running = SseEvent(
            "hermes.tool.progress",
            "{\"tool\": \"terminal\", \"label\": \"ls\", \"toolCallId\": \"c\", \"status\": \"running\"}",
        )
        assertEquals(ChatEvent.Tool("c", "ls", true), chatEvent(running))
    }

    @Test
    fun aToolFrameWithoutACallIdIsIgnored() {
        assertNull(chatEvent(SseEvent("hermes.tool.progress", "{\"status\": \"running\"}")))
    }

    @Test
    fun anApprovalCarriesItsChoices() {
        val frame = SseEvent(
            "approval.request",
            "{\"event\": \"approval.request\", \"run_id\": \"chatcmpl-abc\", \"timestamp\": 1.0, " +
                "\"command\": \"rm -rf /root/x\", \"description\": \"dangerous delete\", " +
                "\"choices\": [\"once\", \"session\", \"deny\"], \"request_id\": \"r1\"}",
        )
        assertEquals(
            ChatEvent.Approval(
                "chatcmpl-abc", "r1", "rm -rf /root/x", "dangerous delete", listOf("once", "session", "deny"),
            ),
            chatEvent(frame),
        )
    }

    @Test
    fun anApprovalWithoutARequestIdOrChoicesUsesDefaults() {
        val frame = SseEvent("approval.request", "{\"run_id\": \"r\", \"command\": \"ls\"}")
        assertEquals(
            ChatEvent.Approval("r", null, "ls", "", listOf("once", "deny")),
            chatEvent(frame),
        )
    }

    @Test
    fun anApprovalMissingItsRunOrCommandIsIgnored() {
        assertNull(chatEvent(SseEvent("approval.request", "{\"command\": \"ls\"}")))
        assertNull(chatEvent(SseEvent("approval.request", "{\"run_id\": \"r\"}")))
    }

    @Test
    fun finishReasonIsFinished() {
        val usage = ", \"usage\": {\"prompt_tokens\": 29521, \"completion_tokens\": 30, \"total_tokens\": 29551}"
        assertEquals(ChatEvent.Finished("stop"), chatEvent(chunk("{}", "\"stop\"", usage)))
    }

    @Test
    fun contentWithAFinishReasonIsText() {
        assertEquals(ChatEvent.Text("x"), chatEvent(chunk("{\"content\": \"x\"}", "\"stop\"")))
    }

    @Test
    fun doneIsDone() {
        assertEquals(ChatEvent.Done, chatEvent(SseEvent(null, "[DONE]")))
    }

    @Test
    fun badJsonIsIgnored() {
        assertNull(chatEvent(SseEvent(null, "{not json")))
        assertNull(chatEvent(SseEvent(null, "{\"choices\": []}")))
        assertNull(chatEvent(SseEvent(null, "{\"choices\": [1]}")))
        assertNull(chatEvent(SseEvent("approval.request", "nope")))
        assertNull(chatEvent(SseEvent("hermes.tool.progress", "nope")))
    }

    @Test
    fun multiByteTextSurvives() {
        assertEquals(ChatEvent.Text("héllo 👋"), chatEvent(chunk("{\"content\": \"héllo 👋\"}")))
    }
}
