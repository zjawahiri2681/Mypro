package com.mypro.iptvbox.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64

/**
 * A shareable invitation: iptvbox://join?d=<base64url(json)>.
 * Everything needed to join is in the link; the room key never touches the relay server unencrypted.
 */
@Serializable
data class WatchLink(
    val room: String,
    /** base64url 16-byte AES key. */
    val key: String,
    val mode: Mode,
    /** LAN mode: host address and port. */
    val host: String = "",
    val port: Int = 0,
    /** RELAY mode: MQTT broker URI, e.g. ssl://broker.hivemq.com:8883 */
    val broker: String = "",
    val media: MediaRef,
    val hostName: String = "",
    val v: Int = 1,
) {
    @Serializable
    enum class Mode { LAN, RELAY }

    fun toUri(): String = "$SCHEME://$HOST_JOIN?d=" + b64url(json.encodeToString(this).toByteArray(Charsets.UTF_8))

    /** Short human-typeable code for the same room, for when links cannot be pasted (TV remotes). */
    fun shortCode(): String = room.uppercase()

    companion object {
        const val SCHEME = "iptvbox"
        const val HOST_JOIN = "join"
        const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"
        const val DEFAULT_LAN_PORT = 48155

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val rnd = SecureRandom()

        fun newRoomId(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val sb = StringBuilder(8)
            repeat(8) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            return sb.toString()
        }

        fun newKey(): String = ByteArray(16).also { rnd.nextBytes(it) }.let { b64url(it) }

        fun parse(text: String): WatchLink? {
            val t = text.trim()
            val idx = t.indexOf("d=")
            if (!t.startsWith("$SCHEME://") || idx < 0) return null
            val data = t.substring(idx + 2).substringBefore('&').substringBefore('#')
            return try {
                json.decodeFromString<WatchLink>(String(unb64url(data), Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }

        fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        fun unb64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim())
    }
}
