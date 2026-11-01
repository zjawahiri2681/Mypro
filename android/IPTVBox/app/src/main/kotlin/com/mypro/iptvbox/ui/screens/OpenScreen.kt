package com.mypro.iptvbox.ui.screens

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mypro.iptvbox.player.PlayerActivity
import com.mypro.iptvbox.ui.SectionTitle
import com.mypro.iptvbox.util.Files

@Composable
fun OpenScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var url by rememberSaveable { mutableStateOf("") }
    var live by rememberSaveable { mutableStateOf(false) }
    var headers by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            Files.persist(context, uri)
            val info = Files.info(context.contentResolver, uri)
            PlayerActivity.playUrl(context, uri.toString(), info.name)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionTitle("Open URL or file")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Stream URL", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("http://… .m3u8 / .ts / .mp4 / .mkv / .mpd / rtsp://…") },
                        trailingIcon = { IconButton(onClick = { clipboard.getText()?.text?.let { url = it.trim() } }) { Icon(Icons.Default.ContentPaste, "Paste") } },
                    )
                    OutlinedTextField(
                        value = headers, onValueChange = { headers = it }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3,
                        label = { Text("Optional HTTP headers (one per line, Key=Value)") }, placeholder = { Text("Referer=https://example.com") },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = live, onCheckedChange = { live = it })
                        Text("Treat as live stream (auto-reconnect, no seeking)")
                    }
                    Button(onClick = { PlayerActivity.playUrl(context, url.trim(), url.trim(), headers, live) }, enabled = url.isNotBlank()) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Video file on this device", style = MaterialTheme.typography.titleMedium)
                    Text("Plays MP4, MKV, TS, AVI, WebM and more, from internal storage, SD card or USB drive. Local files can also be shared in a watch party on the same Wi-Fi.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { picker.launch(arrayOf("video/*", "audio/*", "*/*")) }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Choose file") }
                }
            }
            Text("Tip: IPTV Box registers as a video player, so you can also open streams and files from a browser, file manager or messaging app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
