package com.mypro.rfidguard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mypro.rfidguard.AppModel
import com.mypro.rfidguard.BuildConfig
import com.mypro.rfidguard.Host
import com.mypro.rfidguard.core.Hex
import com.mypro.rfidguard.core.mifare.Classic
import com.mypro.rfidguard.core.security.Severity
import com.mypro.rfidguard.core.vault.ClassicKeyEntry
import com.mypro.rfidguard.core.vault.ExportCodec

@Composable
fun VaultScreen(model: AppModel, host: Host) {
    val vault = model.data ?: return
    var showLog by remember { mutableStateOf(false) }
    var keyToDelete by remember { mutableStateOf<ClassicKeyEntry?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionCard("Saved tags (${vault.savedTags.size})") {
            if (vault.savedTags.isEmpty()) Hint("Scan a tag and choose \"Save to vault\" to keep an encrypted copy here.")
            vault.savedTags.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Column(Modifier.fillMaxWidth().clickable { model.viewing = t }.padding(vertical = 6.dp)) {
                    Text(t.name, fontWeight = FontWeight.SemiBold)
                    Hint("${t.snapshot.typeName} · ${formatUid(t.snapshot.uidHex)} · ${formatTime(t.savedAtEpochMs)}")
                }
            }
        }

        SectionCard("MIFARE Classic keys (${vault.classicKeys.size})") {
            Hint("Keys for your own cards. Each key is only ever presented to the card it was added for, so a fake card cannot collect your keys. To add one, scan the card and choose \"Add key\". RFID Guard does not crack or guess keys.")
            for (k in vault.classicKeys) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.label)
                        Hint("Card " + (if (k.uidHex.isEmpty()) "unassigned (not used)" else formatUid(k.uidHex)) + " · key ends " + k.keyHex.takeLast(2))
                    }
                    TextButton(onClick = { keyToDelete = k }) { Text("Remove") }
                }
            }
        }

        SectionCard("Tag passwords (${vault.tagPasswords.size})") {
            if (vault.tagPasswords.isEmpty()) Hint("Passwords that RFID Guard set on your NTAG / Ultralight EV1 tags appear here.")
            for ((uid, p) in vault.tagPasswords) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Mono(formatUid(uid))
                        Hint("Set " + formatTime(p.createdAtEpochMs))
                    }
                    TextButton(onClick = {
                        host.confirmIdentity("Show a tag password") {
                            model.message = "Tag ${formatUid(uid)}\nPassword (PWD): ${p.pwdHex}\nAcknowledge (PACK): ${p.packHex}"
                        }
                    }) { Text("Show") }
                }
            }
            if (vault.tagPasswords.isNotEmpty()) Hint("These exist only in this vault. Export an encrypted backup so a lost phone does not leave your tags locked.")
        }

        SectionCard("Activity log") {
            Hint("Every write attempt is recorded here, inside the encrypted vault.")
            val shown = if (showLog) vault.audit.take(100) else vault.audit.take(3)
            for (e in shown) {
                Column(Modifier.fillMaxWidth()) {
                    Text((if (e.ok) "✓ " else "✗ ") + e.action, color = if (e.ok) severityColor(Severity.GOOD) else severityColor(Severity.HIGH), fontWeight = FontWeight.SemiBold)
                    Hint("${formatTime(e.atEpochMs)} · ${formatUid(e.uidHex)}")
                    if (showLog && e.detail.isNotEmpty()) Hint(e.detail)
                }
            }
            if (vault.audit.isEmpty()) Hint("No writes yet.")
            if (vault.audit.size > 3) TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Show less" else "Show more") }
        }
        Spacer(Modifier.height(12.dp))
    }

    keyToDelete?.let { k ->
        ConfirmDialog(
            "Remove this key?", "\"${k.label}\" is deleted from the vault. Cards that need it can no longer be read here.", "Remove", danger = true,
            onConfirm = { keyToDelete = null; model.update { v -> v.copy(classicKeys = v.classicKeys.filterNot { it.keyHex == k.keyHex && it.uidHex == k.uidHex }) } },
            onDismiss = { keyToDelete = null },
        )
    }
}

@Composable
fun AddKeyDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var keyText by remember { mutableStateOf("") }
    val key = Hex.decodeOrNull(keyText)?.takeIf { it.size == 6 }
    val known = key != null && Hex.encode(key) in Classic.WELL_KNOWN_KEYS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a card key") },
        text = {
                GuardTouches()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(label, { label = it.take(40) }, "Label (for example: Office card)")
                Field(keyText, { keyText = it.uppercase().take(17) }, "Key: 6 bytes in hex", secret = true,
                    error = when {
                        keyText.isEmpty() -> null
                        key == null -> "Needs exactly 12 hex digits"
                        known -> "This factory key is already built in"
                        else -> null
                    })
            }
        },
        confirmButton = { Button(onClick = { if (key != null) onConfirm(label.trim().ifEmpty { "Key" }, Hex.encode(key)) }, enabled = key != null && !known) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class BackupAction { NONE, EXPORT, IMPORT }

@Composable
fun SettingsScreen(model: AppModel, host: Host) {
    val vault = model.data ?: return
    val status = host.deviceStatus
    val authBound = remember(model.vaultRevision) { model.vault.authBound }
    val strongBox = remember(model.vaultRevision) { model.vault.strongBox }
    var backup by remember { mutableStateOf(BackupAction.NONE) }
    var confirmImport by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmExpert by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionCard("Protection status") {
            StatusRow(authBound, "Vault key needs your biometrics or screen lock",
                if (authBound) "Every unlock is authorised by the phone's secure hardware." else "The vault is encrypted, but the key is not tied to a screen lock.")
            if (!authBound) OutlinedButton(onClick = { host.upgradeProtection() }) { Text("Bind vault to screen lock") }
            StatusRow(strongBox, "Key stored in a dedicated security chip (StrongBox)",
                if (strongBox) "The key lives in a separate tamper-resistant chip." else "This phone keeps the key in its trusted execution environment instead, which is still hardware-backed.", optional = true)
            StatusRow(status.secureLock, "Phone has a screen lock", if (status.secureLock) "Keep it: removing the screen lock destroys the vault key." else "Set a PIN, pattern or password in the phone settings.")
            StatusRow(!status.rootSuspected, "No sign of root access", if (status.rootSuspected) "Root access lets other software read this app's memory while it is unlocked." else "A basic check found nothing; it cannot rule root out.")
            StatusRow(!status.usbDebugging, "USB debugging is off", if (status.usbDebugging) "Turn off USB debugging in developer options when you do not need it." else "Good.")
            if (status.debugBuild) StatusRow(false, "This is a debug build", "Debug builds are easier to inspect. Use the release build for real data.")
            StatusRow(true, "Internet used only for updates you ask for", "The app connects to GitHub only when you tap \"Check for updates\". It never connects in the background and never sends anything from the vault or from a tag.")
            StatusRow(true, "Screenshots and screen recording blocked", "The app's screens are also hidden in the recent-apps list.")
            StatusRow(true, "Excluded from cloud backup and device transfer", "Use the encrypted export below to move or protect your data.")
        }

        SectionCard("Auto-lock") {
            Hint("The vault always locks when the screen turns off. It also locks after the app has been in the background for:")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((seconds, label) in listOf(0 to "Immediately", 60 to "1 minute", 300 to "5 minutes")) {
                    FilterChip(selected = vault.settings.autoLockSeconds == seconds, onClick = { model.update { v -> v.copy(settings = v.settings.copy(autoLockSeconds = seconds)) } }, label = { Text(label) })
                }
            }
            Button(onClick = { model.lock() }) { Text("Lock now") }
        }

        SectionCard("Writing") {
            ToggleRow("Seal new tags by default", "Adds the authenticity seal to content you write.", vault.settings.signWrites) { on ->
                model.update { v -> v.copy(settings = v.settings.copy(signWrites = on)) }
            }
            ToggleRow("Expert mode", "Allows writing MIFARE Classic sector trailers (keys and access bits) and restoring a saved copy to a different tag of the same type.", vault.settings.expertMode) { on ->
                if (on) confirmExpert = true else model.update { v -> v.copy(settings = v.settings.copy(expertMode = false)) }
            }
        }

        SectionCard("Encrypted backup") {
            Hint("A backup file holds everything in the vault, encrypted with a passphrase you choose (AES-256-GCM, PBKDF2-SHA256 with ${ExportCodec.DEFAULT_ITERATIONS / 1000},000 rounds). Keep the passphrase somewhere safe: without it the file is useless, to you as well.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { backup = BackupAction.EXPORT }) { Text("Export") }
                OutlinedButton(onClick = { confirmImport = true }) { Text("Import") }
            }
        }

        SectionCard("Updates") {
            Hint("Installed build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). Nothing is checked or downloaded until you tap the button.")
            Button(onClick = { host.checkForUpdate() }, enabled = !model.busy) { Text("Check for updates") }
            Hint("A download is checked against its published checksum and against this app's signing key before Android's installer is opened. You confirm the install yourself. Your vault is kept.")
        }

        SectionCard("Danger zone") {
            OutlinedButton(onClick = { confirmWipe = true }) { Text("Destroy vault", color = MaterialTheme.colorScheme.error) }
            Hint("Deletes every saved tag, key, tag password and the vault key itself.")
        }

        SectionCard("About") {
            Hint("RFID Guard ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}). For your own tags and cards. It reads and writes 13.56 MHz NFC tags; it does not crack keys, change card identifiers or read payment data.")
        }
        Spacer(Modifier.height(12.dp))
    }

    if (backup != BackupAction.NONE) PassphraseDialog(
        export = backup == BackupAction.EXPORT,
        onConfirm = { pass ->
            val action = backup
            backup = BackupAction.NONE
            if (action == BackupAction.EXPORT) host.exportBackup(pass) else host.importBackup(pass)
        },
        onDismiss = { backup = BackupAction.NONE },
    )
    model.availableUpdate?.let { info ->
        ConfirmDialog(
            "Update available", "Build ${info.build} (version ${info.versionName}) is available, about ${(info.sizeBytes + 524_288) / 1_048_576} MB. Download it from GitHub and open Android's installer?", "Download",
            onConfirm = { model.availableUpdate = null; host.installUpdate(info) },
            onDismiss = { model.availableUpdate = null },
        )
    }
    if (confirmImport) ConfirmDialog(
        "Replace the vault with a backup?", "Everything currently in the vault is replaced by the content of the backup file. Export the current vault first if you may need it.", "Choose backup", danger = true,
        onConfirm = { confirmImport = false; backup = BackupAction.IMPORT },
        onDismiss = { confirmImport = false },
    )
    if (confirmExpert) ConfirmDialog(
        "Turn on expert mode?", "A wrong sector trailer can lock part of a MIFARE Classic card for good. RFID Guard still refuses values that are certain to do so and never writes block 0.", "Turn on",
        onConfirm = { confirmExpert = false; host.confirmIdentity("Turn on expert mode") { model.update { v -> v.copy(settings = v.settings.copy(expertMode = true)) } } },
        onDismiss = { confirmExpert = false },
    )
    if (confirmWipe) ConfirmDialog(
        "Destroy the vault?", "All saved tags, keys and tag passwords are erased and cannot be recovered. Tags protected with a password stored only here stay locked forever.", "Destroy everything", danger = true,
        onConfirm = { confirmWipe = false; host.confirmIdentity("Destroy the vault") { host.resetVault() } },
        onDismiss = { confirmWipe = false },
    )
}

@Composable
private fun StatusRow(ok: Boolean, title: String, detail: String, optional: Boolean = false) {
    val color = severityColor(if (ok) Severity.GOOD else if (optional) Severity.INFO else Severity.MEDIUM)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(if (ok) "✓" else if (optional) "–" else "!", color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Hint(detail)
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Hint(detail)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PassphraseDialog(export: Boolean, onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val problem = when {
        pass.length < ExportCodec.MIN_PASSPHRASE -> "At least ${ExportCodec.MIN_PASSPHRASE} characters"
        export && pass.toSet().size < 5 -> "Too repetitive - use a longer, more varied passphrase"
        export && pass != repeat -> "The two entries do not match"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (export) "Protect the backup" else "Open a backup") },
        text = {
                GuardTouches()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(if (export) "Choose a passphrase of several words. It is the only thing protecting the file once it leaves this phone." else "Enter the passphrase the backup was made with, then pick the file.")
                Field(pass, { pass = it.take(128) }, "Passphrase", secret = true)
                if (export) Field(repeat, { repeat = it.take(128) }, "Repeat passphrase", secret = true, error = if (repeat.isEmpty()) null else problem)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(pass.toCharArray()) }, enabled = problem == null) { Text(if (export) "Choose where to save" else "Choose file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
