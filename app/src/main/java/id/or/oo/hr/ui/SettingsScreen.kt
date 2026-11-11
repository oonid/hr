package id.or.oo.hr.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.or.oo.hr.HrApp
import id.or.oo.hr.image.ImageStore
import id.or.oo.hr.image.canRemoveImage
import id.or.oo.hr.image.canStartSession
import id.or.oo.hr.image.imageInUse
import id.or.oo.pr.session.isIgnoringBatteryOptimizations
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(app: HrApp, actions: HrActions, modifier: Modifier) {
    val context = LocalContext.current
    val resumes = remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes.intValue++
        onPauseOrDispose {}
    }
    val notifications = remember(resumes.intValue) { context.canPostNotifications() }
    val exempt = remember(resumes.intValue) { context.isIgnoringBatteryOptimizations() }
    val installed by app.images.installed.collectAsStateWithLifecycle()
    val previous by app.images.previous.collectAsStateWithLifecycle()
    val operation by app.images.operation.collectAsStateWithLifecycle()
    val running by app.sessions.running.collectAsStateWithLifecycle()
    val gateway by app.gateway.state.collectAsStateWithLifecycle()
    val containerPresent by app.images.containerPresent.collectAsStateWithLifecycle()
    val inUse = imageInUse(running.isNotEmpty(), gateway)
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var confirmRemovePrevious by rememberSaveable { mutableStateOf(false) }
    var confirmSwitchBack by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Notifications") {
            Text(if (notifications) "Allowed" else "Not allowed: hr cannot tell you when the gateway stops.")
            if (!notifications) {
                Row {
                    Button(onClick = actions.askNotifications) { Text("Allow") }
                    TextButton(onClick = { context.openNotificationSettings() }) { Text("Open settings") }
                }
            }
        }
        Section("Battery") {
            Text(if (exempt) "Unrestricted" else "Optimised: some phones stop background apps that are not exempt.")
            if (!exempt) Button(onClick = { context.requestBatteryExemption() }) { Text("Allow unrestricted") }
        }
        Section("Hermes image") {
            val image = installed
            if (image == null) {
                Text("Not installed")
            } else {
                Text(image.reference)
                Text("Installed ${DateFormat.getDateTimeInstance().format(Date(image.installedAt))}")
            }
            previous?.let { older ->
                Text("Previous image: ${older.buildTag} (kept for switching back)")
                Row {
                    OutlinedButton(
                        onClick = { confirmSwitchBack = true },
                        enabled = canStartSession(operation),
                    ) { Text("Switch back") }
                    TextButton(
                        onClick = { confirmRemovePrevious = true },
                        enabled = canStartSession(operation),
                    ) { Text("Remove previous image") }
                }
            }
            operation?.takeIf { it.kind == ImageStore.Kind.Remove || it.kind == ImageStore.Kind.SwitchBack }?.let { LogLines(it.lines) }
            if (containerPresent) {
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    enabled = canRemoveImage(inUse, operation),
                ) { Text("Remove image") }
                if (inUse) Text("Stop the gateway and end the terminal to remove the image.")
            }
        }
    }

    val older = previous
    val current = installed
    if (confirmSwitchBack && older != null && current != null) {
        SwitchBackDialog(
            older.buildTag,
            current.buildTag,
            onConfirm = {
                confirmSwitchBack = false
                app.images.switchBack()
            },
            onCancel = { confirmSwitchBack = false },
        )
    }
    if (confirmRemovePrevious && older != null) {
        RemovePreviousDialog(
            older.buildTag,
            onConfirm = {
                confirmRemovePrevious = false
                app.images.removePrevious()
            },
            onCancel = { confirmRemovePrevious = false },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the Hermes image?") },
            text = { Text("This removes every Hermes image hr installed; the profile is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    app.images.remove()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RemovePreviousDialog(tag: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Remove previous image?") },
        text = { Text("Remove $tag? You can't switch back to it afterwards.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

fun Context.canPostNotifications(): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun Context.openNotificationSettings() {
    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}
