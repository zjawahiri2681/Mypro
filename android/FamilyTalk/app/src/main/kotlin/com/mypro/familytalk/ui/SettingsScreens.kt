package com.mypro.familytalk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mypro.familytalk.FamilyTalkApp
import com.mypro.familytalk.core.crypto.Bytes
import com.mypro.familytalk.core.family.IceServer
import com.mypro.familytalk.core.family.RelayConfig
import com.mypro.familytalk.core.sip.MediaEncryption
import com.mypro.familytalk.core.sip.SipAccount
import com.mypro.familytalk.core.sip.SipTransport
import com.mypro.familytalk.service.LinkService

@Composable
fun SettingsTab(app: FamilyTalkApp, nav: Nav) {
    val data by app.family.data.collectAsStateWithLifecycle()
    val roster by app.family.roster.collectAsStateWithLifecycle()
    val sipUi by app.sip.ui.collectAsStateWithLifecycle()
    val me = roster?.byId(app.family.myId)
    var wipe by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionTitle("You")
        ListItem(
            leadingContent = { Avatar(me?.name ?: "?", me?.color ?: 0) },
            headlineContent = { Text(me?.name ?: "") },
            supportingContent = { Text("Extension \u2066${me?.ext}\u2069 · ${if (app.family.isAdmin) "Family admin" else "Member"}") },
        )
        SectionTitle("Family")
        if (app.family.isAdmin) {
            Item(Icons.Filled.PersonAdd, "Invite a family member", "Show a one-time QR code") { nav.go(Screen.Invite) }
            Item(Icons.Filled.Groups, "Manage family", "Rename, make admin, remove a lost phone") { nav.go(Screen.Members) }
            Item(Icons.Filled.Dns, "Relay & TURN servers", if (roster?.relay?.broker == RelayConfig.DEFAULT_BROKER) "Public relay (encrypted). Tap to use your own." else "Your own server") { nav.go(Screen.Network) }
        } else {
            Item(Icons.Filled.Person, "Family admin", roster?.admins?.joinToString { "${it.name} (${it.ext})" } ?: "") {}
        }
        SectionTitle("Calls")
        ListItem(
            leadingContent = { Icon(Icons.Filled.NotificationsActive, null) },
            headlineContent = { Text("Family calls ring even on silent") },
            supportingContent = { Text("Rings like an alarm when this phone or tablet is on silent or Do Not Disturb, so you never miss family. Outside (SIP) calls follow your normal sound settings.") },
            trailingContent = { Switch(data.ringOnSilent, { app.family.setRingOnSilent(it) }) },
        )
        SectionTitle("Outside calls")
        Item(Icons.Filled.PhoneForwarded, "SIP account", if (data.sip == null) "Not set up — needed for phone numbers" else "${data.sip!!.identityUri} · ${if (sipUi.registered) "Ready" else sipUi.registration}") { nav.go(Screen.Sip) }
        SectionTitle("Privacy")
        val hasTurn = roster?.relay?.ice?.any { s -> s.urls.any { it.startsWith("turn") } } == true
        ListItem(
            leadingContent = { Icon(Icons.Filled.VpnLock, null) },
            headlineContent = { Text("Hide my IP address") },
            supportingContent = {
                Text(if (hasTurn) "Send call media through your family's TURN server instead of directly to the other phone."
                     else "Needs a TURN server. Ask the family admin to add one (Settings › Relay & TURN servers).")
            },
            trailingContent = { Switch(data.relayOnly && hasTurn, { app.family.setRelayOnly(it) }, enabled = hasTurn) },
        )
        ListItem(
            leadingContent = { Icon(Icons.Filled.Screenshot, null) },
            headlineContent = { Text("Block screenshots") },
            supportingContent = { Text("Also hides chats in the recent-apps view.") },
            trailingContent = { Switch(data.secureScreen, { app.family.setSecureScreen(it) }) },
        )
        ListItem(
            leadingContent = { Icon(Icons.Filled.Info, null) },
            headlineContent = { Text("Show message text in notifications") },
            supportingContent = { Text("Off: notifications only say who wrote. Smart watches and other apps then can't read your messages.") },
            trailingContent = { Switch(data.notifPreview, { app.family.setNotifPreview(it) }) },
        )
        Item(Icons.Filled.Info, "How FamilyTalk protects you", "Encryption, what servers can see, limits") { nav.go(Screen.Security) }
        SectionTitle("App updates")
        UpdateSettings(app)
        SectionTitle("Danger zone")
        Item(Icons.AutoMirrored.Filled.ExitToApp, "Leave family and erase this phone", "Deletes your keys, chats and files", danger = true) { wipe = true }
    }
    if (wipe) AlertDialog(
        onDismissRequest = { wipe = false },
        title = { Text("Erase everything?") },
        text = { Text("Your keys, chats and received files are destroyed. You'll need a new invite to come back." + if (app.family.isAdmin) "\n\nYou are an admin: make someone else admin first, or nobody can invite new people." else "") },
        confirmButton = { TextButton({ wipe = false; app.sip.shutdown(); LinkService.stop(app); app.family.leaveAndWipe() }) { Text("Erase", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ wipe = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Item(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(title, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
        supportingContent = { Text(sub) },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SipScreen(app: FamilyTalkApp, nav: Nav) {
    val data by app.family.data.collectAsStateWithLifecycle()
    val sipUi by app.sip.ui.collectAsStateWithLifecycle()
    val cur = data.sip
    var user by remember { mutableStateOf(cur?.username ?: "") }
    var domain by remember { mutableStateOf(cur?.domain ?: "") }
    var pass by remember { mutableStateOf(cur?.password ?: "") }
    var display by remember { mutableStateOf(cur?.displayName ?: "") }
    var authUser by remember { mutableStateOf(cur?.authUsername ?: "") }
    var proxy by remember { mutableStateOf(cur?.proxy ?: "") }
    var port by remember { mutableStateOf(cur?.port?.toString() ?: "") }
    var transport by remember { mutableStateOf(cur?.transport ?: SipTransport.TLS) }
    var media by remember { mutableStateOf(cur?.mediaEncryption ?: MediaEncryption.SRTP_MANDATORY) }
    val draft = SipAccount(user.trim(), domain.trim(), pass, display.trim(), authUser.trim().ifBlank { null }, proxy.trim().ifBlank { null }, port.toIntOrNull(), transport, media)
    BackScaffold("SIP account", nav) {
        Text("Use any SIP provider or office/home PBX to call ordinary phone numbers (dial 9 or +) and SIP addresses. Status: ${sipUi.registration}",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Calls through SIP are protected only up to your provider. They are not end-to-end encrypted like family calls.", color = trustWarn())
        OutlinedTextField(user, { user = it }, label = { Text("Username / extension") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(domain, { domain = it }, label = { Text("Domain (e.g. sip.provider.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(display, { display = it }, label = { Text("Display name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(authUser, { authUser = it }, label = { Text("Auth username (if different)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(proxy, { proxy = it }, label = { Text("Outbound proxy (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("Transport", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SipTransport.entries.forEach { t -> FilterChip(transport == t, { transport = t }, { Text(t.name) }) } }
        Text("Voice encryption", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(media == MediaEncryption.SRTP_MANDATORY, { media = MediaEncryption.SRTP_MANDATORY }, { Text("SRTP required") })
            FilterChip(media == MediaEncryption.SRTP_OPTIONAL, { media = MediaEncryption.SRTP_OPTIONAL }, { Text("If available") })
            FilterChip(media == MediaEncryption.NONE, { media = MediaEncryption.NONE }, { Text("Off") })
        }
        draft.problems().forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
        draft.warnings().forEach { Text("⚠ $it", color = trustWarn()) }
        Button({ app.family.setSip(draft); nav.back() }, Modifier.fillMaxWidth().height(52.dp), enabled = draft.problems().isEmpty()) { Text("Save and register") }
        if (cur != null) TextButton({ app.family.setSip(null); nav.back() }) { Text("Remove SIP account", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun NetworkScreen(app: FamilyTalkApp, nav: Nav) {
    val roster by app.family.roster.collectAsStateWithLifecycle()
    val cfg = roster?.relay ?: RelayConfig()
    val turn = cfg.ice.firstOrNull { s -> s.urls.any { it.startsWith("turn") } }
    val customStun = cfg.ice.filter { it != RelayConfig.DEFAULT_STUN.first() && it.urls.all { u -> u.startsWith("stun:") } }.flatMap { it.urls }
    var broker by remember { mutableStateOf(cfg.broker) }
    var bUser by remember { mutableStateOf(cfg.brokerUser ?: "") }
    var bPass by remember { mutableStateOf(cfg.brokerPassword ?: "") }
    var turnUrl by remember { mutableStateOf(((turn?.urls ?: emptyList()) + customStun).joinToString(",\n")) }
    var turnUser by remember { mutableStateOf(turn?.username ?: "") }
    var turnPass by remember { mutableStateOf(turn?.credential ?: "") }
    val brokerOk = broker.startsWith("ssl://") || broker.startsWith("wss://")
    BackScaffold("Relay & TURN servers", nav) {
        Text("The relay passes sealed envelopes between phones; TURN carries encrypted media when phones cannot reach each other directly. Neither can read messages or hear calls. The default public relay can still see when your family's phones are online and active; running your own (see server/ in the project) keeps that private too. With a TURN server set, it is also used instead of Google's STUN.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Changes are sent to every family phone.", color = trustWarn())
        OutlinedTextField(broker, { broker = it.trim() }, label = { Text("MQTT broker (ssl://host:8883)") }, singleLine = true, isError = !brokerOk, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bUser, { bUser = it }, label = { Text("Broker username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bPass, { bPass = it }, label = { Text("Broker password (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text("Calls between a phone on mobile data and one on Wi-Fi often need a TURN server. Free option: create a free account at metered.ca (20 GB/month), open TURN Server › Credentials and copy the URLs, username and password here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(turnUrl, { turnUrl = it }, label = { Text("TURN/STUN URLs (comma or one per line)") }, placeholder = { Text("turn:global.relay.metered.ca:80, turns:global.relay.metered.ca:443?transport=tcp") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(turnUser, { turnUser = it }, label = { Text("TURN username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(turnPass, { turnPass = it }, label = { Text("TURN password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button({
            val ice = buildList {
                val all = turnUrl.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                val turn = all.filter { it.startsWith("turn:") || it.startsWith("turns:") }
                val stun = all.filter { it.startsWith("stun:") }
                // Public STUN always stays, so direct calls are still tried before using (paid/limited) TURN.
                addAll(RelayConfig.DEFAULT_STUN)
                if (stun.isNotEmpty()) add(IceServer(stun))
                if (turn.isNotEmpty()) add(IceServer(turn, turnUser.ifBlank { null }, turnPass.ifBlank { null }))
            }
            app.family.updateRelay(RelayConfig(broker, bUser.ifBlank { null }, bPass.ifBlank { null }, ice))
            nav.back()
        }, Modifier.fillMaxWidth().height(52.dp), enabled = brokerOk) { Text("Save for the whole family") }
        TextButton({ broker = RelayConfig.DEFAULT_BROKER; bUser = ""; bPass = "" }) { Text("Use the public relay") }
    }
}

@Composable
fun SecurityInfoScreen(nav: Nav) {
    BackScaffold("How you're protected", nav) {
        listOf(
            "Your keys" to "Each phone makes its own keys and never shares the private part. Keys and chats are stored encrypted with a key locked in the phone's secure hardware. Nothing is backed up to the cloud.",
            "Messages and files" to "Every message, file and call setup is encrypted for one recipient and signed by the sender (X25519 + ChaCha20-Poly1305 + Ed25519), then sealed again so the relay can't see who sent it or to which family it belongs. Keys rotate weekly and old ones are deleted after three weeks, so a phone stolen later can't unlock older recorded messages.",
            "Calls" to "Voice and video go phone-to-phone (peer-to-peer) with DTLS-SRTP and fresh keys for every call. The encryption keys are confirmed inside the signed messages, so no server can listen in. When a direct path is impossible, a TURN server forwards the still-encrypted media. In a conference you only ever send audio/video to people who accepted the call.",
            "Who's in the family" to "Only an admin can add or remove people, every phone checks the admin's signature, and every change is written into the family chat. Each new person must be approved on the admin's screen. Comparing safety numbers (tap the shield next to a name) proves you are talking to that person's phone — but an admin can still add someone, so keep admin phones locked and only make trusted adults admins.",
            "What servers can still see" to "The relay sees an anonymous inbox address per phone, when messages arrive, rough sizes, and IP addresses. Unless the admin sets your own TURN/STUN server, Google's STUN server sees your IP address when a call starts. Use your own server (Settings › Relay & TURN servers) and \"Hide my IP address\" to reduce this.",
            "Outside calls (SIP / phone numbers)" to "Calls to normal phone numbers go through your SIP provider and the phone network. They are encrypted to the provider at best — never end-to-end.",
            "What FamilyTalk can't protect against" to "A phone that is unlocked in someone else's hands, or infected with spyware, can see everything shown on it. Keep your phones updated and locked.",
        ).forEach { (t, b) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t, style = MaterialTheme.typography.titleMedium)
                Text(b, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
