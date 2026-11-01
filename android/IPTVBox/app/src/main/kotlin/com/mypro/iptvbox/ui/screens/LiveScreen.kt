package com.mypro.iptvbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mypro.iptvbox.IptvApp
import com.mypro.iptvbox.core.model.Channel
import com.mypro.iptvbox.core.util.ChannelOrganizer
import com.mypro.iptvbox.core.util.GroupMode
import com.mypro.iptvbox.core.util.SortMode
import com.mypro.iptvbox.core.util.TextUtil
import com.mypro.iptvbox.player.PlayerActivity
import com.mypro.iptvbox.ui.EmptyState
import com.mypro.iptvbox.ui.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.mypro.iptvbox.ui.FocusableRow
import com.mypro.iptvbox.ui.SearchField
import com.mypro.iptvbox.ui.tvFocus
import androidx.compose.foundation.lazy.LazyRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen() {
    val repo = IptvApp.repo
    val context = LocalContext.current
    val content by repo.content.collectAsState()
    val favorites by repo.favorites.collectAsState()
    val recents by repo.recents.collectAsState()
    val epg by repo.epg.collectAsState()
    val settings by IptvApp.settings.state.collectAsState()

    val channels = content?.channels ?: emptyList()
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("All channels") }
    var focused by remember { mutableStateOf<Channel?>(null) }

    val mode = settings.groupMode
    val sortMode = settings.sortMode
    // Grouping, sorting and search run off the main thread so large playlists never freeze the UI.
    val organizerState by produceState<ChannelOrganizer?>(null, channels) {
        value = withContext(Dispatchers.Default) { ChannelOrganizer.of(channels) }
    }
    var debouncedQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) { if (query.isEmpty()) debouncedQuery = "" else { delay(250); debouncedQuery = query } }
    val groupInfos by produceState(emptyList<ChannelOrganizer.Group>(), organizerState, mode) {
        val o = organizerState
        value = if (o == null) emptyList() else withContext(Dispatchers.Default) { o.groups(mode) }
    }
    val groups = remember(groupInfos) { listOf("All channels", "Favourites", "Recent") + groupInfos.map { it.key } }
    LaunchedEffect(groups) { if (groupInfos.isNotEmpty() && group !in groups) group = "All channels" }
    val shown by produceState(emptyList<Channel>(), organizerState, group, debouncedQuery, favorites, recents, mode, sortMode) {
        val o = organizerState ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val base = when (group) {
                "All channels" -> ChannelOrganizer.sort(channels, sortMode)
                "Favourites" -> ChannelOrganizer.sort(channels.filter { it.id in favorites }, sortMode)
                "Recent" -> { val byId = channels.associateBy { it.id }; recents.mapNotNull { byId[it] } }
                else -> o.channelsIn(mode, group, sortMode)
            }
            o.search(base, debouncedQuery)
        }
    }

    if (channels.isEmpty()) {
        EmptyState("This playlist has no live channels.")
        return
    }
    val organizer = organizerState
    if (organizer == null) {
        LoadingState("Sorting ${channels.size} channels…")
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // <560dp: chips + list.  560-899dp: groups + list.  900dp+: groups + list + details.
        val narrow = maxWidth < 560.dp
        val showDetails = maxWidth >= 900.dp
        val groupWidth = if (maxWidth >= 1100.dp) 250.dp else 210.dp

        @Composable
        fun ModeTabs(modifier: Modifier = Modifier) {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((m, label) in listOf(GroupMode.PROVIDER to "Groups", GroupMode.COUNTRY to "Country", GroupMode.LANGUAGE to "Language")) {
                    FocusableRow(
                        onClick = { IptvApp.settings.update { it.copy(groupMode = m) } }, selected = m == mode,
                        modifier = Modifier.weight(1f), padding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) { Text(label, Modifier.fillMaxWidth(), maxLines = 1, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
            }
        }

        fun countOf(g: String) = when (g) {
            "All channels" -> channels.size
            "Favourites" -> favorites.count { f -> channels.any { it.id == f } }
            "Recent" -> recents.size
            else -> groupInfos.firstOrNull { it.key == g }?.count ?: 0
        }

        Row(Modifier.fillMaxSize()) {
            if (!narrow) {
                // Groups (provider / country / language)
                Column(Modifier.width(groupWidth).fillMaxHeight().padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), MaterialTheme.shapes.medium).padding(6.dp)) {
                    ModeTabs(Modifier.fillMaxWidth().padding(bottom = 6.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(groups) { g ->
                            FocusableRow(onClick = { group = g }, selected = g == group, onFocused = { group = g }) {
                                Text(g, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text("${countOf(g)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            // Channels
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchField(query, { query = it }, "Search channels or type a number", Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { IptvApp.settings.update { it.copy(sortMode = if (it.sortMode == SortMode.NUMBER) SortMode.NAME else SortMode.NUMBER) } },
                        modifier = Modifier.tvFocus(),
                    ) {
                        Icon(Icons.Default.SortByAlpha, "Sort", modifier = Modifier.size(18.dp))
                        if (!narrow) { Spacer(Modifier.width(6.dp)); Text(if (sortMode == SortMode.NUMBER) "By number" else "A–Z") }
                    }
                }
                if (narrow) {
                    ModeTabs(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(groups) { g ->
                            FilterChip(selected = g == group, onClick = { group = g }, label = { Text("$g (${countOf(g)})", maxLines = 1) }, modifier = Modifier.tvFocus(MaterialTheme.shapes.small))
                        }
                    }
                }
                Text(
                    "${shown.size} channels · long-press a channel to add it to favourites",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                if (shown.isEmpty()) {
                    EmptyState(if (group == "Favourites") "No favourites yet.\nLong-press a channel, or press the star on its details." else "No channels match.")
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(shown, key = { it.id }) { c ->
                            val (now, _) = remember(c.id, epg) { epg.nowNext(c) }
                            FocusableRow(
                                onClick = { PlayerActivity.playChannel(context, c, if (group == "Recent") "All channels" else group) },
                                onFocused = { focused = c },
                                onLongClick = { repo.toggleFavorite(c.id) },
                            ) {
                                if (settings.showChannelNumbers) Text("${c.number}", Modifier.width(52.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Logo(c.logo, 48)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                    if (now != null) {
                                        Text("${timeFmt.format(Date(now.start))}  ${now.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        val progress = ((System.currentTimeMillis() - now.start).toFloat() / now.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.6f).padding(top = 4.dp).height(3.dp), trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))
                                    }
                                }
                                if (c.id in favorites) Icon(Icons.Default.Star, "Favourite", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                                if (c.catchupDays > 0) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.History, "Catch-up available", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
            }
            // Details
            val d = focused?.takeIf { f -> shown.any { it.id == f.id } } ?: shown.firstOrNull()
            if (showDetails) Column(
                Modifier.width(290.dp).fillMaxHeight().padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), MaterialTheme.shapes.medium).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (d != null) {
                    Logo(d.logo, 96)
                    Text(d.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(d.group.ifBlank { "Other" }, organizer.countryOf(d)?.let { "${it.flag} ${it.name}" }, organizer.languageOf(d).takeIf { it != ChannelOrganizer.OTHER }).filterNotNull().joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val (now, next) = epg.nowNext(d)
                    if (now != null) {
                        Text("NOW · ${timeFmt.format(Date(now.start))}–${timeFmt.format(Date(now.stop))}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(now.title, style = MaterialTheme.typography.titleMedium)
                        if (now.description.isNotBlank()) Text(now.description, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("No programme information", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (next != null) Text("NEXT · ${timeFmt.format(Date(next.start))}  ${next.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { PlayerActivity.playChannel(context, d, group) }, modifier = Modifier.tvFocus()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play") }
                        OutlinedButton(onClick = { repo.toggleFavorite(d.id) }, modifier = Modifier.tvFocus()) {
                            Icon(if (d.id in favorites) Icons.Default.Star else Icons.Default.StarBorder, if (d.id in favorites) "Remove from favourites" else "Add to favourites")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Logo(url: String, size: Int) {
    val shape = MaterialTheme.shapes.small
    if (url.isNotBlank()) {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size.dp).clip(shape).background(Color.White.copy(alpha = 0.06f)))
    } else {
        Box(Modifier.size(size.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Tv, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size((size / 2).dp))
        }
    }
}
