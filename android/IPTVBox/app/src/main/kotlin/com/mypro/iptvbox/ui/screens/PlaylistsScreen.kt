package com.mypro.iptvbox.ui.screens

import com.mypro.iptvbox.ui.tvFocus
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mypro.iptvbox.IptvApp
import com.mypro.iptvbox.core.model.Playlist
import com.mypro.iptvbox.core.model.SourceType
import com.mypro.iptvbox.core.util.Ids
import com.mypro.iptvbox.ui.EmptyState
import com.mypro.iptvbox.ui.FocusableRow
import com.mypro.iptvbox.ui.SectionTitle
import com.mypro.iptvbox.util.Files
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun PlaylistsScreen() {
    val repo = IptvApp.repo
    val playlists by repo.playlists.collectAsState()
    val settings by IptvApp.settings.state.collectAsState()
    val content by repo.content.collectAsState()
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Playlist?>(null) }

    if (adding || editing != null) {
        PlaylistEditor(initial = editing, onDone = { adding = false; editing = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Playlists", Modifier.weight(1f))
            Button(modifier = Modifier.tvFocus(), onClick = { adding = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add playlist") }
        }
        if (playlists.isEmpty()) {
            EmptyState("Add your first playlist: an M3U/M3U8 link or file, an Xtream Codes login (server, username, password) or a Stalker/MAG portal.")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(playlists, key = { it.id }) { p ->
                    val isActive = p.id == settings.activePlaylistId
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (isActive) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("ACTIVE", Modifier.padding(horizontal = 4.dp)) }
                            }
                            Text(
                                when (p.type) { SourceType.M3U_URL -> "M3U link"; SourceType.M3U_FILE -> "M3U file"; SourceType.XTREAM -> "Xtream Codes"; SourceType.STALKER -> "Stalker portal" } +
                                    "  ·  " + p.url.take(70),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (isActive && content != null) {
                                Text(
                                    "${content!!.channels.size} channels · ${content!!.movies.size} movies · ${content!!.series.size} series · updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(content!!.loadedAt))}",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!isActive) Button(modifier = Modifier.tvFocus(), onClick = { repo.setActive(p.id) }) { Text("Use") }
                                else OutlinedButton(modifier = Modifier.tvFocus(), onClick = { repo.refreshActive(force = true) }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Refresh") }
                                OutlinedButton(modifier = Modifier.tvFocus(), onClick = { editing = p }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Edit") }
                                TextButton(modifier = Modifier.tvFocus(), onClick = { confirmDelete = p }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp)); Text("Delete", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("The playlist, its cached channels and guide are removed from this device. Favourites are kept.") },
            confirmButton = { Button(modifier = Modifier.tvFocus(), onClick = { repo.deletePlaylist(p.id); confirmDelete = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton(modifier = Modifier.tvFocus(), onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlaylistEditor(initial: Playlist?, onDone: () -> Unit) {
    val repo = IptvApp.repo
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: SourceType.M3U_URL) }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var mac by remember { mutableStateOf(initial?.macAddress ?: "00:1A:79:") }
    var epgUrl by remember { mutableStateOf(initial?.epgUrl ?: "") }
    var userAgent by remember { mutableStateOf(initial?.userAgent ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    BackHandler(onBack = onDone)
    val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            Files.persist(context, uri)
            url = uri.toString()
            fileName = Files.info(context.contentResolver, uri).name
            if (name.isBlank()) name = fileName.substringBeforeLast('.')
        }
    }

    fun build(): Playlist = Playlist(
        id = initial?.id ?: Ids.random(),
        name = name.trim().ifBlank { when (type) { SourceType.XTREAM -> "Xtream"; SourceType.STALKER -> "Portal"; else -> "Playlist" } },
        type = type,
        url = url.trim(),
        username = username.trim(),
        password = password,
        macAddress = mac.trim(),
        epgUrl = epgUrl.trim(),
        userAgent = userAgent.trim(),
    )

    val valid = when (type) {
        SourceType.M3U_URL, SourceType.M3U_FILE -> url.isNotBlank()
        SourceType.XTREAM -> url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        SourceType.STALKER -> url.isNotBlank() && mac.length >= 17
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(modifier = Modifier.tvFocus(), onClick = onDone) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Back") }
            Text(if (initial == null) "Add playlist" else "Edit playlist", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        Text("Source type", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((t, label) in listOf(SourceType.M3U_URL to "M3U link", SourceType.M3U_FILE to "M3U file", SourceType.XTREAM to "Xtream Codes", SourceType.STALKER to "Stalker portal")) {
                FocusableRow(onClick = { type = t }, selected = t == type, modifier = Modifier.width(IntrinsicSize.Max).then(if (t == type) Modifier.focusRequester(firstFocus) else Modifier), padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    Icon(if (t == type) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label)
                }
            }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        when (type) {
            SourceType.M3U_URL -> OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Playlist URL (http://…/get.php?… or any .m3u/.m3u8 link)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SourceType.M3U_FILE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(modifier = Modifier.tvFocus(), onClick = { filePicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Choose .m3u file") }
                Text(fileName.ifBlank { if (url.isNotBlank()) "File selected" else "No file selected" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SourceType.XTREAM -> {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Server (http://host:port)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.weight(1f),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
                    )
                }
            }
            SourceType.STALKER -> {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Portal URL (http://host/c/ or http://host/stalker_portal/)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = mac, onValueChange = { mac = it.uppercase() }, label = { Text("MAC address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Experimental: portals vary; live TV only.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(value = epgUrl, onValueChange = { epgUrl = it }, label = { Text("EPG / XMLTV URL (optional - auto-detected when the playlist provides one)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = userAgent, onValueChange = { userAgent = it }, label = { Text("User-Agent (optional, some providers require a specific one)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(modifier = Modifier.tvFocus(), onClick = {
                testing = true; testResult = null
                scope.launch { testResult = repo.test(build()).fold({ "OK: $it" }, { "Failed: ${it.message ?: it}" }); testing = false }
            }, enabled = valid && !testing) {
                if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.NetworkCheck, null)
                Spacer(Modifier.width(6.dp)); Text("Test")
            }
            Button(modifier = Modifier.tvFocus(), onClick = { repo.savePlaylist(build()); onDone() }, enabled = valid) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Save") }
        }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("OK")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
    }
}
