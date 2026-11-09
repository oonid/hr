package id.or.oo.hr.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.or.oo.hr.HrApp
import id.or.oo.hr.profile.SetupStatus
import id.or.oo.hr.setup.LoginState
import id.or.oo.hr.setup.SetupController
import id.or.oo.hr.setup.SetupStep
import id.or.oo.hr.setup.chatGptButton
import id.or.oo.hr.setup.chatGptLine
import id.or.oo.hr.setup.TestResult
import kotlinx.coroutines.launch

private const val ADVANCED =
    "Open the terminal and run `hermes setup` for other providers and platforms, or `hermes auth add nous` " +
        "to sign in to Nous Portal. Commands such as `hermes chat` start a gateway of their own; while it runs, " +
        "hr cannot start its gateway. Use Test here instead."

@Composable
fun SetupScreen(app: HrApp, actions: HrActions, modifier: Modifier) {
    val controller = app.setup
    val step by controller.step.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        controller.refreshStatus()
        onPauseOrDispose { }
    }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val current = step) {
            SetupStep.Choose -> Choose(status, controller, actions)
            is SetupStep.ChatGpt -> ChatGptStep(current, controller, actions)
            SetupStep.Saving -> Busy("Saving…")
            SetupStep.Testing -> Busy("Restarting the gateway and sending a test request…")
            is SetupStep.Saved -> Finished(current.message, false, controller, actions)
            is SetupStep.Done -> Finished(describe(current.result), true, controller, actions)
            is SetupStep.Error -> Finished(current.reason, true, controller, actions)
        }
    }
}

@Composable
private fun Choose(status: SetupStatus, controller: SetupController, actions: HrActions) {
    Text("ChatGPT", style = MaterialTheme.typography.titleSmall)
    Text(chatGptLine(status))
    Button(onClick = {
        actions.askNotificationsOnce()
        controller.chooseChatGpt()
    }) { Text(chatGptButton(status)) }
    ApiKeyForm(status, controller, actions)
    TelegramForm(status, controller, actions)
    Text("Test", style = MaterialTheme.typography.titleSmall)
    Button(onClick = {
        actions.askNotificationsOnce()
        controller.test()
    }) { Text("Test") }
    Text("Advanced", style = MaterialTheme.typography.titleSmall)
    Text(ADVANCED)
    OutlinedButton(onClick = actions.openTerminal) { Text("Terminal") }
}

@Composable
private fun ChatGptStep(step: SetupStep.ChatGpt, controller: SetupController, actions: HrActions) {
    when (val login = step.login) {
        LoginState.Idle, LoginState.Starting -> {
            Busy("Checking your ChatGPT sign-in…")
            OutlinedButton(onClick = controller::cancelLogin) { Text("Cancel") }
        }
        is LoginState.Waiting -> Waiting(login, controller)
        LoginState.LoggedIn -> LoggedIn(step, controller, actions)
        LoginState.Cancelled -> Retry("Sign-in cancelled.", controller, actions)
        is LoginState.Failed -> Retry(login.reason, controller, actions)
    }
    if (step.login !is LoginState.Waiting) OutlinedButton(onClick = controller::back) { Text("Back") }
}

@Composable
private fun Waiting(login: LoginState.Waiting, controller: SetupController) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var noBrowser by remember { mutableStateOf(false) }
    Button(onClick = {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(login.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            noBrowser = true
        }
    }) { Text("Open ${login.url}") }
    if (noBrowser) Text("No browser found. Open ${login.url} on another device and enter the code.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(login.code, fontFamily = FontFamily.Monospace, fontSize = 28.sp)
        OutlinedButton(onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", login.code))) }
        }) { Text("Copy") }
    }
    Text("Waiting for you to approve…")
    OutlinedButton(onClick = controller::cancelLogin) { Text("Cancel") }
}

@Composable
private fun LoggedIn(step: SetupStep.ChatGpt, controller: SetupController, actions: HrActions) {
    if (step.loadingModels) {
        Busy("Reading your models…")
        return
    }
    var model by rememberSaveable { mutableStateOf(step.models?.firstOrNull().orEmpty()) }
    if (step.models != null) {
        Dropdown("Model", step.models, model) { model = it }
    } else {
        FormField("Model", model) { model = it }
    }
    Button(onClick = {
        actions.askNotificationsOnce()
        controller.saveCodexModel(model.trim())
    }, enabled = model.isNotBlank()) { Text("Save") }
}

@Composable
private fun Retry(message: String, controller: SetupController, actions: HrActions) {
    Text(message)
    Button(onClick = {
        actions.askNotificationsOnce()
        controller.chooseChatGpt()
    }) { Text("Sign in again") }
}

@Composable
private fun Busy(message: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(message)
    }
}

@Composable
private fun Finished(message: String, again: Boolean, controller: SetupController, actions: HrActions) {
    Text(message)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = controller::back) { Text("Back") }
        if (again) Button(onClick = {
            actions.askNotificationsOnce()
            controller.test()
        }) { Text("Test again") }
    }
}

private fun describe(result: TestResult): String = when (result) {
    is TestResult.Answered -> "Hermes answered: ${result.text}"
    is TestResult.Rejected -> "The gateway refused the request (HTTP ${result.code}): ${result.body}"
    TestResult.NoGateway -> "hr's gateway is not running."
    is TestResult.Failed -> result.reason
}
