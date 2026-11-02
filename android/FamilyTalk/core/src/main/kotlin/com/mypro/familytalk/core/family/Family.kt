package com.mypro.familytalk.core.family

import com.mypro.familytalk.core.crypto.Bytes
import com.mypro.familytalk.core.crypto.IdentityKeys
import com.mypro.familytalk.core.crypto.PublicIdentity

/** Admin-side family management: create, invite, admit, remove. Pure logic. */
class FamilyAdmin(private val me: IdentityKeys) {

    fun create(familyName: String, myName: String, relay: RelayConfig = RelayConfig()): SignedRoster {
        val roster = Roster(
            familyId = Bytes.b64(Bytes.random(16)),
            familyName = familyName.trim(),
            version = 1,
            members = listOf(Member(myName.trim(), Extensions.MEMBER_RANGE.first, me.public, Role.ADMIN, 0)),
            relay = relay,
        )
        return RosterCodec.sign(roster, me)
    }

    fun invite(roster: Roster, forName: String? = null, nowMs: Long = System.currentTimeMillis()): Invite {
        val mine = roster.byId(me.memberId) ?: error("not in roster")
        return Invite(
            familyId = roster.familyId, familyName = roster.familyName, admin = me.public, adminName = mine.name,
            relay = roster.relay, secret = Bytes.b64(Bytes.random(32)), expiresAt = nowMs + Invite.DEFAULT_TTL_MS,
            forName = forName,
        )
    }

    /** Validates a join request against the invite and returns the next signed roster. */
    fun admit(
        roster: Roster, invite: Invite, name: String, joiner: PublicIdentity, proof: String,
        nowMs: Long = System.currentTimeMillis(),
    ): SignedRoster {
        require(nowMs <= invite.expiresAt) { "invite expired" }
        require(invite.familyId == roster.familyId) { "invite for another family" }
        require(invite.checkProof(name, joiner, proof)) { "invite proof does not match" }
        require(roster.byId(joiner.memberId) == null) { "already a member" }
        val clean = name.trim().take(40).ifBlank { "Family member" }
        val next = roster.copy(
            version = roster.version + 1,
            members = roster.members + Member(clean, Extensions.nextFree(roster.members.map { it.ext }), joiner, Role.MEMBER, roster.members.size % 8),
        )
        return RosterCodec.sign(next, me)
    }

    fun remove(roster: Roster, memberId: String): SignedRoster {
        require(memberId != me.memberId) { "cannot remove yourself" }
        return RosterCodec.sign(roster.copy(version = roster.version + 1, members = roster.members.filter { it.id != memberId }), me)
    }

    fun update(roster: Roster, change: (Roster) -> Roster): SignedRoster =
        RosterCodec.sign(change(roster).copy(version = roster.version + 1, familyId = roster.familyId), me)
}
