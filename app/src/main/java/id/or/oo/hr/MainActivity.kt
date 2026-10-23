package id.or.oo.hr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import id.or.oo.hr.image.GrantWatcher
import id.or.oo.hr.image.canStartSession
import id.or.oo.hr.ui.canPostNotifications
import id.or.oo.pr.session.isIgnoringBatteryOptimizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import id.or.oo.hr.ui.HrActions
import id.or.oo.hr.ui.HrScreens
import id.or.oo.hr.ui.HrTheme
import id.or.oo.pr.session.SessionService

class MainActivity : ComponentActivity() {

    private val app: HrApp get() = application as HrApp

    private lateinit var grants: GrantWatcher

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && app.sessions.running.value.isNotEmpty()) {
                ContextCompat.startForegroundService(this, Intent(this, SessionService::class.java))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        grants = GrantWatcher(canPostNotifications(), isIgnoringBatteryOptimizations())
        val actions = HrActions(
            startGateway = {
                if (canStartSession(app.images.operation.value)) {
                    askNotificationsOnce()
                    app.gateway.start()
                }
            },
            openTerminal = {
                if (canStartSession(app.images.operation.value)) {
                    askNotificationsOnce()
                    startActivity(Intent(this, TerminalActivity::class.java))
                }
            },
            askNotifications = ::askNotifications,
        )
        setContent { HrTheme { HrScreens(app, actions) } }
    }

    override fun onResume() {
        super.onResume()
        if (!app.images.busy) lifecycleScope.launch(Dispatchers.IO) { app.images.refresh() }
        val granted = grants.update(canPostNotifications(), isIgnoringBatteryOptimizations())
        if (granted && app.sessions.running.value.isNotEmpty()) {
            ContextCompat.startForegroundService(this, Intent(this, SessionService::class.java))
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(NOTIFICATIONS_ASKED, false)) return
        prefs.edit().putBoolean(NOTIFICATIONS_ASKED, true).apply()
        askNotifications()
    }

    private companion object {
        const val PREFS = "hr_prefs"
        const val NOTIFICATIONS_ASKED = "notifications_asked"
    }
}
