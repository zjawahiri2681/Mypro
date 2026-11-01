package com.mypro.iptvbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mypro.iptvbox.IptvApp
import com.mypro.iptvbox.core.sync.WatchLink
import com.mypro.iptvbox.ui.screens.LiveScreen
import com.mypro.iptvbox.ui.screens.MoviesScreen
import com.mypro.iptvbox.ui.screens.OpenScreen
import com.mypro.iptvbox.ui.screens.PlaylistsScreen
import com.mypro.iptvbox.ui.screens.SeriesScreen
import com.mypro.iptvbox.ui.screens.SettingsScreen
import com.mypro.iptvbox.ui.screens.WatchScreen

enum class Section(val label: String, val icon: ImageVector) {
    LIVE("Live TV", Icons.Default.LiveTv),
    MOVIES("Movies", Icons.Default.Movie),
    SERIES("Series", Icons.Default.Tv),
    WATCH("Watch together", Icons.Default.Groups),
    OPEN("Open URL / file", Icons.Default.FolderOpen),
    PLAYLISTS("Playlists", Icons.Default.PlaylistPlay),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun HomeScreen(pendingLink: WatchLink?, onLinkConsumed: () -> Unit) {
    val repo = IptvApp.repo
    var section by rememberSaveable { mutableStateOf(Section.LIVE) }
    val playlists by repo.playlists.collectAsState()
    val loadState by repo.loadState.collectAsState()
    val content by repo.content.collectAsState()

    LaunchedEffect(pendingLink) { if (pendingLink != null) section = Section.WATCH }
    LaunchedEffect(playlists.isEmpty()) { if (playlists.isEmpty() && section in setOf(Section.LIVE, Section.MOVIES, Section.SERIES)) section = Section.PLAYLISTS }

    Row(Modifier.fillMaxSize().background(appBackgroundBrush())) {
        NavRail(section) { section = it }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (loadState.loading) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(loadState.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            loadState.error?.let { err ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error.copy(alpha = 0.2f)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text("Could not load playlist: $err", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { repo.refreshActive(force = true) }) { Text("Retry") }
                    TextButton(onClick = { section = Section.PLAYLISTS }) { Text("Edit") }
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (section) {
                    Section.LIVE -> if (content == null && !loadState.loading) NoPlaylist { section = Section.PLAYLISTS } else LiveScreen()
                    Section.MOVIES -> if (content == null && !loadState.loading) NoPlaylist { section = Section.PLAYLISTS } else MoviesScreen()
                    Section.SERIES -> if (content == null && !loadState.loading) NoPlaylist { section = Section.PLAYLISTS } else SeriesScreen()
                    Section.WATCH -> WatchScreen(pendingLink, onLinkConsumed)
                    Section.OPEN -> OpenScreen()
                    Section.PLAYLISTS -> PlaylistsScreen()
                    Section.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun NoPlaylist(onAdd: () -> Unit) {
    EmptyState("No playlist yet.\nAdd an M3U link, an Xtream Codes login or a Stalker portal to start watching.") {
        Button(onClick = onAdd) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add playlist") }
    }
}

@Composable
private fun NavRail(current: Section, onSelect: (Section) -> Unit) {
    val playlists by IptvApp.repo.playlists.collectAsState()
    val settings by IptvApp.settings.state.collectAsState()
    val active = playlists.firstOrNull { it.id == settings.activePlaylistId }
    Column(
        Modifier.width(220.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp).background(accentBrush(), MaterialTheme.shapes.medium).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ConnectedTv, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("IPTV Box", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
        }
        for (s in Section.entries) {
            FocusableRow(onClick = { onSelect(s) }, selected = s == current) {
                Icon(s.icon, null, tint = if (s == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(s.label, style = MaterialTheme.typography.bodyLarge, color = if (s == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        if (active != null) {
            Text("Playlist", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            Text(active.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        }
    }
}
