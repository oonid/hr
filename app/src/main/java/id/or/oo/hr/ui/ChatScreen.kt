package id.or.oo.hr.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.or.oo.hr.HrApp
import id.or.oo.hr.chat.Conversation
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatScreen(app: HrApp, actions: HrActions, modifier: Modifier) {
    val view by app.chat.view.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.chat.refresh() }
    val current = view
    if (current == null) {
        ChatList(app, modifier)
    } else {
        ChatConversation(app, actions, current, modifier)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatList(app: HrApp, modifier: Modifier) {
    val conversations by app.chat.conversations.collectAsStateWithLifecycle()
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { app.chat.newChat() }) { Text("New chat") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations, key = Conversation::id) { conversation ->
                Card(
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { app.chat.open(conversation.id) },
                        onLongClick = { deleting = conversation.id },
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(conversation.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(conversation.updatedAt)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            confirmButton = {
                TextButton(onClick = {
                    app.chat.delete(id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
