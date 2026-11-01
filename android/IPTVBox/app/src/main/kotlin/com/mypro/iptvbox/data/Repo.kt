package com.mypro.iptvbox.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mypro.iptvbox.core.epg.EpgIndex
import com.mypro.iptvbox.core.epg.XmltvParser
import com.mypro.iptvbox.core.m3u.M3uParser
import com.mypro.iptvbox.core.model.Channel
import com.mypro.iptvbox.core.model.Episode
import com.mypro.iptvbox.core.model.Playlist
import com.mypro.iptvbox.core.model.PlaylistContent
import com.mypro.iptvbox.core.model.Programme
import com.mypro.iptvbox.core.model.SeriesItem
import com.mypro.iptvbox.core.model.SourceType
import com.mypro.iptvbox.core.net.Http
import com.mypro.iptvbox.core.stalker.StalkerClient
import com.mypro.iptvbox.core.xtream.XtreamApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class LoadState(
    val loading: Boolean = false,
    val message: String = "",
    val error: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
private data class EpgCache(val url: String, val savedAt: Long, val programmes: List<Programme>, val names: Map<String, List<String>>)

/**
 * Single source of truth for playlists, loaded content, EPG, favourites and recents.
 * All persistence is plain JSON in the app's private files directory.
 */
class Repo(private val context: Context, private val settings: Settings) {
    private val tag = "Repo"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File = File(context.filesDir, "data").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("iptvbox_lists", Context.MODE_PRIVATE)

    private val _playlists = MutableStateFlow(loadPlaylists())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _content = MutableStateFlow<PlaylistContent?>(null)
    val content: StateFlow<PlaylistContent?> = _content.asStateFlow()

    private val _epg = MutableStateFlow(EpgIndex.EMPTY)
    val epg: StateFlow<EpgIndex> = _epg.asStateFlow()

    private val _loadState = MutableStateFlow(LoadState())
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val _favorites = MutableStateFlow(loadStringList("favorites").toSet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _recents = MutableStateFlow(loadStringList("recents"))
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    private val stalkerClients = ConcurrentHashMap<String, StalkerClient>()
    private var loadJob: Job? = null

    val activePlaylist: Playlist? get() = _playlists.value.firstOrNull { it.id == settings.current.activePlaylistId }

    init {
        // Restore cached content for the active playlist immediately, refresh in background.
        activePlaylist?.let { pl ->
            scope.launch {
                readCache(pl.id)?.let { _content.value = it }
                readEpgCache(pl.id)?.let { _epg.value = it }
                if (_content.value == null || System.currentTimeMillis() - (_content.value?.loadedAt ?: 0) > 6 * 3600_000L) {
                    refreshActive()
                }
            }
        }
    }

    // ---------------------------------------------------------------- playlists

    fun savePlaylist(p: Playlist) {
        val list = _playlists.value.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        _playlists.value = list
        persistPlaylists()
        if (settings.current.activePlaylistId.isEmpty() || list.size == 1) setActive(p.id)
        else if (p.id == settings.current.activePlaylistId) refreshActive(force = true)
    }

    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filterNot { it.id == id }
        persistPlaylists()
        File(dir, "content_$id.json").delete()
        File(dir, "epg_$id.json").delete()
        stalkerClients.remove(id)
        if (settings.current.activePlaylistId == id) {
            _content.value = null
            _epg.value = EpgIndex.EMPTY
            _playlists.value.firstOrNull()?.let { setActive(it.id) } ?: settings.update { it.copy(activePlaylistId = "") }
        }
    }

    fun setActive(id: String) {
        if (settings.current.activePlaylistId == id && _content.value?.playlistId == id) return
        settings.update { it.copy(activePlaylistId = id) }
        _content.value = null
        _epg.value = EpgIndex.EMPTY
        scope.launch {
            readCache(id)?.let { _content.value = it }
            readEpgCache(id)?.let { _epg.value = it }
            if (_content.value == null) refreshActive() else if (_epg.value.isEmpty) refreshEpg()
        }
    }

    fun refreshActive(force: Boolean = false) {
        val pl = activePlaylist ?: return
        if (_loadState.value.loading && !force) return
        loadJob?.cancel()
        loadJob = scope.launch {
            _loadState.value = LoadState(loading = true, message = "Loading ${pl.name}…")
            try {
                val (content, warnings) = loadContent(pl)
                _content.value = content
                writeCache(content)
                _loadState.value = LoadState(loading = true, message = "Loading programme guide…", warnings = warnings)
                loadEpg(pl, content, force = true)
                _loadState.value = LoadState(loading = false, message = "", warnings = warnings)
            } catch (e: Exception) {
                Log.w(tag, "load failed", e)
                _loadState.value = LoadState(loading = false, error = e.message ?: e.toString())
            }
        }
    }

    fun refreshEpg() {
        val pl = activePlaylist ?: return
        val c = _content.value ?: return
        scope.launch { runCatching { loadEpg(pl, c, force = true) } }
    }

    /** Validates a playlist by loading it (used by the "Test" button). Returns a human summary. */
    suspend fun test(p: Playlist): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (c, warnings) = loadContent(p)
            buildString {
                append("${c.channels.size} live channels")
                if (c.movies.isNotEmpty()) append(", ${c.movies.size} movies")
                if (c.series.isNotEmpty()) append(", ${c.series.size} series")
                if (c.epgUrl.isNotBlank()) append("\nEPG: ${c.epgUrl.take(80)}")
                if (warnings.isNotEmpty()) append("\n").append(warnings.take(3).joinToString("\n"))
            }
        }
    }

    private suspend fun loadContent(pl: Playlist): Pair<PlaylistContent, List<String>> = withContext(Dispatchers.IO) {
        val ua = pl.userAgent.ifBlank { settings.current.userAgent }
        when (pl.type) {
            SourceType.M3U_URL -> {
                // An Xtream "get.php" link works better through the API (categories, EPG, VOD).
                XtreamApi.fromGetPhpUrl(pl.url)?.let { (base, u, p) ->
                    val api = XtreamApi(pl.copy(url = base, username = u, password = p, userAgent = ua))
                    return@withContext runCatching { api.loadAll(liveExt = settings.current.liveFormat.ext) to emptyList<String>() }
                        .getOrElse { parseM3uUrl(pl, ua) }
                }
                parseM3uUrl(pl, ua)
            }
            SourceType.M3U_FILE -> {
                val uri = Uri.parse(pl.url)
                val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open file")
                val r = stream.use { M3uParser.parse(Http.maybeGunzip(it, pl.url), pl.id) }
                r.content.copy(epgUrl = pl.epgUrl.ifBlank { r.content.epgUrl }) to r.warnings
            }
            SourceType.XTREAM -> XtreamApi(pl.copy(userAgent = ua)).loadAll(liveExt = settings.current.liveFormat.ext) to emptyList()
            SourceType.STALKER -> {
                val client = StalkerClient(pl)
                stalkerClients[pl.id] = client
                client.loadAll().copy(epgUrl = pl.epgUrl) to listOf("Stalker portal support is experimental")
            }
        }
    }

    private fun parseM3uUrl(pl: Playlist, ua: String): Pair<PlaylistContent, List<String>> {
        val r = Http.openStream(pl.url, ua).use { M3uParser.parse(it, pl.id) }
        return r.content.copy(epgUrl = pl.epgUrl.ifBlank { r.content.epgUrl }) to r.warnings
    }

    private suspend fun loadEpg(pl: Playlist, content: PlaylistContent, force: Boolean) = withContext(Dispatchers.IO) {
        val url = pl.epgUrl.ifBlank { content.epgUrl }
        if (url.isBlank()) { _epg.value = EpgIndex.EMPTY; return@withContext }
        val cacheFile = File(dir, "epg_${pl.id}.json")
        if (!force && cacheFile.exists()) return@withContext
        try {
            val result = Http.openStream(url, pl.userAgent.ifBlank { settings.current.userAgent }).use { XmltvParser.parse(it) }
            _epg.value = EpgIndex(result.programmes, result.channelNames)
            runCatching {
                cacheFile.writeText(json.encodeToString(EpgCache(url, System.currentTimeMillis(), result.programmes, result.channelNames)))
            }
        } catch (e: Exception) {
            Log.w(tag, "EPG load failed: ${e.message}")
            _loadState.value = _loadState.value.copy(warnings = _loadState.value.warnings + "EPG failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- playback helpers

    /** Final URL to hand to the player (Stalker links are created on demand). */
    suspend fun resolveUrl(channel: Channel): String = withContext(Dispatchers.IO) {
        if (channel.extension == "stalker") {
            val pl = _playlists.value.firstOrNull { it.id == channel.playlistId } ?: return@withContext channel.url
            val client = stalkerClients.getOrPut(pl.id) { StalkerClient(pl) }
            client.resolveLink(channel.url)
        } else channel.url
    }

    suspend fun episodes(series: SeriesItem): List<Episode> = withContext(Dispatchers.IO) {
        val pl = _playlists.value.firstOrNull { it.id == series.playlistId } ?: return@withContext emptyList()
        XtreamApi(pl).episodes(series)
    }

    /** Short EPG from an Xtream panel for channels with no XMLTV match. */
    suspend fun shortEpg(channel: Channel): List<Programme> = withContext(Dispatchers.IO) {
        val pl = _playlists.value.firstOrNull { it.id == channel.playlistId } ?: return@withContext emptyList()
        if (pl.type != SourceType.XTREAM && XtreamApi.fromGetPhpUrl(pl.url) == null) return@withContext emptyList()
        val api = XtreamApi.fromGetPhpUrl(pl.url)?.let { (b, u, p) -> XtreamApi(pl.copy(url = b, username = u, password = p)) } ?: XtreamApi(pl)
        runCatching { api.shortEpg(channel) }.getOrDefault(emptyList())
    }

    fun catchupUrl(channel: Channel, programme: Programme): String? {
        if (channel.catchupDays <= 0) return null
        val pl = _playlists.value.firstOrNull { it.id == channel.playlistId } ?: return null
        if (channel.catchupSource.isNotBlank()) {
            // Generic catchup-source template: ${start}, ${end}, ${duration} (seconds) / {utc}, {lutc}
            val start = programme.start / 1000; val end = programme.stop / 1000
            return channel.catchupSource
                .replace("\${start}", start.toString()).replace("{utc}", start.toString())
                .replace("\${end}", end.toString()).replace("{lutc}", (System.currentTimeMillis() / 1000).toString())
                .replace("\${duration}", (end - start).toString()).replace("{duration}", (end - start).toString())
                .replace("\${timestamp}", start.toString())
        }
        val creds = when (pl.type) {
            SourceType.XTREAM -> Triple(XtreamApi.normalizeBase(pl.url), pl.username, pl.password)
            SourceType.M3U_URL -> XtreamApi.fromGetPhpUrl(pl.url)
            else -> null
        } ?: return null
        val api = XtreamApi(pl.copy(url = creds.first, username = creds.second, password = creds.third))
        val offset = _content.value?.serverTimeOffsetMs ?: 0L
        return api.catchupUrl(channel, programme.start, programme.durationMs, offset)
    }

    fun channelById(id: String): Channel? = _content.value?.channels?.firstOrNull { it.id == id }

    fun toggleFavorite(id: String) {
        val s = _favorites.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        _favorites.value = s
        saveStringList("favorites", s.toList())
    }

    fun addRecent(id: String) {
        val l = _recents.value.toMutableList()
        l.remove(id); l.add(0, id)
        while (l.size > 30) l.removeAt(l.size - 1)
        _recents.value = l
        saveStringList("recents", l)
        settings.update { it.copy(lastChannelId = id) }
    }

    // ---------------------------------------------------------------- persistence

    private fun loadPlaylists(): List<Playlist> = runCatching {
        val f = File(dir, "playlists.json")
        if (f.exists()) json.decodeFromString(ListSerializer(Playlist.serializer()), f.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun persistPlaylists() = runCatching {
        File(dir, "playlists.json").writeText(json.encodeToString(ListSerializer(Playlist.serializer()), _playlists.value))
    }

    private fun readCache(id: String): PlaylistContent? = runCatching {
        val f = File(dir, "content_$id.json")
        if (f.exists()) json.decodeFromString(PlaylistContent.serializer(), f.readText()) else null
    }.getOrNull()

    private fun writeCache(c: PlaylistContent) = runCatching {
        File(dir, "content_${c.playlistId}.json").writeText(json.encodeToString(PlaylistContent.serializer(), c))
    }

    private fun readEpgCache(id: String): EpgIndex? = runCatching {
        val f = File(dir, "epg_$id.json")
        if (!f.exists()) return null
        val c = json.decodeFromString(EpgCache.serializer(), f.readText())
        if (System.currentTimeMillis() - c.savedAt > settings.current.epgRefreshHours * 3600_000L) {
            scope.launch { activePlaylist?.let { pl -> _content.value?.let { loadEpg(pl, it, force = true) } } }
        }
        EpgIndex(c.programmes, c.names)
    }.getOrNull()

    private fun loadStringList(key: String): List<String> = runCatching {
        prefs.getString(key, null)?.let { json.decodeFromString(ListSerializer(String.serializer()), it) } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun saveStringList(key: String, list: List<String>) {
        prefs.edit().putString(key, json.encodeToString(ListSerializer(String.serializer()), list)).apply()
    }
}
