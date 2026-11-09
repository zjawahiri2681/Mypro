package com.mypro.iptvbox.ui.screens

import com.mypro.iptvbox.ui.tvFocus
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mypro.iptvbox.IptvApp
import com.mypro.iptvbox.core.sync.MediaRef
import com.mypro.iptvbox.core.sync.WatchLink
import com.mypro.iptvbox.player.PlayerActivity
import com.mypro.iptvbox.ui.SectionTitle
import com.mypro.iptvbox.util.Files
import com.mypro.iptvbox.watch.WatchSession

@Composable
fun WatchScreen(pendingLink: WatchLink?, onLinkConsumed: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<WatchLink?>(null) }
    var message by remember { mutableStateOf("") }
    val watch by WatchSession.state.collectAsState()

    LaunchedEffect(pendingLink) {
        if (pendingLink != null) { text = pendingLink.toUri(); parsed = pendingLink; onLinkConsumed() }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val link = parsed
        if (uri != null && link != null) {
            Files.persist(context, uri)
            val info = Files.info(context.contentResolver, uri)
            if (link.media.size > 0 && info.size > 0 && info.size != link.media.size) {
                message = "Warning: the chosen file (${info.size / 1_000_000} MB) is not the same size as the host's (${link.media.size / 1_000_000} MB). Playback may drift."
            }
            PlayerActivity.joinWatch(context, link, uri)
        }
    }

    fun join() {
        val link = WatchLink.parse(text) ?: run { message = "That is not a valid IPTV Box invitation link."; return }
        parsed = link
        message = ""
        if (link.media.kind == MediaRef.Kind.FILE) {
            filePicker.launch(arrayOf("video/*", "audio/*", "*/*"))
        } else {
            PlayerActivity.joinWatch(context, link, null)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionTitle("Watch together")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Watch the same stream, movie or local video file in sync with someone else. Anyone in the party can pause, play or seek.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Join a party", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        placeholder = { Text("Paste the iptvbox://join?… link here") }, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { IconButton(onClick = { clipboard.getText()?.text?.let { text = it } }) { Icon(Icons.Default.ContentPaste, "Paste") } },
                    )
                    val preview = WatchLink.parse(text)
                    if (preview != null) {
                        Text(
                            "Room ${preview.shortCode()} from ${preview.hostName.ifBlank { "a friend" }} · ${if (preview.mode == WatchLink.Mode.LAN) "same Wi-Fi" else "internet"} · " +
                                if (preview.media.kind == MediaRef.Kind.FILE) "local file ${preview.media.name}" else preview.media.name.ifBlank { preview.media.url },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(modifier = Modifier.tvFocus(), onClick = { join() }, enabled = text.isNotBlank()) { Icon(Icons.Default.Login, null); Spacer(Modifier.width(6.dp)); Text("Join") }
                        if (preview?.media?.kind == MediaRef.Kind.FILE) Text("You will be asked to pick the same video file.", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Start a party", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Play anything (a channel, a movie, a URL or a video file from Open URL / file), then press the people icon or choose Options → Watch together in the player. Share the link or let the other person scan the QR code.")
                    Text("• Over the internet: both devices need internet access. Messages go through a public relay, encrypted end-to-end with the key inside the link.\n• Same Wi-Fi: works with no internet at all, for example two devices at home watching a downloaded film.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (watch.role != WatchSession.Role.NONE) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (watch.role == WatchSession.Role.HOST) "You are hosting a party" else "You are in a party", style = MaterialTheme.typography.titleMedium)
                        Text("${watch.status} · ${watch.peers} connected")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(modifier = Modifier.tvFocus(), onClick = {
                                // Bring the running player back; never re-join our own room (that would end a hosted party).
                                context.startActivity(android.content.Intent(context, PlayerActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                            }) { Text("Back to player") }
                            OutlinedButton(modifier = Modifier.tvFocus(), onClick = { WatchSession.leave() }) { Text("Leave") }
                        }
                    }
                }
            }
            val settings by IptvApp.settings.state.collectAsState()
            Text("Your name in parties: ${settings.displayName} (change it under Settings)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
