package com.mypro.familytalk.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mypro.familytalk.FamilyTalkApp
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Chat(val conv: String) : Screen
    data class Verify(val memberId: String) : Screen
    data object Invite : Screen
    data object Members : Screen
    data object Sip : Screen
    data object Network : Screen
    data object Security : Screen
}

class MainActivity : ComponentActivity() {
    private val app get() = FamilyTalkApp.instance
    private val stack = mutableStateListOf<Screen>(Screen.Home)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            app.family.data.collect { d ->
                if (d.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        lifecycleScope.launch { app.family.events.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() } }
        handleIntent(intent)
        setContent {
            FamilyTalkTheme {
                val data by app.family.data.collectAsStateWithLifecycle()
                val hasFamily = data.roster != null || data.familyId != null
                if (!hasFamily || data.removed) {
                    OnboardingScreen(app, removed = data.removed)
                } else if (data.roster == null) {
                    WaitingForAdmissionScreen(app)
                } else {
                    Navigator()
                    JoinApprovals(app)
                }
            }
        }
    }

    @Composable
    private fun Navigator() {
        val top = stack.last()
        BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
        val nav = remember { Nav({ stack.add(it) }, { if (stack.size > 1) stack.removeAt(stack.lastIndex) }) }
        LaunchedEffect(top) { app.chats.openConv = (top as? Screen.Chat)?.conv }
        when (top) {
            Screen.Home -> HomeScreen(app, nav)
            is Screen.Chat -> ChatScreen(app, nav, top.conv)
            is Screen.Verify -> VerifyScreen(app, nav, top.memberId)
            Screen.Invite -> InviteScreen(app, nav)
            Screen.Members -> MembersScreen(app, nav)
            Screen.Sip -> SipScreen(app, nav)
            Screen.Network -> NetworkScreen(app, nav)
            Screen.Security -> SecurityInfoScreen(nav)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        app.chats.openConv = null
    }

    override fun onResume() {
        super.onResume()
        (stack.lastOrNull() as? Screen.Chat)?.let { app.chats.openConv = it.conv; app.chats.markRead(it.conv) }
    }

    private fun handleIntent(intent: Intent?) {
        // Only our own notifications carry this per-process token, so other apps
        // cannot open (and mark as read) a chat by sending us an Intent.
        if (intent?.getStringExtra("token") != intentToken) return
        val conv = intent.getStringExtra("conv") ?: return
        stack.clear(); stack.add(Screen.Home); stack.add(Screen.Chat(conv))
    }

    companion object {
        val intentToken: String = java.util.UUID.randomUUID().toString()
    }
}

/** Admin side: a person who scanned an invite asks to join. Nobody gets in without a tap here. */
@Composable
private fun JoinApprovals(app: FamilyTalkApp) {
    val pending by app.family.pendingJoins.collectAsStateWithLifecycle()
    val p = pending.firstOrNull() ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { androidx.compose.material3.Text("Let ${p.name.take(40)} join?") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Text("Someone used your invite. Only allow it if you are sure it's them: compare these digits with their screen, in person or on a phone call.")
                androidx.compose.material3.Text("Their phone should show this safety number on its waiting screen:")
                androidx.compose.material3.Text(
                    com.mypro.familytalk.core.crypto.SafetyNumber.format(p.safety).take(23),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton({ app.family.decideJoin(p, true) }) { androidx.compose.material3.Text("Allow") } },
        dismissButton = { androidx.compose.material3.TextButton({ app.family.decideJoin(p, false) }) { androidx.compose.material3.Text("Deny") } },
    )
}

class Nav(val go: (Screen) -> Unit, val back: () -> Unit)
