package id.or.oo.hr.chat

import id.or.oo.hr.gateway.GatewayState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class ToolLine(val callId: String, val label: String, val running: Boolean)

data class Turn(
    val text: String,
    val tools: List<ToolLine>,
    val approval: ChatEvent.Approval?,
    val answering: Boolean,
)

data class ChatView(
    val conversation: Conversation,
    val turn: Turn?,
    val error: String?,
    val busyElsewhere: Boolean = false,
)

class ChatController(
    private val store: ConversationStore,
    private val client: ChatTurns,
    private val gateway: StateFlow<GatewayState>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    io: CoroutineDispatcher = Dispatchers.IO,
    private val gatewayWaitMs: Long = 3_000,
) {
    private class Active(
        val job: Job,
        val conversation: Conversation,
        val turnId: String,
        var turn: Turn,
        var unresolved: List<String>,
        var stopped: Boolean = false,
        var failed: Boolean = false,
    )

    private val lock = Any()
    private var active: Active? = null
    private var opened: Conversation? = null
    private var error: String? = null
    private var choice = 0L
    private val work = Channel<() -> Unit>(Channel.UNLIMITED)
    private val mutableConversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val mutableView = MutableStateFlow<ChatView?>(null)
    private val mutableRunningId = MutableStateFlow<String?>(null)

    val conversations: StateFlow<List<Conversation>> = mutableConversations
    val view: StateFlow<ChatView?> = mutableView
    val runningId: StateFlow<String?> = mutableRunningId

    init {
        work.trySend { listNow() }
        scope.launch(io) {
            for (op in work) runCatching { op() }
        }
    }

    fun refresh() {
        work.trySend { listNow() }
    }

    fun newChat() {
        synchronized(lock) {
            choice++
            opened = Conversation(newId(), "hr-" + newId(), NEW_TITLE, clock(), emptyList())
            error = null
            publish()
        }
    }

    fun open(id: String) {
        synchronized(lock) {
            val running = active?.conversation
            if (running?.id == id) {
                choice++
                opened = running
                error = null
                publish()
                return
            }
            val token = ++choice
            work.trySend {
                val loaded = try {
                    store.load(id)
                } catch (e: IllegalArgumentException) {
                    null
                }
                synchronized(lock) {
                    if (loaded != null && choice == token) {
                        opened = loaded
                        error = null
                        publish()
                    }
                }
            }
        }
    }

    fun close() {
        synchronized(lock) {
            choice++
            opened = null
            error = null
            publish()
        }
    }

    fun send(text: String): Boolean {
        val message = text.trim()
        if (message.isEmpty()) return false
        synchronized(lock) {
            val current = opened ?: return false
            if (active != null) return false
            if (gateway.value !is GatewayState.Running) {
                error = NOT_RUNNING
                publish()
                return false
            }
            val turnId = newId()
            val previous = current.pendingTurns
            val conversation = current.copy(
                title = if (current.messages.isEmpty()) titleFor(message) else current.title,
                updatedAt = clock(),
                messages = current.messages + ChatMessage(true, message),
                pendingTurns = (previous + turnId).takeLast(MAX_PENDING),
            )
            opened = conversation
            error = null
            val next = scope.launch(start = CoroutineStart.LAZY) {
                runTurn(coroutineContext[Job]!!, conversation, turnId, previous, message)
            }
            active = Active(next, conversation, turnId, Turn("", emptyList(), null, false), previous)
            publish()
            next.invokeOnCompletion { finish(next, null) }
            next.start()
        }
        return true
    }

    fun stop() {
        synchronized(lock) {
            val running = active ?: return
            running.stopped = true
            running.job.cancel()
        }
    }

    fun answer(choice: String) {
        val (me, approval) = synchronized(lock) {
            val running = active ?: return
            val approval = running.turn.approval ?: return
            if (running.turn.answering) return
            running.turn = running.turn.copy(answering = true)
            publish()
            running.job to approval
        }
        scope.launch {
            val ok = try {
                client.answer(approval.runId, approval.requestId, choice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            synchronized(lock) {
                val running = active
                if (running == null || running.job !== me || running.turn.approval !== approval) return@launch
                running.turn = running.turn.copy(approval = if (ok) null else approval, answering = false)
                if (ok && error == ANSWER_FAILED) error = null
                if (!ok && opened?.id == running.conversation.id) error = ANSWER_FAILED
                publish()
            }
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            if (active?.conversation?.id == id) return
            if (opened?.id == id) {
                choice++
                opened = null
                error = null
                publish()
            }
            work.trySend {
                try {
                    store.delete(id)
                } finally {
                    listNow()
                }
            }
        }
    }

    private suspend fun runTurn(me: Job, conversation: Conversation, turnId: String, previous: List<String>, text: String) {
        val outcome = try {
            val failure = save(conversation).await()
            if (failure != null) {
                TurnOutcome.Broken(failure)
            } else {
                resolve(me, previous)
                client.send(conversation.sessionId, turnId, text) { fold(me, it) }.also { awaitGatewayIfLost(me, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurnOutcome.Broken(REQUEST_FAILED)
        }
        finish(me, outcome)
    }

    private suspend fun resolve(me: Job, pending: List<String>) {
        for (turnId in pending) {
            val resolved = try {
                client.resolveLost(turnId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!resolved) return
            synchronized(lock) {
                val running = active ?: return
                if (running.job !== me) return
                running.unresolved = running.unresolved - turnId
            }
        }
    }

    private suspend fun awaitGatewayIfLost(me: Job, outcome: TurnOutcome) {
        if (outcome !is TurnOutcome.Broken && outcome != TurnOutcome.NoGateway) return
        synchronized(lock) {
            val running = active ?: return
            if (running.job !== me) return
            running.failed = true
        }
        if (outcome is TurnOutcome.Broken && !outcome.lost) return
        withTimeoutOrNull(gatewayWaitMs) { gateway.first { it !is GatewayState.Running } }
    }

    private fun fold(me: Job, event: ChatEvent) {
        synchronized(lock) {
            val running = active ?: return
            if (running.job !== me) return
            val turn = running.turn
            running.turn = when (event) {
                is ChatEvent.Text -> turn.copy(text = turn.text + event.delta, approval = null, answering = false)
                is ChatEvent.Tool -> turn.copy(tools = toolLines(turn.tools, event), approval = null, answering = false)
                is ChatEvent.Approval -> turn.copy(approval = event, answering = false)
                else -> return
            }
            publish()
        }
    }

    private fun toolLines(lines: List<ToolLine>, event: ChatEvent.Tool): List<ToolLine> {
        val at = lines.indexOfFirst { it.callId == event.callId }
        return when {
            event.running && at < 0 -> lines + ToolLine(event.callId, event.label, true)
            event.running -> lines.toMutableList().also { it[at] = ToolLine(event.callId, event.label, true) }
            at < 0 -> lines
            else -> lines.toMutableList().also { it[at] = it[at].copy(running = false) }
        }
    }

    private fun finish(me: Job, outcome: TurnOutcome?) {
        synchronized(lock) {
            val running = active ?: return
            if (running.job !== me) return
            active = null
            val (interrupted, reason) = if (running.stopped || outcome == null) true to null else ending(outcome, gateway.value is GatewayState.Running)
            val clears = !running.failed && (running.stopped || outcome == null || outcome is TurnOutcome.Finished)
            val pending = (running.unresolved + listOfNotNull(running.turnId.takeUnless { clears })).takeLast(MAX_PENDING)
            val text = running.turn.text.trim()
            val tools = running.turn.tools.map { it.label }
            val reply = if (text.isEmpty() && tools.isEmpty()) emptyList() else listOf(ChatMessage(false, text, tools, interrupted))
            val conversation = if (reply.isEmpty() && pending == running.conversation.pendingTurns) {
                running.conversation
            } else {
                running.conversation.copy(
                    updatedAt = clock(),
                    messages = running.conversation.messages + reply,
                    pendingTurns = pending,
                ).also { save(it) }
            }
            if (opened?.id == conversation.id) {
                opened = conversation
                error = reason
            }
            publish()
        }
    }

    private fun ending(outcome: TurnOutcome, gatewayUp: Boolean): Pair<Boolean, String?> = when (outcome) {
        is TurnOutcome.Finished -> false to if (outcome.reason == "stop") null else "the reply stopped early (${outcome.reason})"
        TurnOutcome.NoGateway -> true to if (gatewayUp) NOT_RUNNING else GATEWAY_STOPPED
        is TurnOutcome.Rejected -> true to if (outcome.code == 401) {
            "the gateway refused hr's key (HTTP 401)"
        } else {
            "the gateway refused the request (HTTP ${outcome.code}): ${outcome.body}"
        }
        is TurnOutcome.Broken -> true to if (gatewayUp) outcome.reason else GATEWAY_STOPPED
    }

    private fun save(conversation: Conversation): CompletableDeferred<String?> {
        val done = CompletableDeferred<String?>()
        work.trySend {
            val failure = try {
                store.save(conversation)
                null
            } catch (e: Exception) {
                "couldn't save the chat (${e.message ?: e.javaClass.simpleName})"
            }
            done.complete(failure)
            listNow()
            if (failure != null) {
                synchronized(lock) {
                    if (opened?.id == conversation.id) {
                        error = failure
                        publish()
                    }
                }
            }
        }
        return done
    }

    private fun listNow() {
        mutableConversations.value = store.list()
    }

    private fun publish() {
        val shown = opened
        val running = active?.takeIf { it.conversation.id == shown?.id }
        val elsewhere = active != null && running == null
        mutableRunningId.value = active?.conversation?.id
        mutableView.value = shown?.let { ChatView(running?.conversation ?: it, running?.turn, error, elsewhere) }
    }

    private companion object {
        const val NEW_TITLE = "New chat"
        const val NOT_RUNNING = "the gateway is not running"
        const val GATEWAY_STOPPED = "the gateway stopped during the reply"
        const val ANSWER_FAILED = "couldn't send the answer to Hermes"
        const val REQUEST_FAILED = "the request failed"
        const val MAX_PENDING = 10
    }
}
