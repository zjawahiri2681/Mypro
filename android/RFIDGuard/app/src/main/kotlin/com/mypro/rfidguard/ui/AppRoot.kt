package com.mypro.rfidguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mypro.rfidguard.AppModel
import com.mypro.rfidguard.Host
import com.mypro.rfidguard.NfcState
import com.mypro.rfidguard.Tab
import kotlinx.coroutines.delay

@Composable
fun AppRoot(model: AppModel, host: Host) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (model.data == null) LockScreen(model, host) else MainScreen(model, host)
    }
}

@Composable
private fun LockScreen(model: AppModel, host: Host) {
    var confirmReset by remember { mutableStateOf(false) }
    val firstRun = !model.vault.exists
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("RFID Guard", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (firstRun) "Your tags, keys and backups are kept in an encrypted vault that only you can open. Create it to begin."
            else "The vault is locked. Tags are ignored until you unlock.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!host.deviceStatus.secureLock) {
            Spacer(Modifier.height(12.dp))
            Text(
                "This phone has no screen lock. The vault will be encrypted but cannot ask for your fingerprint or PIN. Set a screen lock for real protection.",
                textAlign = TextAlign.Center, color = severityColor(com.mypro.rfidguard.core.security.Severity.MEDIUM), style = MaterialTheme.typography.bodySmall,
            )
        }
        model.lockScreenError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        if (!model.vaultBroken) {
            Button(onClick = { host.unlock() }, modifier = Modifier.fillMaxWidth()) { Text(if (firstRun) "Create vault" else "Unlock") }
        } else {
            Button(onClick = { host.unlock() }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            TextButton(onClick = { confirmReset = true }) { Text("Reset vault", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmReset) ConfirmDialog(
        title = "Reset the vault?",
        text = "Every saved tag, key and tag password in this app will be destroyed. Tags protected with a password stored only here will stay locked. This cannot be undone.",
        confirmLabel = "Destroy and reset", danger = true,
        onConfirm = { confirmReset = false; host.resetVault() },
        onDismiss = { confirmReset = false },
    )
}

@Composable
private fun MainScreen(model: AppModel, host: Host) {
    BackHandler(enabled = model.viewing != null) { model.viewing = null }
    BackHandler(enabled = model.viewing == null && model.tab != Tab.SCAN) { model.tab = Tab.SCAN }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(model.tab == Tab.SCAN, { model.viewing = null; model.tab = Tab.SCAN }, icon = { Icon(Icons.Filled.Search, null) }, label = { Text("Scan") })
                NavigationBarItem(model.tab == Tab.WRITE, { model.viewing = null; model.tab = Tab.WRITE }, icon = { Icon(Icons.Filled.Edit, null) }, label = { Text("Write") })
                NavigationBarItem(model.tab == Tab.VAULT, { model.viewing = null; model.tab = Tab.VAULT }, icon = { Icon(Icons.Filled.Lock, null) }, label = { Text("Vault") })
                NavigationBarItem(model.tab == Tab.SETTINGS, { model.viewing = null; model.tab = Tab.SETTINGS }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Security") })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (host.nfcState) {
                NfcState.ABSENT -> Banner("This phone has no NFC hardware. Saved tags can be viewed, but nothing can be scanned or written.")
                NfcState.OFF -> Banner("NFC is switched off.", "Turn on") { host.openNfcSettings() }
                NfcState.ON -> {}
            }
            Box(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                val saved = model.viewing
                when {
                    saved != null -> TagDetail(model, host, saved.snapshot, saved)
                    model.tab == Tab.SCAN -> ScanScreen(model, host)
                    model.tab == Tab.WRITE -> WriteScreen(model, host)
                    model.tab == Tab.VAULT -> VaultScreen(model, host)
                    else -> SettingsScreen(model, host)
                }
            }
        }
    }

    model.pendingOp?.let { op ->
        // Drop the armed operation by itself so a forgotten request cannot write to a later tag.
        LaunchedEffect(op) {
            delay(AppModel.ARM_TIMEOUT_MS)
            if (model.pendingOp === op) { model.disarm(); model.message = "No tag was presented in time. Nothing was written." }
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(op.title) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (op.requiredUid != null) "Hold tag ${formatUid(op.requiredUid)} flat against the back of the phone and keep it still."
                        else "Hold the tag flat against the back of the phone and keep it still.",
                        textAlign = TextAlign.Center,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { model.disarm() }) { Text("Cancel") } },
        )
    }

    model.message?.let { text ->
        AlertDialog(
            onDismissRequest = { model.message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { model.message = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}
