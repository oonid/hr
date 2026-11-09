package id.or.oo.hr.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

@Composable
fun ChatConversation(app: HrApp, actions: HrActions, view: ChatView, modifier: Modifier) {
    val gateway by app.gateway.state.collectAsStateWithLifecycle()
    val messages = view.conversation.messages
    val turn = view.turn
    val list = rememberLazyListState()
    val count = messages.size + if (turn != null) 1 else 0
    val streamed = (turn?.text?.length ?: 0) + (turn?.tools?.size ?: 0)
    LaunchedEffect(count, streamed) {
        if (count > 0) list.scrollToItem(count - 1, Int.MAX_VALUE)
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
        if (gateway !is GatewayState.Running) GatewayBanner(gateway, actions)
        Input(app, view, gateway is GatewayState.Running)
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    val alignment = if (message.fromUser) Alignment.End else Alignment.Start
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Card {
            SelectionContainer {
                Column(modifier = Modifier.padding(10.dp)) {
                    if (message.tools.isNotEmpty()) {
                        Text(message.tools.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    }
                    val text = if (message.interrupted) "${message.text}\n(interrupted)".trim() else message.text
                    if (text.isNotEmpty()) Text(text)
                }
            }
        }
    }
}

@Composable
private fun Running(turn: Turn, app: HrApp) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card {
            SelectionContainer {
                Column(modifier = Modifier.padding(10.dp)) {
                    turn.tools.forEach { Text(toolText(it), style = MaterialTheme.typography.bodySmall) }
                    Text(turn.text.ifEmpty { "…" })
                }
            }
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

private fun toolText(line: ToolLine) = (if (line.running) "⏳ " else "✓ ") + line.label

@Composable
private fun GatewayBanner(state: GatewayState, actions: HrActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("The gateway is not running.")
            if (state is GatewayState.Starting) {
                Text("Starting the gateway…")
            } else {
                Button(onClick = actions.startGateway) { Text("Start") }
            }
        }
    }
}

@Composable
private fun Input(app: HrApp, view: ChatView, running: Boolean) {
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
                    if (app.chat.send(draft)) draft = ""
                },
                enabled = running && draft.isNotBlank() && !view.busyElsewhere,
            ) { Text("Send") }
        }
    }
}
