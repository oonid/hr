package id.or.oo.hr

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import id.or.oo.pr.session.ManagedSession
import id.or.oo.pr.session.SessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal

class TerminalActivity : ComponentActivity() {

    private val sessions: SessionManager get() = (application as HrApp).sessions
    private var session: ManagedSession? = null
    private val screen = ManagedSession.Screen { finish() }
    private var initialImeRequested = false
    private var initialImeShown = false
    private var backPressStarted = false
    private var selectionController: SelectionController? = null
    private val showLeavePrompt = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = session
                if (current == null || current.exit.value != null) finish() else showLeavePrompt.value = true
            }
        })
        when (terminalStart(restored = savedInstanceState != null, sessionOpen = sessions.isOpen(Hermes.TERMINAL_KEY))) {
            TerminalStart.SHOW_ENDED -> showEndedWhileStopped()
            TerminalStart.OPEN -> open()
        }
    }

    private fun showEndedWhileStopped() {
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = TERMINAL_BACKGROUND) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("The terminal ended when hr was stopped.", color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { open() }) { Text("Start a new terminal") }
                    }
                }
            }
        }
    }

    private fun open() {
        (application as HrApp).profiles.ensureProfile()
        when (val result = sessions.open(Hermes.TERMINAL_KEY)) {
            is SessionManager.OpenResult.Failed -> setContent { Message("Couldn't open the terminal: ${result.reason}") }
            is SessionManager.OpenResult.Ending -> {
                sessions.attach(result.session, screen)
                sessions.detach(result.session, screen)
                setContent { Message("Ending the previous terminal…") }
                lifecycleScope.launch {
                    val stopped = withTimeoutOrNull(ManagedSession.END_TIMEOUT_MS) {
                        result.session.exit.first { it != null }
                    }
                    if (isFinishing) return@launch
                    if (stopped == null) {
                        setContent { Message("Couldn't stop the previous terminal.") }
                    } else {
                        lifecycle.withStarted { if (!isFinishing) open() }
                    }
                }
            }
            is SessionManager.OpenResult.Opened -> show(result.session)
        }
    }

    private fun show(current: ManagedSession) {
        session = current
        sessions.attach(current, screen)
        if (lifecycle.currentState != Lifecycle.State.INITIALIZED) {
            sessions.setVisible(current, screen, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        if (hasWindowFocus() && !initialImeRequested) {
            initialImeRequested = true
            ensureInitialKeyboard(attempt = 0)
        }

        setContent {
            val exit by current.exit.collectAsState()
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = TERMINAL_BACKGROUND) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Terminal(
                            terminalEmulator = current.emulator!!,
                            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
                            initialFontSize = 12.sp,
                            backgroundColor = TERMINAL_BACKGROUND,
                            foregroundColor = Color.White,
                            keyboardEnabled = true,
                            showSoftKeyboard = true,
                            onTerminalTap = { window.decorView.post { showKeyboard() } },
                            onSelectionControllerAvailable = { selectionController = it },
                        )
                        exit?.let { ended ->
                            Text(
                                text = "Terminal ended (exit ${ended.code ?: "unknown"})",
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Color(0xCC000000))
                                    .navigationBarsPadding()
                                    .padding(12.dp),
                            )
                        }
                    }
                    if (showLeavePrompt.value) {
                        LeavePrompt(
                            onEnd = {
                                showLeavePrompt.value = false
                                current.end()
                                finish()
                            },
                            onKeep = {
                                showLeavePrompt.value = false
                                sessions.setVisible(current, screen, false)
                                finish()
                            },
                            onCancel = { showLeavePrompt.value = false },
                        )
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) backPressStarted = true
            KeyEvent.ACTION_UP -> {
                if (backPressStarted && !event.isCanceled) {
                    val selection = selectionController
                    if (selection != null && selection.isSelectionActive) selection.clearSelection()
                    else onBackPressedDispatcher.onBackPressed()
                }
                backPressStarted = false
            }
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !initialImeRequested && session != null) {
            initialImeRequested = true
            ensureInitialKeyboard(attempt = 0)
        }
    }

    private fun ensureInitialKeyboard(attempt: Int) {
        window.decorView.postDelayed({
            if (isFinishing || initialImeShown) return@postDelayed
            val imeVisible = ViewCompat.getRootWindowInsets(window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (imeVisible) {
                initialImeShown = true
            } else if (attempt < 5) {
                showKeyboard()
                ensureInitialKeyboard(attempt + 1)
            }
        }, 300)
    }

    private fun showKeyboard() {
        val editor = findTextEditor(window.decorView) ?: return
        editor.requestFocus()
        WindowCompat.getInsetsController(window, editor).show(WindowInsetsCompat.Type.ime())
    }

    private fun findTextEditor(view: View): View? {
        if (view.onCheckIsTextEditor()) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTextEditor(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    override fun onStart() {
        super.onStart()
        session?.let { sessions.setVisible(it, screen, true) }
    }

    override fun onStop() {
        if (!isChangingConfigurations) session?.let { sessions.setVisible(it, screen, false) }
        super.onStop()
    }

    override fun onDestroy() {
        session?.let { sessions.detach(it, screen, retainEnded = isChangingConfigurations) }
        super.onDestroy()
    }

    private companion object {
        val TERMINAL_BACKGROUND = Color(0xFF1a1a2e)
    }
}

@Composable
private fun LeavePrompt(onEnd: () -> Unit, onKeep: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Leave the terminal?") },
        text = { Text("Keep running leaves the terminal working in the background. End terminal stops it and everything started in it. The gateway is not affected.") },
        confirmButton = { TextButton(onClick = onKeep) { Text("Keep running") } },
        dismissButton = {
            Row {
                TextButton(onClick = onEnd) { Text("End terminal") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Message(text: String) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1a1a2e)) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(text, color = Color.White)
            }
        }
    }
}
