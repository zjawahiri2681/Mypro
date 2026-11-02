package com.mypro.familytalk.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.mypro.familytalk.FamilyTalkApp
import com.mypro.familytalk.core.crypto.SafetyNumber
import com.mypro.familytalk.core.family.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackScaffold(title: String, nav: Nav, content: @Composable () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/** Compare safety numbers in person (or by scanning) to rule out a key swap. */
@Composable
fun VerifyScreen(app: FamilyTalkApp, nav: Nav, memberId: String) {
    val roster by app.family.roster.collectAsStateWithLifecycle()
    val data by app.family.data.collectAsStateWithLifecycle()
    val m = roster?.byId(memberId) ?: return BackScaffold("Verify", nav) { Text("Not a family member") }
    val number = remember(m) { SafetyNumber.forPair(app.family.identity.public, m.identity) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { scanned ->
            val ok = scanned.removePrefix("FTSN:") == number
            result = ok
            if (ok) app.family.markVerified(memberId, true)
        }
    }
    val verified = memberId in data.verified
    BackScaffold("Verify ${m.name}", nav) {
        Row(verticalAlignment = Alignment.CenterVertically) { TrustBadge(verified); Spacer(Modifier.size(8.dp)); ExtChip(m.ext) }
        Text("When you are together, open this screen on both phones. Scan each other's code, or read the numbers aloud. If they match, nobody can be listening in between you.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { QrImage("FTSN:$number", 220.dp) }
        Text(SafetyNumber.format(number).chunked(18).joinToString("\n"), style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr), fontFamily = FontFamily.Monospace, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), lineHeight = 30.sp)
        Button({ scan.launch(scanOptions().setPrompt("Scan ${m.name}'s safety code")) }, Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.size(8.dp)); Text("Scan their code")
        }
        var confirmManual by remember { mutableStateOf(false) }
        OutlinedButton({ if (verified) app.family.markVerified(memberId, false) else confirmManual = true }, Modifier.fillMaxWidth()) {
            Text(if (verified) "Clear verification" else "We read the numbers aloud — they match")
        }
        if (confirmManual) AlertDialog(
            onDismissRequest = { confirmManual = false },
            title = { Text("Did all 60 digits match?") },
            text = { Text("Only confirm if you compared every group of numbers with ${m.name}, in person or on a video call where you could see and hear them.") },
            confirmButton = { TextButton({ app.family.markVerified(memberId, true); confirmManual = false }) { Text("Yes, all match") } },
            dismissButton = { TextButton({ confirmManual = false }) { Text("Cancel") } },
        )
        result?.let {
            Text(if (it) "Match. ${m.name} is verified." else "The codes do not match. First check that each phone shows the other person's screen (not your own). If they still differ, don't trust this connection and tell the family admin.",
                color = if (it) trustOk() else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun InviteScreen(app: FamilyTalkApp, nav: Nav) {
    val ctx = LocalContext.current
    var forName by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<com.mypro.familytalk.core.family.Invite?>(null) }
    BackScaffold("Invite a family member", nav) {
        val inv = invite
        if (inv == null) {
            Text("The invite works for one person and expires in 24 hours. Show the QR, or send it by message to someone who isn't with you. Either way, you must tap Allow before they get in.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(forName, { forName = it.take(40) }, label = { Text("Their name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button({ invite = app.family.newInvite(forName.ifBlank { null }) }, Modifier.fillMaxWidth().height(52.dp)) { Text("Create invite") }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QrImage(inv.encode(), 280.dp)
                Text("Together: on their phone open FamilyTalk › Join with invite QR and scan this.", textAlign = TextAlign.Center)
                OutlinedButton({
                    ctx.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, com.mypro.familytalk.core.family.Invite.shareMessage(inv)),
                        "Send invite by message",
                    ))
                }, Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Filled.Share, null); Spacer(Modifier.size(8.dp)); Text("Send invite by message") }
                Text("Far away: send it by WhatsApp, SMS or e-mail. They paste the whole message into FamilyTalk.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Anyone who sees that message could ask to join, so before you tap Allow, phone them and check the digits on both screens match.",
                    fontSize = 14.sp, color = trustWarn(), textAlign = TextAlign.Center)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MembersScreen(app: FamilyTalkApp, nav: Nav) {
    val roster by app.family.roster.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    BackScaffold("Manage family", nav) {
        roster?.members?.sortedBy { it.ext }?.forEach { m ->
            Card {
                ListItem(
                    leadingContent = { Avatar(m.name, m.color) },
                    headlineContent = { Text(m.name + if (m.id == app.family.myId) " (you)" else "") },
                    supportingContent = { Text("Ext ${m.ext} · ${if (m.role == Role.ADMIN) "Admin" else "Member"}") },
                )
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                    TextButton({ rename = m.id; newName = m.name }) { Text("Rename") }
                    if (m.id != app.family.myId) {
                        TextButton({ app.family.setRole(m.id, if (m.role == Role.ADMIN) Role.MEMBER else Role.ADMIN) }) { Text(if (m.role == Role.ADMIN) "Make member" else "Make admin") }
                        TextButton({ confirm = m.id }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        Text("Lost phone? Remove it here straight away. It will stop receiving calls and messages from everyone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    rename?.let { id ->
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(newName, { newName = it.take(40) }, singleLine = true) },
            confirmButton = { TextButton({ if (newName.isNotBlank()) app.family.renameMember(id, newName); rename = null }) { Text("Save") } },
            dismissButton = { TextButton({ rename = null }) { Text("Cancel") } },
        )
    }
    confirm?.let { id ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove ${roster?.byId(id)?.name}?") },
            text = { Text("Their phone will be cut off from the family. They can only come back with a new invite.") },
            confirmButton = { TextButton({ app.family.removeMember(id); confirm = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}
