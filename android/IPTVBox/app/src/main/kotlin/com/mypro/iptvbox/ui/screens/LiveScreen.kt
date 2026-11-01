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
import com.mypro.iptvbox.ui.FocusableRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

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
    val organizer = remember(channels) { ChannelOrganizer.of(channels) }
    val groupInfos = remember(channels, mode) { organizer.groups(mode) }
    val groups = remember(groupInfos) { listOf("All channels", "Favourites", "Recent") + groupInfos.map { it.key } }
    LaunchedEffect(groups) { if (group !in groups) group = "All channels" }
    val shown = remember(channels, group, query, favorites, recents, mode, sortMode) {
        val base = when (group) {
            "All channels" -> ChannelOrganizer.sort(channels, sortMode)
            "Favourites" -> ChannelOrganizer.sort(channels.filter { it.id in favorites }, sortMode)
            "Recent" -> recents.mapNotNull { id -> channels.firstOrNull { it.id == id } }
            else -> organizer.channelsIn(mode, group, sortMode)
        }
        if (query.isBlank()) base else {
            val q = TextUtil.normalize(query)
            base.filter { TextUtil.normalize(it.name).contains(q) || it.number.toString() == query.trim() }
        }
    }

    if (channels.isEmpty()) {
        EmptyState("This playlist has no live channels.")
        return
    }

    Row(Modifier.fillMaxSize()) {
        // Groups (provider / country / language)
        Column(Modifier.width(250.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)).padding(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((m, label) in listOf(GroupMode.PROVIDER to "Groups", GroupMode.COUNTRY to "Country", GroupMode.LANGUAGE to "Language")) {
                    FocusableRow(
                        onClick = { IptvApp.settings.update { it.copy(groupMode = m) } }, selected = m == mode,
                        modifier = Modifier.weight(1f), padding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    ) { Text(label, Modifier.fillMaxWidth(), maxLines = 1, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (m == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups) { g ->
                    val count = when (g) { "All channels" -> channels.size; "Favourites" -> favorites.count { f -> channels.any { it.id == f } }; "Recent" -> recents.size; else -> groupInfos.firstOrNull { it.key == g }?.count ?: 0 }
                    FocusableRow(onClick = { group = g }, selected = g == group, onFocused = { group = g }) {
                        Text(g, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // Channels
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search channels or type a number") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") } },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { IptvApp.settings.update { it.copy(sortMode = if (it.sortMode == SortMode.NUMBER) SortMode.NAME else SortMode.NUMBER) } }) {
                    Icon(Icons.Default.SortByAlpha, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (sortMode == SortMode.NUMBER) "By number" else "A–Z")
                }
            }
            if (shown.isEmpty()) {
                EmptyState(if (group == "Favourites") "No favourites yet. Open a channel's details and press the star." else "No channels match.")
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(shown, key = { it.id }) { c ->
                        val (now, _) = remember(c.id, epg) { epg.nowNext(c) }
                        FocusableRow(
                            onClick = { PlayerActivity.playChannel(context, c, if (group == "Recent") "All channels" else group) },
                            onFocused = { focused = c },
                            onLongClick = { repo.toggleFavorite(c.id) },
                        ) {
                            if (settings.showChannelNumbers) Text("${c.number}", Modifier.width(48.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Logo(c.logo, 44)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                if (now != null) {
                                    Text("${timeFmt.format(Date(now.start))}  ${now.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val progress = ((System.currentTimeMillis() - now.start).toFloat() / now.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.6f).padding(top = 4.dp).height(3.dp), trackColor = Color.White.copy(alpha = 0.12f))
                                }
                            }
                            if (c.id in favorites) Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                            if (c.catchupDays > 0) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.History, "Catch-up", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }
        // Details
        val d = focused ?: shown.firstOrNull()
        Column(Modifier.width(320.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (d != null) {
                Logo(d.logo, 96)
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(d.group.ifBlank { "Other" }, organizer.countryOf(d)?.let { "${it.flag} ${it.name}" }, organizer.languageOf(d).takeIf { it != ChannelOrganizer.OTHER }).filterNotNull().joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val (now, next) = epg.nowNext(d)
                if (now != null) {
                    Text("Now · ${timeFmt.format(Date(now.start))}–${timeFmt.format(Date(now.stop))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(now.title, style = MaterialTheme.typography.bodyLarge)
                    if (now.description.isNotBlank()) Text(now.description, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("No programme information", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (next != null) Text("Next · ${timeFmt.format(Date(next.start))}  ${next.title}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { PlayerActivity.playChannel(context, d, group) }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play") }
                    OutlinedButton(onClick = { repo.toggleFavorite(d.id) }) {
                        Icon(if (d.id in favorites) Icons.Default.Star else Icons.Default.StarBorder, null)
                    }
                }
            }
        }
    }
}

@Composable
fun Logo(url: String, size: Int) {
    if (url.isNotBlank()) {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.06f)))
    } else {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Tv, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size((size / 2).dp))
        }
    }
}
