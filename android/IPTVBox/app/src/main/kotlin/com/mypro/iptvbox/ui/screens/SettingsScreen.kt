package com.mypro.iptvbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mypro.iptvbox.BuildConfig
import com.mypro.iptvbox.IptvApp
import com.mypro.iptvbox.core.sync.WatchLink
import com.mypro.iptvbox.data.AppSettings
import com.mypro.iptvbox.data.BufferMode
import com.mypro.iptvbox.data.DecoderMode
import com.mypro.iptvbox.data.LiveFormat
import com.mypro.iptvbox.data.QualityCap
import com.mypro.iptvbox.data.ThemeId
import com.mypro.iptvbox.core.util.GroupMode
import com.mypro.iptvbox.core.util.SortMode
import com.mypro.iptvbox.ui.FocusableRow
import com.mypro.iptvbox.ui.SectionTitle

@Composable
fun SettingsScreen() {
    val store = IptvApp.settings
    val s by store.state.collectAsState()
    fun set(block: (AppSettings) -> AppSettings) = store.update(block)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionTitle("Settings")
        Group("Appearance")
        Text("Colour theme: ${s.theme.label}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ThemeId.entries) { t -> ThemeSwatch(t, selected = t == s.theme) { set { it.copy(theme = t) } } }
        }

        Group("Playback")
        CycleRow("Video quality", s.qualityCap.label, "Caps the resolution on streams that offer several (HLS/DASH). Also changeable per stream from the player menu.") { set { it.copy(qualityCap = next(it.qualityCap)) } }
        CycleRow("Buffer size", s.bufferMode.label, "Larger buffers survive bad connections; smaller ones zap faster.") { set { it.copy(bufferMode = next(it.bufferMode)) } }
        CycleRow("Live stream format (Xtream)", s.liveFormat.label, "Takes effect after the playlist is refreshed.") { set { it.copy(liveFormat = next(it.liveFormat)) } }
        CycleRow("Video decoder", s.decoderMode.label, "Change if some channels show a black screen or stutter.") { set { it.copy(decoderMode = next(it.decoderMode)) } }
        SwitchRow("Tunnelled playback", s.tunneling, "Smoother 4K/HDR on some TV boxes; turn off if audio drifts.") { set { it.copy(tunneling = !it.tunneling) } }
        SwitchRow("Picture-in-picture", s.pipEnabled, "Keep watching in a small window when you press Home.") { set { it.copy(pipEnabled = !it.pipEnabled) } }
        CycleRow("Hide player info after", "${s.overlayTimeoutSec} s") { set { it.copy(overlayTimeoutSec = listOf(3, 6, 10, 15).let { l -> l[(l.indexOf(it.overlayTimeoutSec) + 1) % l.size] }) } }
        TextRow("Preferred audio language", s.preferredAudioLang, "e.g. en, ar, fr - leave empty for the stream default") { v -> set { it.copy(preferredAudioLang = v) } }
        TextRow("Preferred subtitle language", s.preferredSubtitleLang, "e.g. en - empty = subtitles off unless chosen") { v -> set { it.copy(preferredSubtitleLang = v) } }

        Group("Channels & guide")
        CycleRow("Group channels by", s.groupMode.label, "Provider groups as delivered, or detected country / language.") { set { it.copy(groupMode = next(it.groupMode)) } }
        CycleRow("Sort channels by", s.sortMode.label) { set { it.copy(sortMode = next(it.sortMode)) } }
        SwitchRow("Start with the last channel", s.autoPlayLast, "Behave like a TV: open straight into live TV.") { set { it.copy(autoPlayLast = !it.autoPlayLast) } }
        SwitchRow("Show channel numbers", s.showChannelNumbers) { set { it.copy(showChannelNumbers = !it.showChannelNumbers) } }
        CycleRow("Refresh programme guide every", "${s.epgRefreshHours} h") { set { it.copy(epgRefreshHours = listOf(6, 12, 24).let { l -> l[(l.indexOf(it.epgRefreshHours) + 1) % l.size] }) } }
        FocusableRow(onClick = { IptvApp.repo.refreshEpg() }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Column { Text("Reload programme guide now", style = MaterialTheme.typography.bodyLarge); Text("Downloads the XMLTV guide again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        Group("Network")
        TextRow("User-Agent", s.userAgent, "Sent to playlist and stream servers. Empty = IPTV Box default. Some providers need a VLC or TiviMate style agent.") { v -> set { it.copy(userAgent = v) } }

        Group("Watch together")
        TextRow("Your name", s.displayName, "Shown to people you watch with.") { v -> set { it.copy(displayName = v.ifBlank { "TV" }) } }
        TextRow("Relay server (MQTT)", s.relayBroker, "Public broker used for internet parties. Messages are end-to-end encrypted. Default: ${WatchLink.DEFAULT_BROKER}") { v -> set { it.copy(relayBroker = v.ifBlank { WatchLink.DEFAULT_BROKER }) } }

        Group("About")
        Text(
            "IPTV Box ${BuildConfig.VERSION_NAME} · personal build.\nPlays M3U/M3U8 playlists, Xtream Codes and Stalker portals with EPG, catch-up, favourites, VOD and series, plus synchronised watch parties.\nThis app contains no channels or content; it only plays what your own playlists and files provide.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

private inline fun <reified E : Enum<E>> next(e: E): E { val v = enumValues<E>(); return v[(e.ordinal + 1) % v.size] }

@Composable
private fun ThemeSwatch(t: ThemeId, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
        Box(
            Modifier.size(56.dp)
                .onFocusChanged { focused = it.isFocused }
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(t.primary), Color(t.secondary))))
                .border(if (focused || selected) 3.dp else 1.dp, if (focused) Color.White else if (selected) Color(t.primary) else Color.White.copy(alpha = 0.25f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(Color(t.background)))
            if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(t.label.substringBefore(" ("), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Group(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun CycleRow(title: String, value: String, hint: String = "", onClick: () -> Unit) {
    FocusableRow(onClick = onClick, modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, hint: String = "", onToggle: () -> Unit) {
    FocusableRow(onClick = onToggle, modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() }, modifier = Modifier.focusProperties { canFocus = false })
    }
}

@Composable
private fun TextRow(title: String, value: String, hint: String = "", onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value) }
    FocusableRow(onClick = { draft = value; editing = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { onChange(draft.trim()); editing = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}
