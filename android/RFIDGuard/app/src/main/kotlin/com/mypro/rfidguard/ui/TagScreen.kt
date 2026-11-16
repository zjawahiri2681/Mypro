package com.mypro.rfidguard.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mypro.rfidguard.AppModel
import com.mypro.rfidguard.Host
import com.mypro.rfidguard.core.Hex
import com.mypro.rfidguard.core.TagSnapshot
import com.mypro.rfidguard.core.mifare.Classic
import com.mypro.rfidguard.core.ndef.NdefCodec
import com.mypro.rfidguard.core.ndef.NdefContent
import com.mypro.rfidguard.core.ndef.NdefContents
import com.mypro.rfidguard.core.ndef.NdefFormatException
import com.mypro.rfidguard.core.ndef.UriRisk
import com.mypro.rfidguard.core.security.Severity
import com.mypro.rfidguard.core.security.TagAudit
import com.mypro.rfidguard.core.toHex
import com.mypro.rfidguard.core.type2.Type2
import com.mypro.rfidguard.core.vault.ClassicKeyEntry
import com.mypro.rfidguard.core.vault.SavedTag
import com.mypro.rfidguard.core.vault.TagPassword
import com.mypro.rfidguard.nfc.WriteOp
import java.security.SecureRandom
import java.util.UUID

@Composable
fun ScanScreen(model: AppModel, host: Host) {
    val scan = model.lastScan
    if (scan != null) {
        TagDetail(model, host, scan, null)
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Ready to scan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Hold a tag or card flat against the back of the phone. RFID Guard reads it, checks it for security problems and never opens anything on its own.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Hint("Works with 13.56 MHz NFC tags: NTAG, MIFARE Ultralight, MIFARE Classic (on supported phones), ISO 15693, FeliCa. 125 kHz proximity fobs need separate hardware.")
    }
}

private enum class TagDialog { NONE, SAVE, ERASE, LOCK, SET_PASSWORD, REMOVE_PASSWORD, RESTORE, CLASSIC_WRITE, ADD_KEY, DELETE }

/** Full view of one tag: verdict, content, actions and raw memory. [saved] is set when it comes from the vault. */
@Composable
fun TagDetail(model: AppModel, host: Host, tag: TagSnapshot, saved: SavedTag?) {
    val vault = model.data ?: return
    val signingKey = remember(vault.signingKeyHex) { Hex.decodeOrNull(vault.signingKeyHex) }
    val findings = remember(tag, vault.signingKeyHex) { TagAudit.assess(tag, signingKey) }
    val records = remember(tag) {
        try { tag.ndef?.messageHex?.let(Hex::decodeOrNull)?.let(NdefCodec::parse).orEmpty() } catch (e: NdefFormatException) { emptyList() }
    }
    var dialog by remember(tag) { mutableStateOf(TagDialog.NONE) }
    var linkToOpen by remember(tag) { mutableStateOf<String?>(null) }
    var reveal by remember(tag) { mutableStateOf(false) }
    var showMemory by remember(tag) { mutableStateOf(false) }

    val spec = remember(tag) { Type2.specFromVersion(tag.type2?.versionHex?.let(Hex::decodeOrNull)) }
    val storedPassword = vault.tagPasswords[tag.uidHex]
    val protectionActive = remember(tag) {
        val cfgPage = spec?.configPage
        val pages = tag.type2?.pagesHex
        if (cfgPage == null || pages == null) false
        else {
            val cfg0 = pages.getOrNull(cfgPage)?.let(Hex::decodeOrNull)
            val cfg1 = pages.getOrNull(cfgPage + 1)?.let(Hex::decodeOrNull)
            if (cfg0 == null || cfg1 == null) true else Type2.config(cfg0, cfg1)?.protectionActive(spec?.totalPages ?: 0) ?: true
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        if (saved != null) {
            TextButton(onClick = { model.viewing = null }) { Text("‹ Back to vault") }
        }
        SectionCard(saved?.name ?: tag.typeName) {
            if (saved != null) LabelValue("Chip", tag.typeName)
            LabelValue("Identifier (UID)", formatUid(tag.uidHex), mono = true)
            tag.manufacturer?.let { LabelValue("Chip maker", it) }
            LabelValue(if (saved != null) "Saved" else "Scanned", formatTime(saved?.savedAtEpochMs ?: tag.readAtEpochMs))
            val worst = TagAudit.worst(findings)
            Text(
                when (worst) {
                    Severity.HIGH -> "Verdict: serious security concerns"
                    Severity.MEDIUM -> "Verdict: some security concerns"
                    Severity.LOW -> "Verdict: minor concerns"
                    else -> "Verdict: no problems found"
                },
                color = severityColor(worst), fontWeight = FontWeight.SemiBold,
            )
            for (note in tag.notes) Hint(note)
        }

        SectionCard("Security check") {
            findings.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                FindingRow(f)
            }
        }

        if (tag.ndef != null) SectionCard("Content") {
            if (records.isEmpty()) Hint(if (tag.ndef?.messageHex == null) "No content could be read." else "The tag holds no readable records.")
            var hasSecret = false
            for (r in records) {
                val content = NdefContents.decode(r)
                if (content is NdefContent.Wifi && content.password.isNotEmpty()) hasSecret = true
                val (kind, text) = describeRecord(content, reveal)
                Column(Modifier.fillMaxWidth()) {
                    Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text)
                    val link = when (content) {
                        is NdefContent.Uri -> content.uri
                        is NdefContent.SmartPoster -> content.uri
                        else -> null
                    }
                    if (link != null) TextButton(onClick = { linkToOpen = link }) { Text("Check and open…") }
                }
            }
            if (hasSecret) TextButton(onClick = { if (reveal) reveal = false else host.confirmIdentity("Show the Wi-Fi password") { reveal = true } }) { Text(if (reveal) "Hide password" else "Show password") }
            tag.ndef?.let { Hint("Capacity ${it.maxSize} bytes · ${if (it.writable) "writable" else "read-only"}") }
        }

        SectionCard("Actions") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saved == null) Button(onClick = { dialog = TagDialog.SAVE }) { Text("Save to vault") }
                else Button(onClick = { dialog = TagDialog.RESTORE }) { Text("Restore to tag") }
                OutlinedButton(onClick = { host.copySensitive("UID", tag.uidHex) }) { Text("Copy UID") }
                if (saved != null) OutlinedButton(onClick = { dialog = TagDialog.DELETE }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            if (saved == null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tag.ndef?.writable == true || storedPassword != null) OutlinedButton(onClick = { dialog = TagDialog.ERASE }) { Text("Erase") }
                    if (spec?.configPage != null) {
                        if (protectionActive && storedPassword != null) OutlinedButton(onClick = { dialog = TagDialog.REMOVE_PASSWORD }) { Text("Remove password") }
                        else if (!protectionActive) OutlinedButton(onClick = { dialog = TagDialog.SET_PASSWORD }) { Text("Set password") }
                    }
                    if (tag.ndef?.writable == true && tag.ndef?.canMakeReadOnly == true) OutlinedButton(onClick = { dialog = TagDialog.LOCK }) { Text("Lock forever", color = MaterialTheme.colorScheme.error) }
                    if (tag.classic != null) OutlinedButton(onClick = { dialog = TagDialog.CLASSIC_WRITE }) { Text("Write block") }
                    if (tag.classic != null || tag.sak == 0x08 || tag.sak == 0x18) OutlinedButton(onClick = { dialog = TagDialog.ADD_KEY }) { Text("Add key") }
                }
            }
            if (storedPassword != null) {
                TextButton(onClick = {
                    host.confirmIdentity("Show this tag's password") {
                        model.message = "Password (PWD): ${storedPassword.pwdHex}\nAcknowledge (PACK): ${storedPassword.packHex}\n\nKeep these private. Anyone who has them can rewrite the tag."
                    }
                }) { Text("Show this tag's password") }
            }
        }

        SectionCard("Technical details") {
            LabelValue("Technologies", tag.techs.joinToString(", "))
            tag.atqaHex?.let { LabelValue("ATQA / SAK", it + " / " + (tag.sak?.let { s -> "%02X".format(s) } ?: "-"), mono = true) }
            tag.atsHex?.let { if (it.isNotEmpty()) LabelValue("Historical bytes", it, mono = true) }
            tag.type2?.versionHex?.let { LabelValue("Chip version", it, mono = true) }
            tag.type2?.signatureHex?.let { LabelValue("Originality signature", it, mono = true) }
            tag.classic?.let { LabelValue("Memory", "${it.sizeBytes} bytes in ${it.sectors.size} sectors") }
            tag.nfcV?.let { LabelValue("Memory", "${it.blocksHex.size} blocks of ${it.blockSize} bytes") }
            tag.felica?.let { LabelValue("System code / PMm", it.systemCodeHex + " / " + it.pmmHex, mono = true) }
            val dump = remember(tag) { memoryDump(tag) }
            if (dump != null) {
                TextButton(onClick = { showMemory = !showMemory }) { Text(if (showMemory) "Hide memory" else "Show memory") }
                if (showMemory) Mono(dump, Modifier.horizontalScroll(rememberScrollState()))
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    linkToOpen?.takeIf { it.isNotBlank() }?.let { link -> LinkDialog(link, onOpen = { linkToOpen = null; host.openLink(link) }, onDismiss = { linkToOpen = null }) }

    when (dialog) {
        TagDialog.NONE -> {}
        TagDialog.SAVE -> InputDialog(
            title = "Save to vault", label = "Name", confirmLabel = "Save", initial = tag.typeName,
            hint = "The full scan, including any keys that opened it, is stored encrypted.",
            validate = { if (it.isBlank()) "Give it a name" else if (it.length > 60) "Too long" else null },
            onConfirm = { name ->
                dialog = TagDialog.NONE
                if (model.update { v -> v.copy(savedTags = listOf(SavedTag(UUID.randomUUID().toString(), name.trim(), System.currentTimeMillis(), tag)) + v.savedTags) }) model.message = "Saved to the vault."
            },
            onDismiss = { dialog = TagDialog.NONE },
        )
        TagDialog.DELETE -> ConfirmDialog(
            "Delete this saved tag?", "The saved copy is removed from the vault. The physical tag is not touched.", "Delete", danger = true,
            onConfirm = { dialog = TagDialog.NONE; val id = saved?.id; model.viewing = null; model.update { v -> v.copy(savedTags = v.savedTags.filterNot { it.id == id }) } },
            onDismiss = { dialog = TagDialog.NONE },
        )
        TagDialog.ERASE -> ConfirmDialog(
            "Erase this tag?", "The content of tag ${formatUid(tag.uidHex)} is replaced by an empty message. Save it to the vault first if you may need it again.", "Erase", danger = true,
            onConfirm = { dialog = TagDialog.NONE; host.confirmIdentity("Erase a tag") { model.arm(WriteOp.Erase(tag.uidHex)) } },
            onDismiss = { dialog = TagDialog.NONE },
        )
        TagDialog.LOCK -> ConfirmDialog(
            "Lock this tag forever?", "After locking, the content of tag ${formatUid(tag.uidHex)} can never be changed or erased again - by anyone, including you. This cannot be undone. Check the content is final.", "Lock permanently", danger = true,
            onConfirm = { dialog = TagDialog.NONE; host.confirmIdentity("Permanently lock a tag") { model.arm(WriteOp.MakeReadOnly(tag.uidHex)) } },
            onDismiss = { dialog = TagDialog.NONE },
        )
        TagDialog.SET_PASSWORD -> SetPasswordDialog(
            onConfirm = { protectRead ->
                dialog = TagDialog.NONE
                host.confirmIdentity("Password-protect a tag") {
                    val password = storedPassword ?: newTagPassword()
                    // Stored before the tag is touched, so the password can never exist only on the tag.
                    if (model.update { v -> v.copy(tagPasswords = v.tagPasswords + (tag.uidHex to password)) }) model.arm(WriteOp.SetPassword(tag.uidHex, password, protectRead))
                }
            },
            onDismiss = { dialog = TagDialog.NONE },
        )
        TagDialog.REMOVE_PASSWORD -> ConfirmDialog(
            "Remove the password?", "Tag ${formatUid(tag.uidHex)} becomes writable by anyone again.", "Remove",
            onConfirm = { dialog = TagDialog.NONE; val p = storedPassword; if (p != null) host.confirmIdentity("Remove a tag password") { model.arm(WriteOp.RemovePassword(tag.uidHex, p)) } },
            onDismiss = { dialog = TagDialog.NONE },
        )
        TagDialog.RESTORE -> RestoreDialog(
            tag = tag, expert = vault.settings.expertMode,
            onConfirm = { anyTag -> dialog = TagDialog.NONE; host.confirmIdentity("Restore a saved copy to a tag") { model.arm(WriteOp.Restore(if (anyTag) null else tag.uidHex, tag)) } },
            onDismiss = { dialog = TagDialog.NONE },
        )
        TagDialog.ADD_KEY -> AddKeyDialog(
            onConfirm = { label, key ->
                dialog = TagDialog.NONE
                val added = model.update { v ->
                    if (v.classicKeys.count { it.uidHex == tag.uidHex } >= 16 || v.classicKeys.any { it.keyHex == key && it.uidHex == tag.uidHex }) v
                    else v.copy(classicKeys = v.classicKeys + ClassicKeyEntry(label, key, tag.uidHex))
                }
                if (added) model.message = "Key stored for card ${formatUid(tag.uidHex)}. Scan the card again to read with it."
            },
            onDismiss = { dialog = TagDialog.NONE },
        )
        TagDialog.CLASSIC_WRITE -> ClassicWriteDialog(
            tag = tag, expert = vault.settings.expertMode,
            onConfirm = { block, data, key, useB ->
                dialog = TagDialog.NONE
                host.confirmIdentity("Write a MIFARE Classic block") { model.arm(WriteOp.ClassicWrite(tag.uidHex, block, data, key, useB, vault.settings.expertMode)) }
            },
            onDismiss = { dialog = TagDialog.NONE },
        )
    }
}

private fun newTagPassword(): TagPassword {
    val random = SecureRandom()
    val pwd = ByteArray(4)
    // Avoid the factory value and all-zero, which readers try first.
    do random.nextBytes(pwd) while (pwd.all { it == 0xFF.toByte() } || pwd.all { it == 0.toByte() })
    val pack = ByteArray(2).also(random::nextBytes)
    return TagPassword(pwd.toHex(), pack.toHex(), System.currentTimeMillis())
}

/** A link from a tag is never opened directly: the owner sees the full address and the risk checks first. */
@Composable
private fun LinkDialog(link: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val findings = remember(link) { UriRisk.assess(link) }
    val blocked = findings.any { it.title.startsWith("Dangerous link type") || it.title == "Hidden characters in link" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open this link?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(link, fontFamily = FontFamily.Monospace)
                if (findings.isEmpty()) Text("No warning signs found. Only open it if you trust where this tag came from.", color = severityColor(Severity.GOOD))
                for (f in findings) FindingRow(f)
                if (blocked) Text("RFID Guard will not open this link.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            if (!blocked) Button(
                onClick = onOpen,
                colors = if (UriRisk.worst(findings) >= Severity.MEDIUM) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(if (findings.isEmpty()) "Open" else "Open anyway") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SetPasswordDialog(onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var protectRead by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password-protect this tag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RFID Guard creates a random password for this tag and keeps it in your vault. Afterwards only this vault can change the tag.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(protectRead, { protectRead = it })
                    Text("Also hide the content from other readers")
                }
                Hint(if (protectRead) "Other phones will see an empty or unreadable tag. Choose this for private data, not for tags others should tap." else "Everyone can still read the tag; nobody else can change it.")
                Hint("Limits: the chip's password is 32 bits and is sent unencrypted. This stops casual tampering, not a prepared attacker. If the vault is lost without a backup, the tag stays protected forever.")
            }
        },
        confirmButton = { Button(onClick = { onConfirm(protectRead) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RestoreDialog(tag: TagSnapshot, expert: Boolean, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var anyTag by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore saved copy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The saved user data is written back to the tag and whatever is there now is overwritten. The identifier, lock bits, keys and password settings are never copied.")
                if (expert) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(anyTag, { anyTag = it })
                    Text("Allow another blank tag of the same type")
                }
                Hint(if (anyTag) "Use this only to move your own content to a replacement tag." else "Only the original tag ${formatUid(tag.uidHex)} is accepted.")
            }
        },
        confirmButton = { Button(onClick = { onConfirm(anyTag) }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ClassicWriteDialog(tag: TagSnapshot, expert: Boolean, onConfirm: (Int, ByteArray, ByteArray, Boolean) -> Unit, onDismiss: () -> Unit) {
    var blockText by remember { mutableStateOf("") }
    var dataText by remember { mutableStateOf("") }
    var keyText by remember { mutableStateOf("") }
    var useB by remember { mutableStateOf(false) }
    val dump = tag.classic
    val block = blockText.toIntOrNull()
    val data = Hex.decodeOrNull(dataText)?.takeIf { it.size == 16 }
    val sector = block?.let { if (it in 0..255) dump?.sectors?.getOrNull(Classic.sectorOfBlock(it)) else null }
    val knownKey = if (useB) sector?.keyBHex else sector?.keyAHex
    val key = (if (keyText.isBlank()) knownKey?.let(Hex::decodeOrNull) else Hex.decodeOrNull(keyText))?.takeIf { it.size == 6 }
    val blockProblem = when {
        block == null -> "Enter a block number"
        sector == null -> "No such block on this card"
        block == 0 -> "Block 0 (identifier) is never written"
        Classic.isTrailer(block) && !expert -> "Sector trailer: needs expert mode"
        Classic.isTrailer(block) && data != null -> Classic.validateTrailerForWrite(data)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write a block") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(blockText, { blockText = it.filter(Char::isDigit).take(3) }, "Block number", keyboard = KeyboardType.Number, error = if (blockText.isEmpty()) null else blockProblem)
                Field(dataText, { dataText = it.uppercase().take(47) }, "Data: 16 bytes in hex", error = if (dataText.isEmpty() || data != null) null else "Needs exactly 32 hex digits")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(useB, { useB = it })
                    Text("Authenticate with key B")
                }
                Field(keyText, { keyText = it.uppercase().take(17) }, if (knownKey != null) "Key (blank = key found at scan)" else "Key: 6 bytes in hex", secret = true, error = if (keyText.isEmpty() || key != null) null else "Needs exactly 12 hex digits")
                Hint("The write is checked by reading the block back. A wrong sector trailer can lock a sector for good, so trailers are validated and need expert mode.")
            }
        },
        confirmButton = {
            Button(onClick = { if (block != null && data != null && key != null) onConfirm(block, data, key, useB) }, enabled = blockProblem == null && data != null && key != null) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
