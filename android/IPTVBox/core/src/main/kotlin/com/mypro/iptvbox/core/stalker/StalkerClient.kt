package com.mypro.iptvbox.core.stalker

import com.mypro.iptvbox.core.model.Channel
import com.mypro.iptvbox.core.model.ContentType
import com.mypro.iptvbox.core.model.Playlist
import com.mypro.iptvbox.core.model.PlaylistContent
import com.mypro.iptvbox.core.net.Http
import com.mypro.iptvbox.core.util.Ids
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URLEncoder

/**
 * Minimal Stalker / Ministra ("MAG portal") client: handshake, profile, genres,
 * channel list and link creation. Experimental - portals differ a lot.
 */
class StalkerClient(val playlist: Playlist) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mac = playlist.macAddress.trim().uppercase()
    private var token: String = ""
    private var endpoint: String = ""
    private val referer: String

    init {
        val raw = playlist.url.trim().let { if (it.startsWith("http")) it else "http://$it" }.trimEnd('/')
        referer = if (raw.endsWith("/c")) "$raw/" else "$raw/c/"
    }

    private val userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    private fun headers(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Cookie"] = "mac=${enc(mac)}; stb_lang=en; timezone=${enc("Europe/London")}"
        h["X-User-Agent"] = "Model: MAG250; Link: WiFi"
        h["Referer"] = referer
        h["Accept"] = "*/*"
        if (token.isNotEmpty()) h["Authorization"] = "Bearer $token"
        return h
    }

    private fun candidateEndpoints(): List<String> {
        val raw = playlist.url.trim().let { if (it.startsWith("http")) it else "http://$it" }.trimEnd('/')
        val host = Regex("""^(https?://[^/?#]+)""").find(raw)?.groupValues?.get(1) ?: raw
        return listOf(
            "$raw/server/load.php",
            "$raw/portal.php",
            "$host/stalker_portal/server/load.php",
            "$host/portal.php",
            "$host/c/portal.php",
        ).distinct()
    }

    private fun call(query: String): JsonObject {
        val endpoints = if (endpoint.isNotEmpty()) listOf(endpoint) else candidateEndpoints()
        var last: Exception? = null
        for (ep in endpoints) {
            try {
                val body = Http.getString("$ep?$query&JsHttpRequest=1-xml", userAgent, headers())
                val el = json.parseToJsonElement(body)
                if (el is JsonObject && el.containsKey("js")) {
                    endpoint = ep
                    return el
                }
                last = IOException("Unexpected reply from $ep")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Portal not reachable")
    }

    fun handshake() {
        val r = call("type=stb&action=handshake&token=&prehash=0")
        val js = r["js"] as? JsonObject ?: throw IOException("Handshake failed")
        token = js.str("token")
        if (token.isEmpty()) throw IOException("Portal did not return a token")
        // get_profile registers the session; some portals require it before listing channels
        runCatching {
            call(
                "type=stb&action=get_profile&hd=1&ver=ImageDescription:%200.2.18-r14-pub-250;%20ImageDate:%20Fri%20Jan%2015%2015:20:44%20EET%202016;%20PORTAL%20version:%205.6.1;%20API%20Version:%20JS%20API%20version:%20328;%20STB%20API%20version:%20134;%20Player%20Engine%20version:%200x566" +
                    "&num_banks=2&sn=&stb_type=MAG250&client_type=STB&image_version=218&video_out=hdmi&device_id=&device_id2=&signature=&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0&metrics=&hw_version_2=&timestamp=${System.currentTimeMillis() / 1000}&api_signature=263&prehash=",
            )
        }
    }

    fun genres(): Map<String, String> {
        val r = call("type=itv&action=get_genres")
        val arr = r["js"] as? JsonArray ?: return emptyMap()
        return arr.filterIsInstance<JsonObject>().associate { it.str("id") to it.str("title") }
    }

    fun channels(): List<Channel> {
        val genres = runCatching { genres() }.getOrDefault(emptyMap())
        val r = call("type=itv&action=get_all_channels")
        val js = r["js"] as? JsonObject ?: return emptyList()
        val data = js["data"] as? JsonArray ?: return emptyList()
        val out = ArrayList<Channel>(data.size)
        var n = 0
        for (e in data) {
            val o = e as? JsonObject ?: continue
            n++
            val id = o.str("id")
            val cmd = o.str("cmd")
            val logo = o.str("logo").let { if (it.isBlank() || it.startsWith("http")) it else logoBase() + it }
            out += Channel(
                id = Ids.stable(playlist.id, "stalker", id),
                playlistId = playlist.id,
                name = o.str("name"),
                url = cmd, // resolved lazily through resolveLink()
                group = genres[o.str("tv_genre_id")] ?: "All",
                logo = logo,
                tvgId = o.str("xmltv_id"),
                number = o.str("number").toIntOrNull() ?: n,
                type = ContentType.LIVE,
                catchupDays = if (o.str("tv_archive") == "1") 1 else 0,
                providerId = id,
                extension = "stalker",
            )
        }
        return out
    }

    /** Converts the stored "cmd" into a playable URL (portals hand out temporary links). */
    fun resolveLink(cmd: String): String {
        if (token.isEmpty()) handshake()
        val r = call("type=itv&action=create_link&cmd=${enc(cmd)}&series=&forced_storage=undefined&disable_ad=0&download=0&force_ch_link_check=0")
        val js = r["js"] as? JsonObject ?: throw IOException("create_link failed")
        val link = js.str("cmd").ifBlank { cmd }
        return stripPrefix(link)
    }

    fun loadAll(): PlaylistContent {
        handshake()
        return PlaylistContent(playlistId = playlist.id, channels = channels(), loadedAt = System.currentTimeMillis())
    }

    private fun logoBase(): String {
        val host = Regex("""^(https?://[^/?#]+)""").find(endpoint)?.groupValues?.get(1) ?: ""
        return if (endpoint.contains("stalker_portal")) "$host/stalker_portal/misc/logos/320/" else "$host/misc/logos/320/"
    }

    companion object {
        fun stripPrefix(link: String): String {
            var s = link.trim()
            for (p in listOf("ffrt ", "ffmpeg ", "auto ", "ffrt2 ", "ffrt3 ")) if (s.startsWith(p)) s = s.removePrefix(p).trim()
            return s.substringBefore(' ')
        }
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
    }
}
