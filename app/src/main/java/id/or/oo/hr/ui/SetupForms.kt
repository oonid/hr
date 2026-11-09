package id.or.oo.hr.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import id.or.oo.hr.profile.SetupStatus
import id.or.oo.hr.setup.ApiProvider
import id.or.oo.hr.setup.SetupController

@Composable
fun ApiKeyForm(status: SetupStatus, controller: SetupController, actions: HrActions) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val active = ApiProvider.entries.firstOrNull { it.id == status.provider }
    val provider = ApiProvider.entries.firstOrNull { it.id == chosen } ?: active ?: ApiProvider.entries.first()
    var key by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("API key", style = MaterialTheme.typography.titleSmall)
        if (active != null) Text(apiLine(active, status))
        Dropdown("Provider", ApiProvider.entries.map { it.label }, provider.label) { label ->
            chosen = ApiProvider.entries.first { it.label == label }.id
        }
        FormField("API key", key, secret = true) { key = it }
        if (provider.keyVariable in status.savedKeys) Text("Key saved")
        FormField("Model", model) { model = it }
        if (provider.needsBaseUrl) FormField("Base URL", baseUrl) { baseUrl = it }
        Button(
            onClick = {
                actions.askNotificationsOnce()
                controller.chooseApiKey(provider, key, model.trim(), baseUrl.takeIf { provider.needsBaseUrl })
                key = ""
            },
            enabled = key.isNotBlank() && model.isNotBlank() && (!provider.needsBaseUrl || baseUrl.isNotBlank()),
        ) { Text("Save") }
    }
}

@Composable
fun TelegramForm(status: SetupStatus, controller: SetupController, actions: HrActions) {
    var confirming by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var users by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Telegram (optional)", style = MaterialTheme.typography.titleSmall)
        Text(telegramLine(status))
        if (status.telegramToken) OutlinedButton(onClick = { confirming = true }) { Text("Remove") }
        FormField("Bot token", token, secret = true) { token = it }
        FormField("Allowed user ids", users) { users = it }
        Button(
            onClick = {
                actions.askNotificationsOnce()
                controller.saveTelegram(token, users)
                token = ""
            },
            enabled = token.isNotBlank(),
        ) { Text("Save") }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove the Telegram bot token?") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    controller.removeTelegram()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

private fun apiLine(provider: ApiProvider, status: SetupStatus): String = buildString {
    append("Using ${provider.label}")
    status.model?.let { append(" · $it") }
    if (provider.needsBaseUrl) status.baseUrlHost?.let { append(" · $it") }
}

private fun telegramLine(status: SetupStatus): String = when {
    !status.telegramToken -> "Not set up"
    status.telegramUsers == 1 -> "Bot token saved · 1 allowed user"
    else -> "Bot token saved · ${status.telegramUsers} allowed users"
}

@Composable
fun FormField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    onSelect(option)
                    expanded = false
                })
            }
        }
    }
}
