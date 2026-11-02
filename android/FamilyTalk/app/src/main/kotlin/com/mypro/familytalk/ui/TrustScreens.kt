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
        Text(SafetyNumber.format(number).chunked(18).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), lineHeight = 30.sp)
        Button({ scan.launch(scanOptions().setPrompt("Scan ${m.name}'s safety code")) }, Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.size(8.dp)); Text("Scan their code")
        }
        OutlinedButton({ app.family.markVerified(memberId, !verified) }, Modifier.fillMaxWidth()) {
            Text(if (verified) "Clear verification" else "Numbers match — mark verified")
        }
        result?.let {
            Text(if (it) "✔ Match. ${m.name} is verified." else "✖ The codes do NOT match. Don't trust this connection until you've checked with the family admin.",
                color = if (it) Brand.Verified else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun InviteScreen(app: FamilyTalkApp, nav: Nav) {
    val ctx = LocalContext.current
    var forName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    BackScaffold("Invite a family member", nav) {
        if (code == null) {
            Text("The invite works once and expires in 24 hours. Show the QR in person — anyone who scans it can join your family.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(forName, { forName = it.take(40) }, label = { Text("Their name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button({ code = app.family.newInvite(forName.ifBlank { null }).encode() }, Modifier.fillMaxWidth().height(52.dp)) { Text("Create invite") }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QrImage(code!!, 280.dp)
                Text("On their phone: install FamilyTalk › Join with invite QR.", textAlign = TextAlign.Center)
                Text("Keep this screen open until they appear in the family list.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                TextButton({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code), "Send invite code"))
                }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.size(6.dp)); Text("Send code instead (less safe)") }
                Text("Only send the code through a channel you trust: whoever has it can join until it is used.", fontSize = 12.sp, color = Brand.Unverified, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun MembersScreen(app: FamilyTalkApp, nav: Nav) {
    val roster by app.family.roster.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    BackScaffold("Manage family", nav) {
        roster?.members?.sortedBy { it.ext }?.forEach { m ->
            Card {
                ListItem(
                    leadingContent = { Avatar(m.name, m.color) },
                    headlineContent = { Text(m.name + if (m.id == app.family.myId) " (you)" else "") },
                    supportingContent = { Text("Ext ${m.ext} · ${if (m.role == Role.ADMIN) "Admin" else "Member"}") },
                    trailingContent = {
                        if (m.id != app.family.myId) Row {
                            TextButton({ app.family.setRole(m.id, if (m.role == Role.ADMIN) Role.MEMBER else Role.ADMIN) }) { Text(if (m.role == Role.ADMIN) "Make member" else "Make admin") }
                            TextButton({ confirm = m.id }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        }
                    },
                )
            }
        }
        Text("Lost phone? Remove it here straight away. It will stop receiving calls and messages from everyone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
