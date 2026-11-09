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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import com.mypro.iptvbox.core.epg.Catchup
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
    private var epgJob: Job? = null
    /** Only one XMLTV download/parse at a time: two in parallel can exhaust memory on TV boxes. */
    private val epgMutex = Mutex()

    val activePlaylist: Playlist? get() = _playlists.value.firstOrNull { it.id == settings.current.activePlaylistId }

    init {
        // Restore cached content for the active playlist immediately, refresh in background.
        activePlaylist?.let { pl ->
            scope.launch {
                readCache(pl.id)?.let { _content.value = it }
                readEpgCache(pl.id)?.let { _epg.value = it }
                if (_content.value == null || System.currentTimeMillis() - (_content.value?.loadedAt ?: 0) > 6 * 3600_000L) {
                    refreshActive(force = true)
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
        if (isActivePlaylist(id)) { loadJob?.cancel(); epgJob?.cancel(); _loadState.value = LoadState() }
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
        // Stop whatever the previous playlist was loading so it cannot overwrite this one.
        loadJob?.cancel(); epgJob?.cancel()
        _loadState.value = LoadState()
        settings.update { it.copy(activePlaylistId = id) }
        _content.value = null
        _epg.value = EpgIndex.EMPTY
        scope.launch {
            readCache(id)?.let { if (isActivePlaylist(id)) _content.value = it }
            readEpgCache(id)?.let { if (isActivePlaylist(id)) _epg.value = it }
            if (!isActivePlaylist(id)) return@launch
            if (_content.value == null) refreshActive(force = true) else if (_epg.value.isEmpty) refreshEpg()
        }
    }

    private fun isActivePlaylist(playlistId: String) = settings.current.activePlaylistId == playlistId

    /** Suspends until the active playlist's channels are available (or the timeout passes). */
    suspend fun awaitContent(timeoutMs: Long = 15_000): PlaylistContent? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { content.first { it != null } }

    fun refreshActive(force: Boolean = false) {
        val pl = activePlaylist ?: return
        if (_loadState.value.loading && !force) return
        loadJob?.cancel()
        epgJob?.cancel()
        loadJob = scope.launch {
            _loadState.value = LoadState(loading = true, message = "Loading ${pl.name}…")
            try {
                val (content, warnings) = loadContent(pl)
                if (!isActivePlaylist(pl.id)) return@launch
                _content.value = content
                writeCache(content)
                _loadState.value = LoadState(loading = false, message = "", warnings = warnings)
                // The guide loads separately so channels are usable straight away.
                epgJob = scope.launch { loadEpg(pl, content) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                Log.w(tag, "load ran out of memory", e)
                if (isActivePlaylist(pl.id)) _loadState.value = LoadState(loading = false, error = "Playlist too large for this device's memory")
            } catch (e: Exception) {
                Log.w(tag, "load failed: ${e.javaClass.simpleName}")
                if (isActivePlaylist(pl.id)) _loadState.value = LoadState(loading = false, error = Http.redact(e.message ?: e.toString()))
            }
        }
    }

    fun refreshEpg() {
        val pl = activePlaylist ?: return
        val c = _content.value ?: return
        epgJob?.cancel()
        epgJob = scope.launch { loadEpg(pl, c) }
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

    /**
     * Downloads and parses the XMLTV guide(s). Keeps only channels that exist in the playlist
     * and a window of 1 day back / 3 days ahead, so big guides fit in a TV box's memory.
     * Several guide URLs may be given, comma-separated (url-tvg="a.xml.gz,b.xml.gz").
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun loadEpg(pl: Playlist, content: PlaylistContent) = epgMutex.withLock {
        withContext(Dispatchers.IO) {
            val raw = pl.epgUrl.ifBlank { content.epgUrl }
            val urls = raw.split(',').map { it.trim() }.filter { it.startsWith("http") }.distinct()
            if (urls.isEmpty()) { if (isActivePlaylist(pl.id)) _epg.value = EpgIndex.EMPTY; return@withContext }
            val ua = pl.userAgent.ifBlank { settings.current.userAgent }
            val wanted = HashSet<String>().apply {
                for (c in content.channels) {
                    if (c.tvgId.isNotBlank()) add(c.tvgId)
                    add(com.mypro.iptvbox.core.util.TextUtil.normalize(c.tvgName.ifBlank { c.name }))
                    add(com.mypro.iptvbox.core.util.TextUtil.channelKey(c.name))
                }
            }
            val now = System.currentTimeMillis()
            val programmes = ArrayList<Programme>()
            val names = HashMap<String, List<String>>()
            if (isActivePlaylist(pl.id)) _loadState.value = _loadState.value.copy(message = "Loading programme guide…")
            for (url in urls) {
                try {
                    val r = Http.openStream(url, ua).use { XmltvParser.parse(it, now - 24 * 3600_000L, now + 3 * 24 * 3600_000L) }
                    // keep a guide channel if its id or any display name matches a playlist channel
                    val keep = HashSet<String>()
                    for ((id, ns) in r.channelNames) {
                        if (id in wanted || ns.any { n -> com.mypro.iptvbox.core.util.TextUtil.normalize(n) in wanted || com.mypro.iptvbox.core.util.TextUtil.channelKey(n) in wanted }) keep += id
                    }
                    r.programmes.filterTo(programmes) { it.channelId in keep || it.channelId in wanted }
                    for (id in keep) r.channelNames[id]?.let { names[id] = it }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(tag, "EPG load failed: ${e.javaClass.simpleName}")
                    if (isActivePlaylist(pl.id)) _loadState.value = _loadState.value.copy(warnings = _loadState.value.warnings + "Guide failed: ${Http.redact(e.message ?: e.toString())}")
                }
            }
            if (!isActivePlaylist(pl.id)) return@withContext
            programmes.sortWith(compareBy({ it.channelId }, { it.start }))
            _epg.value = EpgIndex(programmes, names)
            runCatching {
                File(dir, "epg_${pl.id}.json").outputStream().buffered().use {
                    json.encodeToStream(EpgCache.serializer(), EpgCache(raw, System.currentTimeMillis(), programmes, names), it)
                }
            }
            if (isActivePlaylist(pl.id)) _loadState.value = _loadState.value.copy(message = "")
            Unit
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
        xtreamFor(pl)?.episodes(series) ?: emptyList()
    }

    /** Short EPG from an Xtream panel for channels with no XMLTV match. */
    suspend fun shortEpg(channel: Channel): List<Programme> = withContext(Dispatchers.IO) {
        val pl = _playlists.value.firstOrNull { it.id == channel.playlistId } ?: return@withContext emptyList()
        val api = xtreamFor(pl) ?: return@withContext emptyList()
        runCatching { api.shortEpg(channel) }.getOrDefault(emptyList())
    }

    /** Xtream API client for an Xtream login or an Xtream-style get.php M3U link (null otherwise). */
    private fun xtreamFor(pl: Playlist): XtreamApi? {
        val ua = pl.userAgent.ifBlank { settings.current.userAgent }
        return when (pl.type) {
            SourceType.XTREAM -> XtreamApi(pl.copy(userAgent = ua))
            SourceType.M3U_URL -> XtreamApi.fromGetPhpUrl(pl.url)?.let { (b, u, p) -> XtreamApi(pl.copy(url = b, username = u, password = p, userAgent = ua)) }
            else -> null
        }
    }

    /** User-Agent to use for streams of this playlist. */
    fun userAgentFor(playlistId: String): String =
        _playlists.value.firstOrNull { it.id == playlistId }?.userAgent?.ifBlank { null } ?: settings.current.userAgent

    fun catchupUrl(channel: Channel, programme: Programme): String? {
        if (channel.catchupDays <= 0) return null
        val pl = _playlists.value.firstOrNull { it.id == channel.playlistId } ?: return null
        // M3U conventions first (catchup-source / catchup type), then the Xtream timeshift API.
        if (channel.catchupSource.isNotBlank() || channel.catchupType in setOf("shift", "timeshift", "flussonic", "flussonic-hls", "flussonic-ts", "fs", "append")) {
            Catchup.buildUrl(channel, programme.start, programme.stop)?.let { return it }
        }
        val api = xtreamFor(pl) ?: return null
        if (channel.providerId.isBlank()) return null
        return api.catchupUrl(channel, programme.start, programme.durationMs, _content.value?.serverTimezone ?: "")
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

    @OptIn(ExperimentalSerializationApi::class)
    private fun readCache(id: String): PlaylistContent? = try {
        val f = File(dir, "content_$id.json")
        if (f.exists()) f.inputStream().buffered().use { json.decodeFromStream(PlaylistContent.serializer(), it) } else null
    } catch (e: Throwable) { null }

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeCache(c: PlaylistContent) = try {
        val tmp = File(dir, "content_${c.playlistId}.json.tmp")
        tmp.outputStream().buffered().use { json.encodeToStream(PlaylistContent.serializer(), c, it) }
        tmp.renameTo(File(dir, "content_${c.playlistId}.json"))
    } catch (e: Throwable) { false }

    @OptIn(ExperimentalSerializationApi::class)
    private fun readEpgCache(id: String): EpgIndex? = try {
        val f = File(dir, "epg_$id.json")
        if (!f.exists()) null else {
            val c = f.inputStream().buffered().use { json.decodeFromStream(EpgCache.serializer(), it) }
            if (System.currentTimeMillis() - c.savedAt > settings.current.epgRefreshHours * 3600_000L) {
                // stale: refresh after the playlist itself is available, via the single EPG job
                scope.launch { awaitContent()?.let { refreshEpg() } }
            }
            EpgIndex(c.programmes, c.names)
        }
    } catch (e: Throwable) { null }

    private fun loadStringList(key: String): List<String> = runCatching {
        prefs.getString(key, null)?.let { json.decodeFromString(ListSerializer(String.serializer()), it) } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun saveStringList(key: String, list: List<String>) {
        prefs.edit().putString(key, json.encodeToString(ListSerializer(String.serializer()), list)).apply()
    }
}
