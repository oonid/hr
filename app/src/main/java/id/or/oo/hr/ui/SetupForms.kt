package id.or.oo.hr.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import id.or.oo.hr.setup.ApiProvider
import id.or.oo.hr.setup.SetupController

@Composable
fun ApiKeyForm(controller: SetupController, actions: HrActions) {
    var provider by rememberSaveable { mutableStateOf(ApiProvider.entries.first()) }
    var key by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("API key", style = MaterialTheme.typography.titleSmall)
        Dropdown("Provider", ApiProvider.entries.map { it.label }, provider.label) { label ->
            provider = ApiProvider.entries.first { it.label == label }
        }
        FormField("API key", key, secret = true) { key = it }
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
fun TelegramForm(controller: SetupController, actions: HrActions) {
    var token by remember { mutableStateOf("") }
    var users by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Telegram (optional)", style = MaterialTheme.typography.titleSmall)
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
