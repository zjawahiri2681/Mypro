package com.mypro.familytalk.core.protocol

import com.mypro.familytalk.core.crypto.PublicIdentity
import com.mypro.familytalk.core.family.SignedRoster
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything that travels inside a sealed envelope. */
@Serializable
sealed interface Payload

// ---- calls -------------------------------------------------------------------------
@Serializable @SerialName("invite")
data class CallInvite(
    val callId: String,
    val video: Boolean,
    /** Everyone in the call, including the caller. Size 2 for a normal call. */
    val participants: List<String>,
) : Payload

@Serializable @SerialName("ringing") data class CallRinging(val callId: String) : Payload
@Serializable @SerialName("accept") data class CallAccept(val callId: String) : Payload
@Serializable @SerialName("decline") data class CallDecline(val callId: String, val reason: String = "declined") : Payload
@Serializable @SerialName("hangup") data class CallHangup(val callId: String, val reason: String = "hangup") : Payload
/** Conference roster changed (someone joined / left). */
@Serializable @SerialName("members") data class CallMembers(val callId: String, val participants: List<String>) : Payload

/** WebRTC session description. The DTLS fingerprint inside is what binds media to identity. */
@Serializable @SerialName("sdp")
data class Sdp(val callId: String, val type: String, val sdp: String) : Payload

@Serializable @SerialName("ice")
data class Ice(val callId: String, val mid: String?, val index: Int, val candidate: String) : Payload

@Serializable @SerialName("media") data class MediaState(val callId: String, val mic: Boolean, val camera: Boolean) : Payload

// ---- chat & files ------------------------------------------------------------------
@Serializable @SerialName("text")
data class ChatText(val msgId: String, val text: String, val sentAt: Long, val group: Boolean = false, val replyTo: String? = null) : Payload

@Serializable @SerialName("receipt")
data class ChatReceipt(val msgIds: List<String>, val read: Boolean) : Payload

/** Small files (<= [com.mypro.familytalk.core.files.FileTransfer.INLINE_LIMIT]) travel inline in chunks via the relay. */
@Serializable @SerialName("file")
data class FileOffer(val fileId: String, val name: String, val mime: String, val size: Long, val sha256: String, val chunks: Int, val sentAt: Long, val group: Boolean = false) : Payload

@Serializable @SerialName("chunk")
data class FileChunk(val fileId: String, val index: Int, val data: String) : Payload

// ---- family ------------------------------------------------------------------------
@Serializable @SerialName("roster") data class RosterUpdate(val roster: SignedRoster) : Payload

@Serializable @SerialName("join")
data class JoinRequest(val name: String, val identity: PublicIdentity, val inviteId: String, val proof: String) : Payload

@Serializable @SerialName("ping") data class Ping(val at: Long) : Payload
@Serializable @SerialName("pong") data class Pong(val at: Long) : Payload

object PayloadCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "t" }
    fun encode(p: Payload): ByteArray = json.encodeToString(Payload.serializer(), p).toByteArray(Charsets.UTF_8)
    fun decode(b: ByteArray): Payload = json.decodeFromString(Payload.serializer(), String(b, Charsets.UTF_8))
}
