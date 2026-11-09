package id.or.oo.hr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.or.oo.hr.HrApp

class HrActions(
    val startGateway: () -> Unit,
    val openTerminal: () -> Unit,
    val askNotifications: () -> Unit,
    val askNotificationsOnce: () -> Unit,
)

enum class Screen(val title: String) { Home("hr"), Logs("Gateway log"), Settings("Settings"), Setup("Setup"), Chat("Chat") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HrScreens(app: HrApp, actions: HrActions) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    val chatView by app.chat.view.collectAsStateWithLifecycle()
    val back = {
        if (screen == Screen.Chat && chatView != null) app.chat.close() else screen = Screen.Home
    }
    BackHandler(enabled = screen != Screen.Home, onBack = back)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (screen == Screen.Chat) chatView?.conversation?.title ?: screen.title else screen.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (screen != Screen.Home) TextButton(onClick = { back() }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding)
        when (screen) {
            Screen.Home -> HomeScreen(app, actions, modifier) { screen = it }
            Screen.Logs -> LogsScreen(app, modifier)
            Screen.Settings -> SettingsScreen(app, actions, modifier)
            Screen.Setup -> SetupScreen(app, actions, modifier)
            Screen.Chat -> ChatScreen(app, actions, modifier)
        }
    }
}
