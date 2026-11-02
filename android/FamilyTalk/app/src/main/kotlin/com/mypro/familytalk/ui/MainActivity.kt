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

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            app.family.data.collect { d ->
                if (d.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        lifecycleScope.launch { app.family.events.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() } }
        askPermissions()
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
        val conv = intent?.getStringExtra("conv") ?: return
        stack.clear(); stack.add(Screen.Home); stack.add(Screen.Chat(conv))
    }

    private fun askPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.launch(wanted.toTypedArray())
    }
}

class Nav(val go: (Screen) -> Unit, val back: () -> Unit)
