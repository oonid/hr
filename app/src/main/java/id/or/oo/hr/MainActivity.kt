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
import id.or.oo.hr.ui.HrActions
import id.or.oo.hr.ui.HrScreens
import id.or.oo.hr.ui.HrTheme
import id.or.oo.pr.session.SessionService

class MainActivity : ComponentActivity() {

    private val app: HrApp get() = application as HrApp

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && app.sessions.running.value.isNotEmpty()) {
                ContextCompat.startForegroundService(this, Intent(this, SessionService::class.java))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val actions = HrActions(
            startGateway = {
                askNotificationsOnce()
                app.gateway.start()
            },
            openTerminal = {
                askNotificationsOnce()
                startActivity(Intent(this, TerminalActivity::class.java))
            },
            askNotifications = ::askNotifications,
        )
        setContent { HrTheme { HrScreens(app, actions) } }
    }

    override fun onResume() {
        super.onResume()
        app.images.refresh()
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
