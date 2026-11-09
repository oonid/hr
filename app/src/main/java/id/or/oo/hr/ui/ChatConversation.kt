package id.or.oo.hr.ui

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.or.oo.hr.HrApp
import id.or.oo.hr.chat.ChatMessage
import id.or.oo.hr.chat.ChatView
import id.or.oo.hr.chat.ToolLine
import id.or.oo.hr.chat.Turn
import id.or.oo.hr.gateway.GatewayState
import id.or.oo.hr.image.canStartSession

@Composable
fun ChatConversation(app: HrApp, actions: HrActions, view: ChatView, modifier: Modifier) {
    val gateway by app.gateway.state.collectAsStateWithLifecycle()
    val operation by app.images.operation.collectAsStateWithLifecycle()
    val messages = view.conversation.messages
    val turn = view.turn
    val conversationId = view.conversation.id
    val list = rememberLazyListState()
    val dragged = list.interactionSource.collectIsDraggedAsState()
    var follow by remember(conversationId) { mutableStateOf(true) }
    val lastIndex = messages.lastIndex + if (turn != null) 1 else 0
    LaunchedEffect(list, conversationId) {
        var gesture = false
        snapshotFlow { Triple(dragged.value, list.isScrollInProgress, list.canScrollForward) }
            .collect { (dragging, scrolling, more) ->
                if (dragging) gesture = true
                if (gesture) follow = !more
                if (!dragging && !scrolling) gesture = false
            }
    }
    LaunchedEffect(conversationId, lastIndex, turn?.text?.length, turn?.tools?.size, turn?.approval != null) {
        if (follow && lastIndex >= 0) list.scrollToItem(lastIndex, Int.MAX_VALUE)
    }
    Column(modifier = modifier.fillMaxSize().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(messages) { _, message -> Bubble(message) }
            if (turn != null) item { Running(turn, app) }
        }
        view.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (gateway !is GatewayState.Running) {
            GatewayBanner(gateway, actions, canStartSession(operation))
        }
        Input(app, view, gateway is GatewayState.Running) { follow = true }
    }
}

@Composable
private fun bubbleColors(fromUser: Boolean): CardColors {
    val scheme = MaterialTheme.colorScheme
    return if (fromUser) {
        CardDefaults.cardColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer)
    } else {
        CardDefaults.cardColors(containerColor = scheme.surfaceVariant, contentColor = scheme.onSurfaceVariant)
    }
}

@Composable
private fun BubbleCard(fromUser: Boolean, content: @Composable () -> Unit) {
    val alignment = if (fromUser) Alignment.End else Alignment.Start
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Box(
            modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH),
            contentAlignment = if (fromUser) Alignment.TopEnd else Alignment.TopStart,
        ) {
            Card(colors = bubbleColors(fromUser)) {
                SelectionContainer {
                    Column(modifier = Modifier.padding(10.dp)) { content() }
                }
            }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    BubbleCard(message.fromUser) {
        if (message.tools.isNotEmpty()) {
            Text(message.tools.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
        val text = if (message.interrupted) "${message.text}\n(interrupted)".trim() else message.text
        if (text.isNotEmpty()) Text(text)
    }
}

@Composable
private fun Running(turn: Turn, app: HrApp) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BubbleCard(fromUser = false) {
            turn.tools.forEach { Text(toolText(it), style = MaterialTheme.typography.bodySmall) }
            Text(turn.text.ifEmpty { "…" })
        }
        turn.approval?.let { approval ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Hermes wants to run:", style = MaterialTheme.typography.titleSmall)
                    Text(approval.command, fontFamily = FontFamily.Monospace)
                    if (approval.description.isNotEmpty()) Text(approval.description)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { app.chat.answer("once") }, enabled = !turn.answering) { Text("Allow once") }
                        OutlinedButton(onClick = { app.chat.answer("deny") }, enabled = !turn.answering) { Text("Deny") }
                    }
                }
            }
        }
    }
}

private const val BUBBLE_WIDTH = 0.85f

private fun toolText(line: ToolLine) = (if (line.running) "⏳ " else "✓ ") + line.label

@Composable
private fun GatewayBanner(state: GatewayState, actions: HrActions, canStart: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val message = when (state) {
                is GatewayState.Starting -> "Starting the gateway…"
                GatewayState.Stopping -> "Stopping the gateway…"
                is GatewayState.Failed -> "The gateway could not start: ${state.reason}"
                is GatewayState.StoppedUnexpectedly -> "The gateway stopped unexpectedly (exit ${state.exitCode ?: "unknown"})."
                else -> "The gateway is not running."
            }
            Text(message)
            if (state !is GatewayState.Starting && state != GatewayState.Stopping) {
                Button(onClick = actions.startGateway, enabled = canStart) { Text("Start") }
            }
        }
    }
}

@Composable
private fun Input(app: HrApp, view: ChatView, running: Boolean, onSent: () -> Unit) {
    var draft by rememberSaveable(view.conversation.id) { mutableStateOf("") }
    val busy = view.turn != null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5,
        )
        if (view.busyElsewhere) Text("Hermes is answering in another chat.", style = MaterialTheme.typography.bodySmall)
        if (busy) {
            OutlinedButton(onClick = { app.chat.stop() }) { Text("Stop") }
        } else {
            Button(
                onClick = {
                    if (app.chat.send(draft)) {
                        draft = ""
                        onSent()
                    }
                },
                enabled = running && draft.isNotBlank() && !view.busyElsewhere,
            ) { Text("Send") }
        }
    }
}
