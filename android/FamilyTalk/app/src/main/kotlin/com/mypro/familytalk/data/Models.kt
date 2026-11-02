package com.mypro.familytalk.data

import com.mypro.familytalk.core.crypto.PrivatePrekey
import com.mypro.familytalk.core.crypto.PublicIdentity
import com.mypro.familytalk.core.crypto.SignedPrekey
import com.mypro.familytalk.core.family.Invite
import com.mypro.familytalk.core.family.RelayConfig
import com.mypro.familytalk.core.family.SignedRoster
import com.mypro.familytalk.core.sip.SipAccount
import kotlinx.serialization.Serializable

@Serializable
data class AppData(
    val identity: String? = null,
    val myName: String = "",
    /** Root of trust, pinned from the invite QR (or ourselves when we created the family). */
    val pinnedAdmin: PublicIdentity? = null,
    /** Known before the first roster arrives (joining). */
    val familyId: String? = null,
    val joinRelay: RelayConfig? = null,
    /** The invite we are joining with, until the first roster arrives (kept across restarts). */
    val joinInvite: Invite? = null,
    val roster: SignedRoster? = null,
    val removed: Boolean = false,
    /** Open invites (admin only). Single use; removed once someone joins with it. */
    val invites: List<Invite> = emptyList(),
    val sip: SipAccount? = null,
    /** Route all media through TURN so family members never see each other's IP address. */
    val relayOnly: Boolean = false,
    /** Member ids whose safety number was compared in person. */
    val verified: Set<String> = emptySet(),
    /** Blocks screenshots / recent-apps previews of chats and calls. */
    val secureScreen: Boolean = true,
    val clientId: String = "",
    val replay: Map<String, Long> = emptyMap(),
    /** Our private prekeys (deleted after 3 weeks for forward secrecy). */
    val prekeys: List<PrivatePrekey> = emptyList(),
    /** Newest verified prekey of each family member. */
    val peerPrekeys: Map<String, SignedPrekey> = emptyMap(),
    /** The signed public half of our newest prekey, re-sent to members who join later. */
    val myPrekey: SignedPrekey? = null,
    /** Show message text in notifications (off: "New family message" only). */
    val notifPreview: Boolean = false,
    /** Check GitHub for new FamilyTalk builds and download them in the background. */
    val autoUpdate: Boolean = true,
)

/** A join request waiting for the admin to approve it on screen. */
data class PendingJoin(val name: String, val identity: PublicIdentity, val request: com.mypro.familytalk.core.protocol.JoinRequest, val safety: String)

@Serializable
data class FileInfo(val name: String, val mime: String, val size: Long, val localPath: String? = null, val progress: Float = 0f)

@Serializable
enum class MsgStatus { SENDING, SENT, DELIVERED, READ, RECEIVED, FAILED }

@Serializable
data class ChatMsg(
    val id: String,
    /** Member id of the other person, or [FAMILY_CONV] for the family group. */
    val conv: String,
    val from: String,
    val at: Long,
    val text: String? = null,
    val file: FileInfo? = null,
    val status: MsgStatus,
    val system: Boolean = false,
)

const val FAMILY_CONV = "family"

@Serializable
data class CallLogEntry(val id: String, val with: List<String>, val outgoing: Boolean, val video: Boolean, val at: Long, val durationSec: Long, val result: String)

@Serializable
data class History(val chats: List<ChatMsg> = emptyList(), val calls: List<CallLogEntry> = emptyList(), val unread: Map<String, Int> = emptyMap())
