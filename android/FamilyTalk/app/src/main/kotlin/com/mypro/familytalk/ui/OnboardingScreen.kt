package com.mypro.familytalk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mypro.familytalk.FamilyTalkApp
import com.mypro.familytalk.core.family.Invite
import com.mypro.familytalk.service.LinkService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun OnboardingScreen(app: FamilyTalkApp, removed: Boolean) {
    var mode by rememberSaveable { mutableStateOf("welcome") }
    var familyName by rememberSaveable { mutableStateOf("") }
    var myName by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { code = it; mode = "join" } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UpdateBanner(app)
            Spacer(Modifier.height(24.dp))
            Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text("FamilyTalk", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Private calls, video, chat and files for your family only. Family calls and chats are end-to-end encrypted on your phones — no accounts, no phone numbers, no ads.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (removed) Text("You were removed from your family. Start again or join with a new invite.", color = MaterialTheme.colorScheme.error)
            when (mode) {
                "welcome" -> {
                    Button({ mode = "create" }, Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Filled.GroupAdd, null); Spacer(Modifier.size(8.dp)); Text("Start a new family")
                    }
                    OutlinedButton({ scan.launch(scanOptions()) }, Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.size(8.dp)); Text("Join with invite QR")
                    }
                    TextButton({ mode = "join" }, Modifier.fillMaxWidth()) { Text("I have an invite code instead") }
                    Text(
                        "One person (usually a parent) starts the family and becomes extension 101. Everyone else joins by scanning the invite QR on that phone, in person.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                "create" -> {
                    OutlinedTextField(familyName, { familyName = it.take(40) }, label = { Text("Family name") }, placeholder = { Text("e.g. Al-Jawahiri family") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(myName, { myName = it.take(40) }, label = { Text("Your name") }, placeholder = { Text("e.g. Baba") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        {
                            runCatching { app.family.createFamily(familyName.ifBlank { "Our family" }, myName.ifBlank { "Me" }); LinkService.start(app) }
                                .onFailure { error = it.message }
                        },
                        Modifier.fillMaxWidth().height(56.dp), enabled = myName.isNotBlank(),
                    ) { Text("Create family — I'll be 101") }
                    TextButton({ mode = "welcome" }) { Text("Back") }
                }
                "join" -> {
                    val invite = runCatching { Invite.decode(code) }.getOrNull()
                    if (invite != null) {
                        Text("Invite to “${invite.familyName}” from ${invite.adminName}", style = MaterialTheme.typography.titleMedium)
                        androidx.compose.runtime.LaunchedEffect(invite.secret) { if (myName.isBlank()) invite.forName?.let { myName = it } }
                    } else {
                        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                        Button({ code = clipboard.getText()?.text ?: "" }, Modifier.fillMaxWidth().height(52.dp)) { Text("Paste invite message") }
                        OutlinedTextField(code, { code = it }, label = { Text("…or paste/type the invite here") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                        if (code.isNotBlank()) Text(runCatching { Invite.decode(code); "" }.exceptionOrNull()?.message ?: "", color = MaterialTheme.colorScheme.error)
                        OutlinedButton({ scan.launch(scanOptions()) }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.size(8.dp)); Text("Scan QR instead") }
                    }
                    OutlinedTextField(myName, { myName = it.take(40) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        {
                            error = null
                            runCatching { app.family.join(invite ?: error("That doesn't look like a FamilyTalk invite"), myName); LinkService.start(app) }
                                .onFailure { error = it.message }
                        },
                        Modifier.fillMaxWidth().height(56.dp), enabled = invite != null && myName.isNotBlank(),
                    ) { Text("Ask to join") }
                    TextButton({ mode = "welcome"; code = "" }) { Text("Back") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            CompactUpdate(app)
        }
    }
}

fun scanOptions(): ScanOptions = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the FamilyTalk QR").setBeepEnabled(false).setOrientationLocked(false)

@Composable
fun WaitingForAdmissionScreen(app: FamilyTalkApp) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
            UpdateBanner(app)
            CircularProgressIndicator()
            var waited by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
            androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); waited++ } }
            Text("Waiting for the family admin's phone…", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (waited > 60) Text("Still waiting. Make sure the admin's phone has FamilyTalk open and internet. If the invite was already used or is older than 24 hours, ask for a new one.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            Text("Their FamilyTalk needs to be online to add you, and they must tap Allow. You'll get your extension number as soon as you're in.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val online by app.family.relayConnected.collectAsStateWithLifecycle()
            val relayErr by app.family.relayError.collectAsStateWithLifecycle()
            val sentAt by app.family.joinSentAt.collectAsStateWithLifecycle()
            Text(
                when {
                    online && sentAt != null -> "● Connected (${app.family.relayNames}) · request sent ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sentAt!!))} (re-sent every 30 s)"
                    online -> "● Connected to relay"
                    relayErr != null -> "○ Can't reach the relay: $relayErr — check internet / VPN / firewall"
                    else -> "○ Connecting to relay…"
                },
                textAlign = TextAlign.Center, color = if (online) trustOk() else MaterialTheme.colorScheme.error,
            )
            if (online) TextButton({ app.family.sendJoinRequest() }) { Text("Send request again") }
            app.family.data.value.pinnedAdmin?.let { admin ->
                Text("The admin's phone will show a number. It should start with:", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    com.mypro.familytalk.core.crypto.SafetyNumber.format(com.mypro.familytalk.core.crypto.SafetyNumber.forPair(app.family.identity.public, admin)).take(23),
                    style = MaterialTheme.typography.titleLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
            Row { TextButton({ app.family.leaveAndWipe() }) { Text("Cancel and start over") } }
            CompactUpdate(app)
        }
    }
}
