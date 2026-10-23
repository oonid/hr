package id.or.oo.hr.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.or.oo.hr.HrApp
import id.or.oo.pr.session.isIgnoringBatteryOptimizations

@Composable
fun SessionBanners(app: HrApp) {
    val context = LocalContext.current
    val running by app.sessions.running.collectAsStateWithLifecycle()
    val keepAwake by app.sessions.keepCpuAwake.collectAsStateWithLifecycle()
    val backgroundError by app.sessions.backgroundError.collectAsStateWithLifecycle()
    val resumes = remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes.intValue++
        onPauseOrDispose {}
    }
    val exempt = remember(resumes.intValue, keepAwake) { context.isIgnoringBatteryOptimizations() }

    Column {
        if (running.isNotEmpty() && backgroundError == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Keep CPU awake", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = keepAwake, onCheckedChange = { app.sessions.setKeepCpuAwake(it) })
                }
            }
        }
        if (keepAwake && !exempt) {
            Banner(
                text = "Some phones' battery savers can still stop the gateway. Allow hr to run unrestricted.",
                action = "Allow",
                onAction = { context.requestBatteryExemption() },
            )
        }
        backgroundError?.let { reason ->
            Banner(text = "Background running unavailable: $reason", action = null, onAction = {})
        }
    }
}

@Composable
fun Banner(text: String, action: String?, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@SuppressLint("BatteryLife")
fun Context.requestBatteryExemption() {
    try {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
        )
    } catch (e: ActivityNotFoundException) {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (missing: ActivityNotFoundException) {
        }
    }
}
