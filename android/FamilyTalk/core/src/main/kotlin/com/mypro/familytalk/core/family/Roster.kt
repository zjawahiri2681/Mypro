package com.mypro.familytalk.core.family

import com.mypro.familytalk.core.crypto.Bytes
import com.mypro.familytalk.core.crypto.IdentityKeys
import com.mypro.familytalk.core.crypto.PublicIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class Role { ADMIN, MEMBER }

@Serializable
data class Member(
    val name: String,
    val ext: Int,
    val identity: PublicIdentity,
    val role: Role = Role.MEMBER,
    /** Index into the app's avatar palette; purely cosmetic. */
    val color: Int = 0,
) {
    val id: String get() = identity.memberId
}

@Serializable
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

/**
 * Where devices meet. The broker only ever carries sealed [com.mypro.familytalk.core.crypto.Envelope]s
 * on hashed topics; TURN only ever relays DTLS-SRTP. Both can be self-hosted (see server/).
 */
@Serializable
data class RelayConfig(
    val broker: String = DEFAULT_BROKER,
    val brokerUser: String? = null,
    val brokerPassword: String? = null,
    val ice: List<IceServer> = DEFAULT_STUN,
) {
    /**
     * Relays this family actually uses. With the public default, three public brokers on
     * different ports (8883, 8884 and 443) are used together, so a network that blocks a
     * port or has broken IPv6 still gets through. With your own server, only it is used.
     */
    fun endpoints(): List<String> = if (broker == DEFAULT_BROKER) PUBLIC_RELAYS else listOf(broker)

    companion object {
        const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"
        /** Two independent STUN servers so one being blocked or down doesn't stop calls. */
        val DEFAULT_STUN = listOf(IceServer(listOf("stun:stun.l.google.com:19302", "stun:stun.cloudflare.com:3478")))
        val PUBLIC_RELAYS = listOf(
            "ssl://broker.hivemq.com:8883",
            "wss://broker.hivemq.com:8884/mqtt",
            "wss://mqtt.eclipseprojects.io:443/mqtt",
        )
    }
}

/** The family directory body. Serialized once, signed, and shipped as that exact string. */
@Serializable
data class Roster(
    val familyId: String,
    val familyName: String,
    val version: Long,
    val members: List<Member>,
    val relay: RelayConfig = RelayConfig(),
) {
    fun byId(id: String): Member? = members.firstOrNull { it.id == id }
    fun byExt(ext: Int): Member? = members.firstOrNull { it.ext == ext }
    val admins: List<Member> get() = members.filter { it.role == Role.ADMIN }

    fun validate() {
        require(familyId.isNotBlank() && familyName.isNotBlank())
        require(members.isNotEmpty()) { "empty roster" }
        require(admins.isNotEmpty()) { "roster needs an admin" }
        require(members.map { it.id }.toSet().size == members.size) { "duplicate member" }
        require(members.map { it.ext }.toSet().size == members.size) { "duplicate extension" }
        members.forEach { require(it.ext in Extensions.MEMBER_RANGE) { "extension ${it.ext} out of range" } }
    }
}

@Serializable
data class SignedRoster(val body: String, val signer: String, val sig: String) {
    fun roster(): Roster = RosterCodec.json.decodeFromString(body)
}

class RosterRejected(msg: String) : Exception(msg)

object RosterCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun sign(roster: Roster, admin: IdentityKeys): SignedRoster {
        roster.validate()
        val me = roster.byId(admin.memberId) ?: throw RosterRejected("signer not in roster")
        if (me.role != Role.ADMIN) throw RosterRejected("signer is not admin")
        val body = json.encodeToString(roster)
        return SignedRoster(body, admin.memberId, Bytes.b64(admin.sign(domain(body))))
    }

    /**
     * Accepts [incoming] only if it is signed by an admin of the roster we already
     * trust ([current]) — or, for the very first roster, by the admin pinned from the
     * invite QR. Prevents a member from promoting themselves or rolling back history.
     */
    fun verify(incoming: SignedRoster, current: Roster?, pinnedAdmin: PublicIdentity?): Roster {
        val signerKeys = when {
            current != null -> current.byId(incoming.signer)?.takeIf { it.role == Role.ADMIN }?.identity
            else -> pinnedAdmin?.takeIf { it.memberId == incoming.signer }
        } ?: throw RosterRejected("signer not trusted")
        if (!signerKeys.verify(domain(incoming.body), Bytes.unb64(incoming.sig))) throw RosterRejected("bad signature")
        val roster = try { incoming.roster() } catch (e: Exception) { throw RosterRejected("malformed") }
        try { roster.validate() } catch (e: IllegalArgumentException) { throw RosterRejected(e.message ?: "invalid") }
        if (current != null) {
            if (roster.familyId != current.familyId) throw RosterRejected("other family")
            if (roster.version <= current.version) throw RosterRejected("stale version")
            // A member's keys never change under the same id (ids are derived from keys),
            // but an extension must not silently move to a different person's keys.
        }
        return roster
    }

    private fun domain(body: String) = Bytes.lp(Bytes.utf8("ft-roster-v1"), Bytes.utf8(body))
}
