package id.or.oo.hr.chat

import id.or.oo.hr.gateway.GatewayState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ChatControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(executor.asCoroutineDispatcher() + Job())
    private val gateway = MutableStateFlow<GatewayState>(GatewayState.Running(1L, "0.13.0"))
    private val ticks = AtomicLong(1_000L)
    private val ids = AtomicInteger()
    private var stubborn: TurnOutcome? = null

    private class Call(val sessionId: String, val turnId: String, val text: String, val onEvent: (ChatEvent) -> Unit) {
        val outcome = CompletableDeferred<TurnOutcome>()
        val cancelled = CompletableDeferred<Unit>()
    }

    private class Answer(val runId: String, val requestId: String?, val choice: String) {
        val result = CompletableDeferred<Boolean>()
    }

    private val calls = Channel<Call>(Channel.UNLIMITED)
    private val answers = Channel<Answer>(Channel.UNLIMITED)
    private val lost = Channel<String>(Channel.UNLIMITED)
    private val log = CopyOnWriteArrayList<String>()
    private var gatewayWaitMs = 20L

    @Volatile
    private var known: List<String> = emptyList()

    @Volatile
    private var resolution = CompletableDeferred(true)
    private val resolutions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val turns = object : ChatTurns {
        override suspend fun send(sessionId: String, turnId: String, text: String, onEvent: (ChatEvent) -> Unit): TurnOutcome {
            log += "send $turnId"
            val call = Call(sessionId, turnId, text, onEvent)
            calls.send(call)
            try {
                return call.outcome.await()
            } catch (e: CancellationException) {
                call.cancelled.complete(Unit)
                return stubborn ?: throw e
            }
        }

        override suspend fun answer(runId: String, requestId: String?, choice: String): Boolean {
            val answer = Answer(runId, requestId, choice)
            answers.send(answer)
            return answer.result.await()
        }

        override suspend fun resolveLost(turnId: String): Boolean {
            log += "resolve $turnId"
            lost.send(turnId)
            return (resolutions[turnId] ?: resolution).await()
        }
    }

    private var dir: File? = null
    private var failList = false
    private val store by lazy {
        object : ConversationStore(dir ?: File(tmp.root, "chats")) {
            override fun list(): List<Conversation> {
                if (failList) throw IOException("list failed")
                return super.list()
            }
        }
    }
    private val controller by lazy {
        ChatController(
            store,
            turns,
            gateway,
            scope,
            { ticks.incrementAndGet() },
            { "id-${ids.incrementAndGet()}" },
            Dispatchers.IO,
            gatewayWaitMs,
            secrets = { known },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    private fun drain() {
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun awaitView(match: (ChatView?) -> Boolean): ChatView? = runBlocking {
        withTimeout(5_000) { controller.view.first(match) }
    }

    private fun awaitList(match: (List<Conversation>) -> Boolean): List<Conversation> = runBlocking {
        withTimeout(5_000) { controller.conversations.first(match) }
    }

    private fun nextCall(): Call = runBlocking { withTimeout(5_000) { calls.receive() } }

    private fun nextAnswer(): Answer = runBlocking { withTimeout(5_000) { answers.receive() } }

    private fun nextLost(): String = runBlocking { withTimeout(5_000) { lost.receive() } }

    private fun awaitGatewayWait() {
        runBlocking { withTimeout(5_000) { gateway.subscriptionCount.first { it > 0 } } }
    }

    private fun lostTurn(chat: Conversation): String {
        controller.send("one")
        val call = nextCall()
        call.outcome.complete(TurnOutcome.Broken("x"))
        assertEquals(listOf(call.turnId), ended(chat.id, 1).conversation.pendingTurns)
        return call.turnId
    }

    private fun ended(id: String, size: Int): ChatView = awaitView {
        it != null && it.conversation.id == id && it.turn == null && it.conversation.messages.size == size
    }!!

    private fun persisted(view: ChatView): Conversation? {
        val c = view.conversation
        awaitList { l -> l.any { it.id == c.id && it.updatedAt == c.updatedAt } }
        return store.load(c.id)
    }

    private fun open(): Conversation {
        controller.newChat()
        return controller.view.value!!.conversation
    }

    private fun saved(id: String, title: String = "Saved $id"): Conversation {
        val c = Conversation(id, "hr-$id", title, 10L, listOf(ChatMessage(true, "old"), ChatMessage(false, "reply")))
        store.save(c)
        return c
    }

    @Test
    fun aTurnStreamsAndIsSaved() {
        val chat = open()
        assertEquals("id-1", chat.id)
        assertEquals("hr-id-2", chat.sessionId)
        assertEquals("New chat", chat.title)
        assertTrue(chat.messages.isEmpty())
        assertNull(store.load(chat.id))

        assertTrue(controller.send("  hi there  "))
        val call = nextCall()
        assertEquals("hr-id-2", call.sessionId)
        assertEquals("hi there", call.text)
        call.onEvent(ChatEvent.Text("\n\nHel"))
        call.onEvent(ChatEvent.Text("lo"))
        val streaming = awaitView { it?.turn?.text == "\n\nHello" }!!
        assertEquals(listOf(ChatMessage(true, "hi there")), streaming.conversation.messages)
        assertNull(streaming.error)

        call.outcome.complete(TurnOutcome.Finished("stop"))
        val done = ended(chat.id, 2)
        assertEquals(ChatMessage(false, "Hello"), done.conversation.messages[1])
        assertNull(done.error)
        assertEquals(done.conversation, persisted(done))
        assertEquals("hi there", controller.conversations.value.single().title)
    }

    @Test
    fun theUserMessageIsSavedBeforeTheReply() {
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        assertEquals(listOf(ChatMessage(true, "hi")), store.load(chat.id)!!.messages)
        assertEquals("hi", store.load(chat.id)!!.title)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        ended(chat.id, 1)
    }

    @Test
    fun blankReplyWithoutToolsSavesNothingButTheUserMessage() {
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("\n\n"))
        awaitView { it?.turn?.text == "\n\n" }
        call.outcome.complete(TurnOutcome.Finished("stop"))
        val done = ended(chat.id, 1)
        assertNull(done.error)
        assertEquals(listOf(ChatMessage(true, "hi")), store.load(chat.id)!!.messages)
    }

    @Test
    fun toolLinesFollowRunningAndCompleted() {
        val chat = open()
        controller.send("list files")
        val call = nextCall()
        call.onEvent(ChatEvent.Tool("c1", "💻 ls", true))
        call.onEvent(ChatEvent.Tool("c2", "read", true))
        awaitView { it?.turn?.tools?.size == 2 }
        call.onEvent(ChatEvent.Tool("c1", "", false))
        call.onEvent(ChatEvent.Tool("c2", "📖 read file", true))
        val view = awaitView { it?.turn?.tools?.firstOrNull()?.running == false && it.turn.tools.getOrNull(1)?.label == "📖 read file" }!!
        assertEquals(
            listOf(ToolLine("c1", "💻 ls", false), ToolLine("c2", "📖 read file", true)),
            view.turn!!.tools,
        )
        call.onEvent(ChatEvent.Text("\nHere they are"))
        call.onEvent(ChatEvent.Finished("stop"))
        call.onEvent(ChatEvent.Done)
        awaitView { it?.turn?.text == "\nHere they are" }
        call.outcome.complete(TurnOutcome.Finished("stop"))
        val done = ended(chat.id, 2)
        assertEquals(ChatMessage(false, "Here they are", listOf("💻 ls", "📖 read file")), done.conversation.messages[1])
        assertEquals(done.conversation, persisted(done))
    }

    @Test
    fun aCompletedToolForAnUnknownCallIsIgnored() {
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Tool("c1", "terminal", true))
        call.onEvent(ChatEvent.Tool("zz", "", false))
        call.onEvent(ChatEvent.Text("ok"))
        val view = awaitView { it?.turn?.text == "ok" }!!
        assertEquals(listOf(ToolLine("c1", "terminal", true)), view.turn!!.tools)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        assertEquals(listOf("terminal"), ended(chat.id, 2).conversation.messages[1].tools)
    }

    @Test
    fun aGatewayThatIsNotRunningSendsNothing() {
        gateway.value = GatewayState.Starting(5L)
        val chat = open()
        assertFalse(controller.send("hi"))
        val view = controller.view.value!!
        assertEquals("the gateway is not running", view.error)
        assertTrue(view.conversation.messages.isEmpty())
        assertNull(view.turn)
        assertNull(store.load(chat.id))
        assertTrue(calls.tryReceive().isFailure)

        gateway.value = GatewayState.Running(6L, null)
        assertTrue(controller.send("again"))
        assertEquals("again", nextCall().text)
        assertNull(controller.view.value!!.error)
    }

    @Test
    fun blankTextOrNoChatSendsNothing() {
        assertFalse(controller.send("hi"))
        assertNull(controller.view.value)
        open()
        assertFalse(controller.send("   \n "))
        assertTrue(controller.view.value!!.conversation.messages.isEmpty())
        assertNull(controller.view.value!!.turn)
        assertTrue(controller.send("real"))
        assertEquals("real", nextCall().text)
        assertFalse(controller.send("while running"))
        assertEquals("real", controller.view.value!!.conversation.messages.single().text)
    }

    @Test
    fun aListThatFailsAfterASaveDoesNotBlockTheTurn() {
        val chat = open()
        awaitList { true }
        drain()
        failList = true
        assertTrue(controller.send("hi"))
        val call = nextCall()
        call.onEvent(ChatEvent.Text("ok"))
        awaitView { it?.turn?.text == "ok" }
        call.outcome.complete(TurnOutcome.Finished("stop"))
        val done = ended(chat.id, 2)
        assertNull(done.error)
    }

    @Test
    fun runningIdNamesTheConversationBeingAnswered() {
        val chat = open()
        assertNull(controller.runningId.value)
        controller.send("hi")
        assertEquals(chat.id, controller.runningId.value)
        val call = nextCall()
        controller.newChat()
        assertEquals(chat.id, controller.runningId.value)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        runBlocking { withTimeout(5_000) { controller.runningId.first { it == null } } }
    }

    @Test
    fun textOrToolsFromHermesClearAStaleApproval() {
        open()
        controller.send("go")
        val call = nextCall()
        val approval = ChatEvent.Approval("run-1", "a", "rm x", "", listOf("once", "deny"))
        call.onEvent(approval)
        awaitView { it?.turn?.approval != null }
        call.onEvent(ChatEvent.Text("resolved"))
        val afterText = awaitView { it?.turn?.text == "resolved" }!!
        assertNull(afterText.turn!!.approval)
        assertFalse(afterText.turn!!.answering)
        call.onEvent(approval)
        awaitView { it?.turn?.approval != null }
        call.onEvent(ChatEvent.Tool("c1", "ls", true))
        assertNull(awaitView { it?.turn?.tools?.size == 1 }!!.turn!!.approval)
    }

    @Test
    fun aGatewayThatStopsMidReplySaysSo() {
        for (outcome in listOf(TurnOutcome.Broken("unexpected end of stream"), TurnOutcome.NoGateway)) {
            gateway.value = GatewayState.Running(1L, null)
            val chat = open()
            controller.send("hi")
            val call = nextCall()
            call.onEvent(ChatEvent.Text("part"))
            awaitView { it?.turn?.text == "part" }
            gateway.value = GatewayState.Stopped()
            call.outcome.complete(outcome)
            assertEquals("the gateway stopped during the reply", ended(chat.id, 2).error)
        }
    }

    @Test
    fun stopKeepsThePartialReplyAsInterrupted() {
        controller.stop()
        val chat = open()
        controller.send("tell me a story")
        val call = nextCall()
        call.onEvent(ChatEvent.Tool("c1", "search", true))
        call.onEvent(ChatEvent.Text("\nOnce upon"))
        awaitView { it?.turn?.text == "\nOnce upon" }
        controller.stop()
        runBlocking { withTimeout(5_000) { call.cancelled.await() } }
        val done = ended(chat.id, 2)
        assertEquals(ChatMessage(false, "Once upon", listOf("search"), true), done.conversation.messages[1])
        assertNull(done.error)
        assertEquals(done.conversation, persisted(done))

        controller.send("next")
        assertEquals("next", nextCall().text)
    }

    @Test
    fun aTurnStoppedBeforeItRunsStillEnds() {
        val gate = CountDownLatch(1)
        executor.execute { gate.await() }
        val chat = open()
        controller.send("hi")
        assertNotNull(controller.view.value!!.turn)
        controller.stop()
        gate.countDown()
        val done = ended(chat.id, 1)
        assertNull(done.error)
        assertTrue(calls.tryReceive().isFailure)
        controller.send("again")
        assertEquals("again", nextCall().text)
    }

    @Test
    fun aStopThatRacesTheEndStillCountsAsStopped() {
        stubborn = TurnOutcome.Finished("length")
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("all of it"))
        awaitView { it?.turn?.text == "all of it" }
        controller.stop()
        val done = ended(chat.id, 2)
        assertEquals(ChatMessage(false, "all of it", interrupted = true), done.conversation.messages[1])
        assertNull(done.error)
    }

    @Test
    fun aBrokenTurnKeepsItsTextAndShowsTheError() {
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("Half a rep"))
        awaitView { it?.turn?.text == "Half a rep" }
        call.outcome.complete(TurnOutcome.Broken("the reply ended early"))
        val done = ended(chat.id, 2)
        assertEquals(ChatMessage(false, "Half a rep", interrupted = true), done.conversation.messages[1])
        assertEquals("the reply ended early", done.error)
        assertEquals(done.conversation, persisted(done))
    }

    @Test
    fun a401SaysTheKeyWasRefused() {
        val chat = open()
        controller.send("hi")
        nextCall().outcome.complete(TurnOutcome.Rejected(401, "nope"))
        val done = ended(chat.id, 1)
        assertEquals("the gateway refused hr's key (HTTP 401)", done.error)
        assertEquals(listOf(ChatMessage(true, "hi")), store.load(chat.id)!!.messages)
    }

    @Test
    fun otherEndingsSayWhy() {
        val endings = listOf(
            TurnOutcome.NoGateway to "the gateway is not running",
            TurnOutcome.Rejected(500, "boom") to "the gateway refused the request (HTTP 500): boom",
            TurnOutcome.Finished("length") to "the reply stopped early (length)",
        )
        for ((outcome, error) in endings) {
            val chat = open()
            controller.send("hi")
            val call = nextCall()
            call.onEvent(ChatEvent.Text("part"))
            awaitView { it?.turn?.text == "part" }
            call.outcome.complete(outcome)
            val done = ended(chat.id, 2)
            assertEquals(error, done.error)
            assertEquals(outcome !is TurnOutcome.Finished, done.conversation.messages[1].interrupted)
        }
        val chat = open()
        controller.send("hi")
        nextCall().outcome.completeExceptionally(IOException("socket closed"))
        assertEquals("the request failed", ended(chat.id, 1).error)
    }

    @Test
    fun aFailedRequestKeepsItsMessageOutOfTheView() {
        val chat = open()
        controller.send("hi")
        nextCall().outcome.completeExceptionally(IllegalArgumentException("Bearer secret"))
        val done = ended(chat.id, 1)
        assertEquals("the request failed", done.error)
        assertFalse(done.error!!.contains("secret"))
    }

    @Test
    fun aTurnThatEndsElsewhereLeavesTheOpenViewAlone() {
        val other = saved("other")
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        val sentAt = store.load(chat.id)!!.updatedAt
        call.onEvent(ChatEvent.Text("partial"))
        awaitView { it?.turn?.text == "partial" }
        controller.open(other.id)
        awaitView { it?.conversation?.id == other.id }
        call.outcome.complete(TurnOutcome.Broken("x"))
        awaitList { l -> l.any { it.id == chat.id && it.updatedAt > sentAt } }
        val view = controller.view.value!!
        assertEquals(ChatView(other, null, null), view)
        assertEquals(ChatMessage(false, "partial", interrupted = true), store.load(chat.id)!!.messages[1])
    }

    @Test
    fun aFailedAnswerElsewhereLeavesTheOpenViewAlone() {
        val other = saved("other")
        open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Approval("run-1", "a", "one", "", listOf("once")))
        awaitView { it?.turn?.approval != null }
        controller.answer("once")
        val answer = nextAnswer()
        controller.open(other.id)
        awaitView { it?.conversation?.id == other.id }
        answer.result.complete(false)
        drain()
        assertEquals(ChatView(other, null, null, busyElsewhere = true), controller.view.value)
    }

    @Test
    fun theListIsReadWhenTheControllerStarts() {
        saved("a")
        assertEquals(listOf("a"), awaitList { it.isNotEmpty() }.map { it.id })
    }

    @Test
    fun aTurnInAnotherChatMakesTheViewBusyElsewhere() {
        val other = saved("other")
        val chat = open()
        assertFalse(controller.view.value!!.busyElsewhere)
        controller.send("hi")
        val call = nextCall()
        assertFalse(controller.view.value!!.busyElsewhere)
        controller.newChat()
        assertTrue(controller.view.value!!.busyElsewhere)
        controller.open(chat.id)
        assertFalse(controller.view.value!!.busyElsewhere)
        controller.open(other.id)
        assertTrue(awaitView { it?.conversation?.id == other.id }!!.busyElsewhere)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        awaitView { it?.busyElsewhere == false }
        assertEquals(other.id, controller.view.value!!.conversation.id)
    }

    @Test
    fun theTurnSurvivesOpeningAnotherConversation() {
        val other = saved("other")
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("one"))
        awaitView { it?.turn?.text == "one" }

        controller.open(other.id)
        val shown = awaitView { it?.conversation?.id == other.id }!!
        assertEquals(other, shown.conversation)
        assertNull(shown.turn)
        call.onEvent(ChatEvent.Text(" two"))
        controller.open(chat.id)
        val back = controller.view.value!!
        assertEquals(chat.id, back.conversation.id)
        assertEquals(listOf(ChatMessage(true, "hi")), back.conversation.messages)
        assertEquals("one two", back.turn!!.text)

        controller.newChat()
        assertNull(controller.view.value!!.turn)
        controller.close()
        assertNull(controller.view.value)
        call.onEvent(ChatEvent.Text(" three"))
        call.outcome.complete(TurnOutcome.Finished("stop"))
        controller.open(chat.id)
        val reopened = ended(chat.id, 2)
        assertEquals(ChatMessage(false, "one two three"), reopened.conversation.messages[1])
        assertEquals(reopened.conversation, persisted(reopened))
        assertEquals(setOf(chat.id, other.id), controller.conversations.value.map { it.id }.toSet())
    }

    @Test
    fun theTurnEndsIntoTheOpenCopyOfItsConversation() {
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        controller.close()
        controller.open(chat.id)
        assertNotNull(controller.view.value!!.turn)
        call.outcome.complete(TurnOutcome.Broken("gone"))
        val done = ended(chat.id, 1)
        assertEquals("gone", done.error)
    }

    @Test
    fun openingAnUnknownConversationChangesNothing() {
        saved("drop")
        val chat = open()
        controller.open("missing")
        controller.open("not a valid id!")
        controller.delete("drop")
        awaitList { l -> l.none { it.id == "drop" } }
        assertEquals(chat, controller.view.value!!.conversation)
    }

    @Test
    fun aLateOpenDoesNotReplaceALaterChoice() {
        val first = saved("first")
        controller.open(first.id)
        controller.close()
        controller.refresh()
        awaitList { l -> l.any { it.id == first.id } }
        assertNull(controller.view.value)
    }

    @Test
    fun anApprovalIsAnsweredAndCleared() {
        val chat = open()
        controller.answer("once")
        controller.send("delete tmp")
        val call = nextCall()
        val approval = ChatEvent.Approval("run-1", "req-1", "rm -rf /tmp/x", "dangerous", listOf("once", "deny"))
        call.onEvent(approval)
        assertEquals(approval, awaitView { it?.turn?.approval != null }!!.turn!!.approval)

        controller.answer("once")
        assertTrue(controller.view.value!!.turn!!.answering)
        val answer = nextAnswer()
        assertEquals("run-1", answer.runId)
        assertEquals("req-1", answer.requestId)
        assertEquals("once", answer.choice)
        controller.answer("deny")
        answer.result.complete(true)
        val cleared = awaitView { it?.turn?.approval == null }!!
        assertFalse(cleared.turn!!.answering)
        assertNull(cleared.error)
        assertTrue(answers.tryReceive().isFailure)

        call.outcome.complete(TurnOutcome.Finished("stop"))
        ended(chat.id, 1)
    }

    @Test
    fun aFailedApprovalAnswerKeepsTheCard() {
        open()
        controller.send("delete tmp")
        val call = nextCall()
        val approval = ChatEvent.Approval("run-1", null, "rm x", "", listOf("once", "deny"))
        call.onEvent(approval)
        awaitView { it?.turn?.approval != null }
        controller.answer("deny")
        val answer = nextAnswer()
        assertNull(answer.requestId)
        answer.result.complete(false)
        val kept = awaitView { it?.error != null }!!
        assertEquals("couldn't send the answer to Hermes", kept.error)
        assertEquals(approval, kept.turn!!.approval)
        assertFalse(kept.turn.answering)

        controller.answer("deny")
        nextAnswer().result.complete(true)
        val cleared = awaitView { it?.turn?.approval == null }!!
        assertNull(cleared.error)
        assertFalse(cleared.turn!!.answering)
    }

    @Test
    fun anAnswerThatThrowsCountsAsNotSent() {
        open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Approval("run-1", "a", "one", "", listOf("once")))
        awaitView { it?.turn?.approval != null }
        controller.answer("once")
        nextAnswer().result.completeExceptionally(IOException("refused"))
        val kept = awaitView { it?.error != null }!!
        assertEquals("couldn't send the answer to Hermes", kept.error)
        assertNotNull(kept.turn!!.approval)
        assertTrue(scope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun anAnswerForAReplacedApprovalIsDropped() {
        open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Approval("run-1", "a", "one", "", listOf("once")))
        awaitView { it?.turn?.approval?.requestId == "a" }
        controller.answer("once")
        val answer = nextAnswer()
        val second = ChatEvent.Approval("run-1", "b", "two", "", listOf("once"))
        call.onEvent(second)
        awaitView { it?.turn?.approval?.requestId == "b" }
        answer.result.complete(true)
        drain()
        val view = controller.view.value!!
        assertEquals(second, view.turn!!.approval)
        assertFalse(view.turn.answering)
    }

    @Test
    fun oneTurnAtATime() {
        val first = open()
        controller.send("first")
        val call = nextCall()
        controller.send("again")
        assertEquals(listOf(ChatMessage(true, "first")), controller.view.value!!.conversation.messages)

        open()
        controller.send("second")
        assertTrue(controller.view.value!!.conversation.messages.isEmpty())
        assertNull(controller.view.value!!.turn)
        call.onEvent(ChatEvent.Text("r"))
        call.outcome.complete(TurnOutcome.Finished("stop"))
        controller.open(first.id)
        ended(first.id, 2)

        val third = open()
        controller.send("third")
        val next = nextCall()
        assertEquals("third", next.text)
        assertEquals(third.sessionId, next.sessionId)
    }

    @Test
    fun theFirstMessageNamesTheConversation() {
        val chat = open()
        val long = "  Bagaimana cara\nmemasang paket di Alpine tanpa root?  "
        controller.send(long)
        nextCall().outcome.complete(TurnOutcome.Finished("stop"))
        assertEquals(titleFor(long), ended(chat.id, 1).conversation.title)
        controller.send("and another")
        nextCall().outcome.complete(TurnOutcome.Finished("stop"))
        assertEquals(titleFor(long), ended(chat.id, 2).conversation.title)
        assertEquals(titleFor(long), store.load(chat.id)!!.title)

        val old = saved("old", "Kept")
        controller.open(old.id)
        awaitView { it?.conversation?.id == old.id }
        controller.send("more")
        nextCall().outcome.complete(TurnOutcome.Finished("stop"))
        assertEquals("Kept", ended(old.id, 3).conversation.title)
    }

    @Test
    fun deletingTheRunningConversationIsRefused() {
        val other = saved("other")
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        controller.delete(chat.id)
        controller.delete(other.id)
        val list = awaitList { l -> l.none { it.id == other.id } }
        assertEquals(listOf(chat.id), list.map { it.id })
        assertNotNull(store.load(chat.id))
        assertNotNull(controller.view.value!!.turn)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        ended(chat.id, 1)

        controller.delete(chat.id)
        awaitList { it.isEmpty() }
        assertNull(controller.view.value)
        assertNull(store.load(chat.id))
    }

    @Test
    fun refreshListsWhatIsSaved() {
        saved("a")
        saved("b")
        controller.refresh()
        assertEquals(setOf("a", "b"), awaitList { it.size == 2 }.map { it.id }.toSet())
    }

    @Test
    fun aChatThatCannotBeSavedIsNotSent() {
        dir = tmp.newFile("not-a-dir")
        val chat = open()
        controller.send("hi")
        val done = awaitView { it?.turn == null && it?.error != null }!!
        assertTrue(done.error!!.startsWith("couldn't save the chat"))
        assertEquals(chat.id, done.conversation.id)
        assertTrue(calls.tryReceive().isFailure)
    }

    @Test
    fun aBrokenTurnLeavesItsTurnPending() {
        for (outcome in listOf(TurnOutcome.Broken("x"), TurnOutcome.NoGateway, TurnOutcome.Rejected(500, "boom"))) {
            val chat = open()
            controller.send("hi")
            val call = nextCall()
            assertEquals(listOf(call.turnId), store.load(chat.id)!!.pendingTurns)
            call.onEvent(ChatEvent.Text("part"))
            awaitView { it?.turn?.text == "part" }
            call.outcome.complete(outcome)
            val done = ended(chat.id, 2)
            assertEquals(listOf(call.turnId), done.conversation.pendingTurns)
            assertEquals(listOf(call.turnId), persisted(done)!!.pendingTurns)
        }
    }

    @Test
    fun aFinishedTurnClearsIt() {
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        assertEquals(listOf(call.turnId), store.load(chat.id)!!.pendingTurns)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        val done = ended(chat.id, 1)
        assertEquals(emptyList<String>(), done.conversation.pendingTurns)
        assertEquals(done.conversation, persisted(done))
        assertEquals(listOf(ChatMessage(true, "hi")), done.conversation.messages)
    }

    @Test
    fun stopClearsIt() {
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        assertEquals(listOf(call.turnId), store.load(chat.id)!!.pendingTurns)
        call.onEvent(ChatEvent.Text("part"))
        awaitView { it?.turn?.text == "part" }
        controller.stop()
        val done = ended(chat.id, 2)
        assertEquals(emptyList<String>(), done.conversation.pendingTurns)
        assertEquals(emptyList<String>(), persisted(done)!!.pendingTurns)
    }

    @Test
    fun theNextSendResolvesTheLostTurnFirst() {
        val chat = open()
        val old = lostTurn(chat)
        val gate = CompletableDeferred<Boolean>()
        resolution = gate
        controller.send("two")
        assertEquals(old, nextLost())
        assertTrue(calls.tryReceive().isFailure)
        gate.complete(true)
        val call = nextCall()
        assertEquals(listOf("send $old", "resolve $old", "send ${call.turnId}"), log.toList())
        assertEquals(listOf(old, call.turnId), store.load(chat.id)!!.pendingTurns)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        val done = ended(chat.id, 2)
        assertEquals(emptyList<String>(), done.conversation.pendingTurns)
        assertEquals(done.conversation, persisted(done))
    }

    @Test
    fun aLostTurnThatCannotBeResolvedStaysPending() {
        val chat = open()
        val old = lostTurn(chat)
        resolution = CompletableDeferred(false)
        controller.send("two")
        assertEquals(old, nextLost())
        nextCall().outcome.complete(TurnOutcome.Finished("stop"))
        val done = ended(chat.id, 2)
        assertEquals(listOf(old), done.conversation.pendingTurns)
        assertEquals(done.conversation, persisted(done))
    }

    @Test
    fun aStopWhileResolvingKeepsTheLostTurn() {
        val chat = open()
        val old = lostTurn(chat)
        resolution = CompletableDeferred()
        controller.send("two")
        assertEquals(old, nextLost())
        controller.stop()
        val done = ended(chat.id, 2)
        assertEquals(listOf(old), done.conversation.pendingTurns)
        assertEquals(done.conversation, persisted(done))
        assertTrue(calls.tryReceive().isFailure)
    }

    @Test
    fun aLostConnectionStillWaits() {
        gatewayWaitMs = 30_000
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("part"))
        awaitView { it?.turn?.text == "part" }
        call.outcome.complete(TurnOutcome.Broken("unexpected end of stream", lost = true))
        awaitGatewayWait()
        assertNotNull(controller.view.value!!.turn)
        gateway.value = GatewayState.StoppedUnexpectedly(137, emptyList())
        val done = ended(chat.id, 2)
        assertEquals("the gateway stopped during the reply", done.error)
        assertEquals(ChatMessage(false, "part", interrupted = true), done.conversation.messages[1])
    }

    @Test
    fun aStopDuringTheGatewayWaitEndsAtOnceAndKeepsTheTurnPending() {
        gatewayWaitMs = 30_000
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("part"))
        awaitView { it?.turn?.text == "part" }
        call.outcome.complete(TurnOutcome.NoGateway)
        awaitGatewayWait()
        controller.stop()
        val done = ended(chat.id, 2)
        assertNull(done.error)
        assertEquals(listOf(call.turnId), done.conversation.pendingTurns)
        assertEquals(listOf(call.turnId), persisted(done)!!.pendingTurns)
    }

    @Test
    fun aResolveThatThrowsStillSends() {
        val chat = open()
        val old = lostTurn(chat)
        resolution = CompletableDeferred<Boolean>().apply { completeExceptionally(IOException("boom")) }
        controller.send("two")
        assertEquals(old, nextLost())
        nextCall().outcome.complete(TurnOutcome.Finished("stop"))
        assertEquals(listOf(old), ended(chat.id, 2).conversation.pendingTurns)
    }

    @Test
    fun aResolvedLostTurnGivesWayToANewBrokenOne() {
        val chat = open()
        val old = lostTurn(chat)
        controller.send("two")
        assertEquals(old, nextLost())
        val call = nextCall()
        call.outcome.complete(TurnOutcome.Broken("y"))
        val done = ended(chat.id, 2)
        assertEquals(listOf(call.turnId), done.conversation.pendingTurns)
        assertEquals(done.conversation, persisted(done))
    }

    private fun savedWithLost(id: String, vararg pending: String): Conversation {
        val c = saved(id).copy(pendingTurns = pending.toList())
        store.save(c)
        controller.open(id)
        awaitView { it?.conversation?.id == id }
        return c
    }

    @Test
    fun twoLostTurnsAreBothResolvedBeforeTheNextSend() {
        val chat = savedWithLost("two", "lost-a", "lost-b")
        val gate = CompletableDeferred<Boolean>()
        resolution = gate
        controller.send("next")
        assertEquals("lost-a", nextLost())
        assertEquals(listOf("lost-a", "lost-b", "id-1"), store.load(chat.id)!!.pendingTurns)
        assertTrue(lost.tryReceive().isFailure)
        gate.complete(true)
        assertEquals("lost-b", nextLost())
        val call = nextCall()
        assertEquals(listOf("resolve lost-a", "resolve lost-b", "send ${call.turnId}"), log.toList())
        call.outcome.complete(TurnOutcome.Finished("stop"))
        val done = ended(chat.id, 3)
        assertEquals(emptyList<String>(), done.conversation.pendingTurns)
        assertEquals(done.conversation, persisted(done))
    }

    @Test
    fun aResolvedTurnIsDroppedAndAnUnresolvedOneKept() {
        val chat = savedWithLost("mixed", "lost-a", "lost-b", "lost-c")
        resolutions["lost-a"] = CompletableDeferred(true)
        resolutions["lost-b"] = CompletableDeferred(false)
        controller.send("next")
        val call = nextCall()
        assertEquals(listOf("resolve lost-a", "resolve lost-b", "send ${call.turnId}"), log.toList())
        call.outcome.complete(TurnOutcome.Broken("x"))
        val done = ended(chat.id, 3)
        assertEquals(listOf("lost-b", "lost-c", call.turnId), done.conversation.pendingTurns)
        assertEquals(done.conversation, persisted(done))
    }

    @Test
    fun aStopWhileResolvingKeepsEveryUnresolvedTurn() {
        val chat = savedWithLost("stopped", "lost-a", "lost-b", "lost-c")
        resolutions["lost-a"] = CompletableDeferred(true)
        resolutions["lost-b"] = CompletableDeferred()
        controller.send("next")
        assertEquals("lost-a", nextLost())
        assertEquals("lost-b", nextLost())
        controller.stop()
        val done = ended(chat.id, 3)
        assertEquals(listOf("lost-b", "lost-c"), done.conversation.pendingTurns)
        assertEquals(done.conversation, persisted(done))
        assertTrue(calls.tryReceive().isFailure)
    }

    @Test
    fun atMostTenLostTurnsAreKept() {
        val old = (1..10).map { "lost-$it" }.toTypedArray()
        val chat = savedWithLost("full", *old)
        resolution = CompletableDeferred(false)
        controller.send("next")
        val call = nextCall()
        assertEquals(old.drop(1) + call.turnId, store.load(chat.id)!!.pendingTurns)
        call.outcome.complete(TurnOutcome.Rejected(500, "boom"))
        val done = ended(chat.id, 3)
        assertEquals(old.drop(1) + call.turnId, done.conversation.pendingTurns)
        assertEquals(done.conversation, persisted(done))
    }

    @Test
    fun aReadTimeoutDoesNotWaitForTheGateway() {
        gatewayWaitMs = 30_000
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("part"))
        awaitView { it?.turn?.text == "part" }
        call.outcome.complete(TurnOutcome.Broken("Read timed out"))
        val done = ended(chat.id, 2)
        assertEquals("Read timed out", done.error)
        assertEquals(listOf(call.turnId), done.conversation.pendingTurns)
        assertEquals(0, gateway.subscriptionCount.value)
    }

    @Test
    fun aSecretSplitAcrossDeltasIsNeverShown() {
        known = listOf("hrsecret12345")
        val chat = open()
        controller.send("what is it")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("key is hrsecret"))
        assertEquals("key is ", controller.view.value!!.turn!!.text)
        call.onEvent(ChatEvent.Text("12345 done"))
        assertEquals("key is [redacted] done", controller.view.value!!.turn!!.text)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        val done = ended(chat.id, 2)
        assertEquals(ChatMessage(false, "key is [redacted] done"), done.conversation.messages[1])
        assertEquals(done.conversation, persisted(done))
    }

    @Test
    fun aSecretStreamedOneCharacterAtATimeIsNeverShown() {
        known = listOf("hrsecret12345", "other-secret-value")
        val chat = open()
        controller.send("go")
        val call = nextCall()
        val shown = mutableListOf<String>()
        for (c in "key is hrsecret12345 then other-secret-value.") {
            call.onEvent(ChatEvent.Text(c.toString()))
            shown += controller.view.value!!.turn!!.text
        }
        assertTrue(shown.none { it.contains("hr") || it.contains("other-") })
        assertEquals("key is [redacted] then [redacted].", shown.last())
        call.outcome.complete(TurnOutcome.Finished("stop"))
        assertEquals("key is [redacted] then [redacted].", ended(chat.id, 2).conversation.messages[1].text)
    }

    @Test
    fun aPrefixThatIsNotASecretIsReleased() {
        known = listOf("hrsecret12345")
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("a hrsec"))
        assertEquals("a ", controller.view.value!!.turn!!.text)
        call.onEvent(ChatEvent.Text("tion"))
        assertEquals("a hrsection", controller.view.value!!.turn!!.text)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        assertEquals(ChatMessage(false, "a hrsection"), ended(chat.id, 2).conversation.messages[1])
    }

    @Test
    fun aHeldPrefixIsFlushedWhenTheTurnEnds() {
        known = listOf("hrsecret12345")
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("ends with hrsec"))
        assertEquals("ends with ", controller.view.value!!.turn!!.text)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        assertEquals(ChatMessage(false, "ends with hrsec"), ended(chat.id, 2).conversation.messages[1])
    }

    @Test
    fun aShortWordIsNotMangled() {
        known = listOf("hrsecret12345", "abc1234")
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("a secret word: abc"))
        assertEquals("a secret word: abc", controller.view.value!!.turn!!.text)
        call.onEvent(ChatEvent.Text("1234 and 12345"))
        assertEquals("a secret word: abc1234 and 12345", controller.view.value!!.turn!!.text)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        assertEquals(ChatMessage(false, "a secret word: abc1234 and 12345"), ended(chat.id, 2).conversation.messages[1])
    }

    @Test
    fun aSecretAddedMidTurnIsRedactedFromThenOn() {
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("token "))
        known = listOf("fresh-token-value")
        call.onEvent(ChatEvent.Text("fresh-token-value ok"))
        assertEquals("token [redacted] ok", controller.view.value!!.turn!!.text)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        assertEquals(ChatMessage(false, "token [redacted] ok"), ended(chat.id, 2).conversation.messages[1])
    }

    @Test
    fun anOldChatIsShownRedacted() {
        known = listOf("hrsecret12345")
        val old = Conversation(
            "old",
            "hr-old",
            "key hrsecret12345",
            10L,
            listOf(ChatMessage(true, "use hrsecret12345"), ChatMessage(false, "done hrsecret12345", listOf("💻 echo hrsecret12345", "📖 read"))),
        )
        store.save(old)
        val file = File(tmp.root, "chats/old.json")
        val before = file.readText()
        controller.open(old.id)
        val view = awaitView { it?.conversation?.id == old.id }!!
        assertEquals(
            old.copy(
                title = "key [redacted]",
                messages = listOf(ChatMessage(true, "use [redacted]"), ChatMessage(false, "done [redacted]", listOf("💻 echo [redacted]", "📖 read"))),
            ),
            view.conversation,
        )
        assertEquals("key [redacted]", awaitList { l -> l.any { it.id == old.id } }.single().title)
        assertEquals(before, file.readText())
        assertEquals(old, store.load(old.id))
    }

    @Test
    fun aTypedSecretIsSavedAsTypedButShownRedacted() {
        known = listOf("hrsecret12345")
        val chat = open()
        controller.send("my key is hrsecret12345")
        val call = nextCall()
        val view = controller.view.value!!
        assertEquals(listOf(ChatMessage(true, "my key is [redacted]")), view.conversation.messages)
        assertEquals("my key is [redacted]", view.conversation.title)
        val saved = store.load(chat.id)!!
        assertEquals(listOf(ChatMessage(true, "my key is hrsecret12345")), saved.messages)
        assertEquals("my key is [redacted]", saved.title)
        call.outcome.complete(TurnOutcome.Finished("stop"))
        ended(chat.id, 1)
    }

    @Test
    fun aLongTitleDoesNotCutASecretOpen() {
        known = listOf("hrsecret12345")
        val chat = open()
        controller.send("a message long enough to cut hrsecret12345 in two")
        val call = nextCall()
        assertFalse(store.load(chat.id)!!.title.contains("hrsec"))
        call.outcome.complete(TurnOutcome.Finished("stop"))
        ended(chat.id, 1)
    }

    @Test
    fun aTurnStoppedMidSecretSavesNoLongPrefixOfIt() {
        known = listOf("hrsecret12345")
        val chat = open()
        controller.send("hi")
        val call = nextCall()
        call.onEvent(ChatEvent.Text("key is hrsecret12"))
        assertEquals("key is ", controller.view.value!!.turn!!.text)
        controller.stop()
        val done = ended(chat.id, 2)
        assertEquals(ChatMessage(false, "key is [redacted]", interrupted = true), done.conversation.messages[1])
        assertEquals(done.conversation, persisted(done))
        assertFalse(store.load(chat.id)!!.messages[1].text.contains("hrsecret"))
    }
}
