package id.or.oo.hr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.or.oo.hr.Hermes
import id.or.oo.hr.HrApp
import id.or.oo.hr.gateway.GatewayState
import id.or.oo.hr.gateway.describe
import id.or.oo.hr.image.ImageStore
import id.or.oo.hr.image.InstalledImage
import id.or.oo.hr.image.canStartSession
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(app: HrApp, actions: HrActions, modifier: Modifier, open: (Screen) -> Unit) {
    val installed by app.images.installed.collectAsStateWithLifecycle()
    val operation by app.images.operation.collectAsStateWithLifecycle()
    val state by app.gateway.state.collectAsStateWithLifecycle()
    val revision by app.gatewayLog.revision.collectAsStateWithLifecycle()
    val recent = remember(revision) { app.gatewayLog.tail(RECENT_LINES) }
    val ticking = state is GatewayState.Starting || state is GatewayState.Running
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticking) {
        while (true) {
            now = System.currentTimeMillis()
            if (!ticking) break
            delay(1_000)
        }
    }
    val canStart = canStartSession(operation)

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ImageCard(installed, operation, onInstall = { app.images.install() }, onDismiss = { app.images.dismiss() })
        if (installed != null) {
            GatewayCard(state, now, canStart, onStart = actions.startGateway, onStop = { app.gateway.stop() })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = actions.openTerminal, enabled = installed != null && canStart) { Text("Terminal") }
            OutlinedButton(onClick = { open(Screen.Settings) }) { Text("Settings") }
        }
        SessionBanners(app)
        if (recent.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Recent log", style = MaterialTheme.typography.titleSmall)
                    LogLines(recent)
                    TextButton(onClick = { open(Screen.Logs) }) { Text("Full log") }
                }
            }
        }
    }
}

@Composable
private fun ImageCard(
    installed: InstalledImage?,
    operation: ImageStore.Operation?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Hermes image", style = MaterialTheme.typography.titleSmall)
            if (installed != null) {
                Text(installed.buildTag)
            } else {
                Text("Hermes is not installed. The ${Hermes.PULL_TAG} image is a download of a few hundred MB.")
            }
            operation?.let { current ->
                LogLines(current.lines.takeLast(RECENT_LINES))
                if (!current.running) TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            if (installed == null && operation?.running != true) {
                Button(onClick = onInstall) { Text("Install") }
            }
        }
    }
}

@Composable
private fun GatewayCard(state: GatewayState, now: Long, canStart: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Gateway", style = MaterialTheme.typography.titleSmall)
            Text(describe(state, now))
            if (state is GatewayState.Running) {
                Text("Version ${state.version ?: "unknown"}")
                Text("API http://${Hermes.HOST}:${Hermes.PORT}")
            }
            if (state is GatewayState.StoppedUnexpectedly) LogLines(state.lastLines)
            when (state) {
                is GatewayState.Starting, is GatewayState.Running -> Button(onClick = onStop) { Text("Stop") }
                GatewayState.Stopping -> Button(onClick = {}, enabled = false) { Text("Stopping…") }
                is GatewayState.StoppedUnexpectedly -> Button(onClick = onStart, enabled = canStart) { Text("Restart") }
                is GatewayState.Stopped, is GatewayState.Failed -> Button(onClick = onStart, enabled = canStart) { Text("Start") }
            }
        }
    }
}

@Composable
fun LogLines(lines: List<String>) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        lines.forEach { line ->
            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val RECENT_LINES = 8
