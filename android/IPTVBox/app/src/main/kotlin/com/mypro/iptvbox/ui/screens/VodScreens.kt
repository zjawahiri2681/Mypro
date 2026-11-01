package com.mypro.iptvbox.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mypro.iptvbox.IptvApp
import com.mypro.iptvbox.core.model.Episode
import com.mypro.iptvbox.core.model.SeriesItem
import com.mypro.iptvbox.core.util.TextUtil
import com.mypro.iptvbox.player.PlayerActivity
import com.mypro.iptvbox.ui.EmptyState
import com.mypro.iptvbox.ui.FocusCard
import com.mypro.iptvbox.ui.FocusableRow

@Composable
private fun GroupChips(groups: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(groups) { g ->
            FocusableRow(onClick = { onSelect(g) }, selected = g == selected, modifier = Modifier.width(IntrinsicSize.Max), padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Text(g, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true, placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear") } },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Poster(url: String, modifier: Modifier = Modifier) {
    if (url.isNotBlank()) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.06f)))
    } else {
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Movie, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
fun MoviesScreen() {
    val context = LocalContext.current
    val content by IptvApp.repo.content.collectAsState()
    val movies = content?.movies ?: emptyList()
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("All") }
    val groups = remember(movies) { listOf("All") + movies.map { it.group.ifBlank { "Other" } }.distinct() }
    val shown = remember(movies, group, query) {
        val base = if (group == "All") movies else movies.filter { it.group.ifBlank { "Other" } == group }
        if (query.isBlank()) base else { val q = TextUtil.normalize(query); base.filter { TextUtil.normalize(it.name).contains(q) } }
    }
    if (movies.isEmpty()) { EmptyState("No movies in this playlist.\nXtream Codes logins expose the provider's VOD library here."); return }
    Column(Modifier.fillMaxSize()) {
        SearchBox(query, { query = it }, "Search ${movies.size} movies")
        GroupChips(groups, group) { group = it }
        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { m ->
                FocusCard(onClick = { PlayerActivity.playUrl(context, m.url, m.name) }) {
                    Poster(m.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    Spacer(Modifier.height(6.dp))
                    Text(m.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    val meta = listOf(m.year, m.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString("  ")
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun SeriesScreen() {
    val content by IptvApp.repo.content.collectAsState()
    val series = content?.series ?: emptyList()
    var selected by remember { mutableStateOf<SeriesItem?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("All") }
    val groups = remember(series) { listOf("All") + series.map { it.group.ifBlank { "Other" } }.distinct() }
    val shown = remember(series, group, query) {
        val base = if (group == "All") series else series.filter { it.group.ifBlank { "Other" } == group }
        if (query.isBlank()) base else { val q = TextUtil.normalize(query); base.filter { TextUtil.normalize(it.name).contains(q) } }
    }
    selected?.let { s ->
        SeriesDetail(s) { selected = null }
        return
    }
    if (series.isEmpty()) { EmptyState("No series in this playlist.\nSeries with seasons and episodes come from Xtream Codes logins."); return }
    Column(Modifier.fillMaxSize()) {
        SearchBox(query, { query = it }, "Search ${series.size} series")
        GroupChips(groups, group) { group = it }
        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { s ->
                FocusCard(onClick = { selected = s }) {
                    Poster(s.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    Spacer(Modifier.height(6.dp))
                    Text(s.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    if (s.year.isNotBlank()) Text(s.year, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SeriesDetail(series: SeriesItem, onBack: () -> Unit) {
    val context = LocalContext.current
    var episodes by remember(series.id) { mutableStateOf<List<Episode>?>(null) }
    var error by remember(series.id) { mutableStateOf<String?>(null) }
    var season by remember(series.id) { mutableIntStateOf(-1) }
    BackHandler(onBack = onBack)
    LaunchedEffect(series.id) {
        runCatching { IptvApp.repo.episodes(series) }
            .onSuccess { episodes = it; season = it.firstOrNull()?.season ?: -1 }
            .onFailure { error = it.message ?: "Failed to load episodes" }
    }
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Back") }
            Poster(series.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Text(series.name, style = MaterialTheme.typography.titleMedium)
            val meta = listOf(series.year, series.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }, series.group).filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (series.plot.isNotBlank()) Text(series.plot, style = MaterialTheme.typography.bodySmall, maxLines = 10, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f)) {
            val eps = episodes
            when {
                error != null -> EmptyState("Could not load episodes: $error")
                eps == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                eps.isEmpty() -> EmptyState("No episodes listed for this series.")
                else -> {
                    val seasons = eps.map { it.season }.distinct().sorted()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        items(seasons) { s ->
                            FocusableRow(onClick = { season = s }, selected = s == season, modifier = Modifier.width(IntrinsicSize.Max), padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                                Text("Season $s", maxLines = 1)
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(eps.filter { it.season == season }, key = { it.id }) { e ->
                            FocusableRow(onClick = { PlayerActivity.playUrl(context, e.url, "${series.name} S${e.season}E${e.episode} ${e.title}".trim()) }) {
                                if (e.poster.isNotBlank()) { AsyncImage(model = e.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp))); Spacer(Modifier.width(12.dp)) }
                                Text("E${e.episode}", Modifier.width(44.dp), color = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(e.title.ifBlank { "Episode ${e.episode}" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (e.plot.isNotBlank()) Text(e.plot, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (e.duration.isNotBlank()) Text(e.duration, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
