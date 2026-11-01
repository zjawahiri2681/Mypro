package com.mypro.iptvbox.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One message on the watch-together channel. Hosts send STATE; everyone may send CMD. */
@Serializable
data class SyncMessage(
    val type: Type,
    /** Sender's random participant id. */
    val from: String,
    /** Sender wall-clock time in ms when the message was created. */
    val ts: Long,
    val name: String = "",
    val playing: Boolean = false,
    val posMs: Long = 0L,
    val rate: Float = 1f,
    /** Identifies what is playing so late joiners load the right media. */
    val mediaId: String = "",
    val cmd: Cmd? = null,
    /** Ping/pong: originator's send time and responder's receive time. */
    val t0: Long = 0L,
    val t1: Long = 0L,
    val text: String = "",
    val isHost: Boolean = false,
) {
    @Serializable
    enum class Type { HELLO, STATE, CMD, PING, PONG, CHAT, BYE }

    @Serializable
    enum class Cmd { PLAY, PAUSE, SEEK, LOAD }

    fun encode(): String = json.encodeToString(this)

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(s: String): SyncMessage = json.decodeFromString(s)
    }
}

/** What a watch-together room is playing. */
@Serializable
data class MediaRef(
    val kind: Kind,
    /** Stream/file URL for URL kind, "" for local files. */
    val url: String = "",
    /** Display name, or file name for local files. */
    val name: String = "",
    /** File size in bytes for local files (helps the joiner pick the same file). */
    val size: Long = 0L,
    /** Extra HTTP headers as key=value lines. */
    val headers: String = "",
) {
    @Serializable
    enum class Kind { URL, FILE }

    val mediaId: String get() = if (kind == Kind.URL) url else "$name|$size"
}
