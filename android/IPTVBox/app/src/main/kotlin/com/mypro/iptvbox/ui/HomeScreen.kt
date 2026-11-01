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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

enum class Section(val label: String, val short: String, val icon: ImageVector) {
    LIVE("Live TV", "Live", Icons.Default.LiveTv),
    MOVIES("Movies", "Movies", Icons.Default.Movie),
    SERIES("Series", "Series", Icons.Default.VideoLibrary),
    WATCH("Watch together", "Party", Icons.Default.Groups),
    OPEN("Open URL / file", "Open", Icons.Default.FolderOpen),
    PLAYLISTS("Playlists", "Playlists", Icons.Default.PlaylistPlay),
    SETTINGS("Settings", "Settings", Icons.Default.Settings),
}

private enum class NavStyle { BOTTOM, COMPACT_RAIL, FULL_RAIL }

@Composable
fun HomeScreen(pendingLink: WatchLink?, onLinkConsumed: () -> Unit) {
    val repo = IptvApp.repo
    var section by rememberSaveable { mutableStateOf(Section.LIVE) }
    val playlists by repo.playlists.collectAsState()
    val loadState by repo.loadState.collectAsState()
    val content by repo.content.collectAsState()
    val isTv = LocalIsTv.current

    LaunchedEffect(pendingLink) { if (pendingLink != null) section = Section.WATCH }
    LaunchedEffect(playlists.isEmpty()) { if (playlists.isEmpty() && section in setOf(Section.LIVE, Section.MOVIES, Section.SERIES)) section = Section.PLAYLISTS }

    BoxWithConstraints(Modifier.fillMaxSize().background(appBackgroundBrush())) {
        val nav = when {
            maxWidth < 600.dp && !isTv -> NavStyle.BOTTOM
            maxWidth >= 1240.dp -> NavStyle.FULL_RAIL
            else -> NavStyle.COMPACT_RAIL
        }
        val body: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                StatusBanners(loadState, onEdit = { section = Section.PLAYLISTS })
                Box(Modifier.fillMaxSize()) {
                    val waitingForFirstLoad = content == null && loadState.loading
                    when (section) {
                        Section.LIVE, Section.MOVIES, Section.SERIES -> when {
                            waitingForFirstLoad -> LoadingState(loadState.message)
                            content == null -> NoPlaylist { section = Section.PLAYLISTS }
                            section == Section.LIVE -> LiveScreen()
                            section == Section.MOVIES -> MoviesScreen()
                            else -> SeriesScreen()
                        }
                        Section.WATCH -> WatchScreen(pendingLink, onLinkConsumed)
                        Section.OPEN -> OpenScreen()
                        Section.PLAYLISTS -> PlaylistsScreen()
                        Section.SETTINGS -> SettingsScreen()
                    }
                }
            }
        }
        if (nav == NavStyle.BOTTOM) {
            Column(Modifier.fillMaxSize()) {
                body(Modifier.weight(1f).fillMaxWidth())
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    // Phones get the five most-used sections; Open and Settings sit behind "More".
                    val primary = listOf(Section.LIVE, Section.MOVIES, Section.SERIES, Section.WATCH)
                    for (s in primary) {
                        NavigationBarItem(selected = s == section, onClick = { section = s }, icon = { Icon(s.icon, null) }, label = { Text(s.short, maxLines = 1) })
                    }
                    val moreSelected = section !in primary
                    var menu by remember { mutableStateOf(false) }
                    NavigationBarItem(
                        selected = moreSelected, onClick = { menu = true },
                        icon = {
                            Box {
                                Icon(Icons.Default.MoreHoriz, null)
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    for (s in listOf(Section.OPEN, Section.PLAYLISTS, Section.SETTINGS)) {
                                        DropdownMenuItem(text = { Text(s.label) }, leadingIcon = { Icon(s.icon, null) }, onClick = { section = s; menu = false })
                                    }
                                }
                            }
                        },
                        label = { Text(if (moreSelected) section.short else "More", maxLines = 1) },
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxSize().padding(safeAreaPadding())) {
                NavRail(section, full = nav == NavStyle.FULL_RAIL) { section = it }
                body(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun StatusBanners(loadState: com.mypro.iptvbox.data.LoadState, onEdit: () -> Unit) {
    val repo = IptvApp.repo
    if (loadState.loading && repo.content.collectAsState().value != null) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(loadState.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
    loadState.error?.let { err ->
        Row(
            Modifier.fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f).over(MaterialTheme.colorScheme.surface), MaterialTheme.shapes.small)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Text("Could not load playlist: $err", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { repo.refreshActive(force = true) }, modifier = Modifier.tvFocus()) { Text("Retry") }
            TextButton(onClick = onEdit, modifier = Modifier.tvFocus()) { Text("Edit") }
        }
    }
}

@Composable
private fun NoPlaylist(onAdd: () -> Unit) {
    EmptyState("No playlist yet.\nAdd an M3U link, an Xtream Codes login or a Stalker portal to start watching.") {
        Button(onClick = onAdd, modifier = Modifier.tvFocus()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add playlist") }
    }
}

@Composable
private fun NavRail(current: Section, full: Boolean, onSelect: (Section) -> Unit) {
    val playlists by IptvApp.repo.playlists.collectAsState()
    val settings by IptvApp.settings.state.collectAsState()
    val active = playlists.firstOrNull { it.id == settings.activePlaylistId }
    val startFocus = remember { FocusRequester() }
    // Give the remote a starting point: focus the current section once at launch.
    LaunchedEffect(Unit) { runCatching { startFocus.requestFocus() } }
    Column(
        Modifier.width(if (full) 220.dp else 96.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), MaterialTheme.shapes.large)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).background(accentBrush(), MaterialTheme.shapes.medium).padding(vertical = 12.dp, horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ConnectedTv, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                if (full) {
                    Spacer(Modifier.width(10.dp))
                    Text("IPTV Box", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        for (s in Section.entries) {
            val selected = s == current
            val m = if (selected) Modifier.focusRequester(startFocus) else Modifier
            if (full) {
                FocusableRow(onClick = { onSelect(s) }, selected = selected, modifier = m) {
                    Icon(s.icon, null)
                    Spacer(Modifier.width(12.dp))
                    Text(s.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                FocusableRow(onClick = { onSelect(s) }, selected = selected, modifier = m, padding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(s.icon, s.label)
                        Text(s.short, style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (active != null && full) {
            Text("Playlist", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(start = 12.dp))
            Text(active.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp))
        }
    }
}
