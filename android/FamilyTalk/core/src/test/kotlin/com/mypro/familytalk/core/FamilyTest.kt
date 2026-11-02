package com.mypro.familytalk.core

import com.mypro.familytalk.core.crypto.IdentityKeys
import com.mypro.familytalk.core.family.FamilyAdmin
import com.mypro.familytalk.core.family.Invite
import com.mypro.familytalk.core.family.Role
import com.mypro.familytalk.core.family.RosterCodec
import com.mypro.familytalk.core.family.RosterRejected
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FamilyTest {
    private val dad = IdentityKeys.generate()
    private val mum = IdentityKeys.generate()
    private val son = IdentityKeys.generate()
    private val admin = FamilyAdmin(dad)

    @Test fun createInviteAdmitAndVerify() {
        val first = admin.create("Jawahiri", "Dad")
        val r1 = RosterCodec.verify(first, null, dad.public)
        assertEquals(101, r1.byId(dad.memberId)!!.ext)

        val invite = Invite.decode(admin.invite(r1).encode())
        assertEquals(dad.public, invite.admin)
        val proof = invite.proof("Mum", mum.public)
        val second = admin.admit(r1, invite, "Mum", mum.public, proof)
        // Mum, who pinned Dad from the QR, accepts the first roster she receives.
        val r2 = RosterCodec.verify(second, null, invite.admin)
        assertEquals(102, r2.byId(mum.memberId)!!.ext)
        assertEquals(Role.MEMBER, r2.byId(mum.memberId)!!.role)
        // Existing members accept it as a newer version.
        assertEquals(2, RosterCodec.verify(second, r1, null).version)
    }

    @Test fun wrongProofOrExpiredInviteRejected() {
        val r1 = RosterCodec.verify(admin.create("F", "Dad"), null, dad.public)
        val invite = admin.invite(r1, nowMs = 0)
        val proofForOtherKey = invite.proof("Mum", son.public)
        assertFailsWith<IllegalArgumentException> { admin.admit(r1, invite, "Mum", mum.public, proofForOtherKey, nowMs = 1) }
        val good = invite.proof("Mum", mum.public)
        assertFailsWith<IllegalArgumentException> { admin.admit(r1, invite, "Mum", mum.public, good, nowMs = Invite.DEFAULT_TTL_MS + 1) }
    }

    @Test fun memberCannotPromoteThemselves() {
        val r1 = RosterCodec.verify(admin.create("F", "Dad"), null, dad.public)
        val inv = admin.invite(r1)
        val r2 = RosterCodec.verify(admin.admit(r1, inv, "Son", son.public, inv.proof("Son", son.public)), r1, null)
        // Son forges a roster making himself admin and signs it himself.
        val forged = r2.copy(version = 3, members = r2.members.map { if (it.id == son.memberId) it.copy(role = Role.ADMIN) else it })
        val signed = RosterCodec.sign(forged, son) // signing succeeds locally ...
        assertFailsWith<RosterRejected> { RosterCodec.verify(signed, r2, null) } // ... but nobody accepts it
    }

    @Test fun rollbackAndTamperingRejected() {
        val s1 = admin.create("F", "Dad")
        val r1 = RosterCodec.verify(s1, null, dad.public)
        val inv = admin.invite(r1)
        val s2 = admin.admit(r1, inv, "Mum", mum.public, inv.proof("Mum", mum.public))
        val r2 = RosterCodec.verify(s2, r1, null)
        assertFailsWith<RosterRejected> { RosterCodec.verify(s1, r2, null) }
        assertFailsWith<RosterRejected> { RosterCodec.verify(s2.copy(body = s2.body.replace("Mum", "Eve")), r1, null) }
        assertFailsWith<RosterRejected> { RosterCodec.verify(s1, null, mum.public) }
    }

    @Test fun removedMemberDisappearsAndExtensionIsReused() {
        val r1 = RosterCodec.verify(admin.create("F", "Dad"), null, dad.public)
        val i = admin.invite(r1)
        val r2 = RosterCodec.verify(admin.admit(r1, i, "Mum", mum.public, i.proof("Mum", mum.public)), r1, null)
        val r3 = RosterCodec.verify(admin.remove(r2, mum.memberId), r2, null)
        assertTrue(r3.byId(mum.memberId) == null)
        val i2 = admin.invite(r3)
        val r4 = RosterCodec.verify(admin.admit(r3, i2, "Son", son.public, i2.proof("Son", son.public)), r3, null)
        assertEquals(102, r4.byId(son.memberId)!!.ext)
    }
}

class InviteTextTest {
    private val dad = com.mypro.familytalk.core.crypto.IdentityKeys.generate()
    private val admin = FamilyAdmin(dad)
    private val roster = RosterCodec.verify(admin.create("Al Family", "Baba"), null, dad.public)
    private val invite = admin.invite(roster, "Ali")
    private val code = invite.encode()

    @Test fun bareCodeAndScannedQr() {
        assertEquals(invite, Invite.decode(code))
        assertEquals(invite, Invite.decode("  $code\n"))
    }

    @Test fun wholeForwardedMessage() {
        assertEquals(invite, Invite.decode(Invite.shareMessage(invite)))
        assertEquals(invite, Invite.decode("Forwarded\n" + Invite.shareMessage(invite) + "\n\nSent from my phone"))
    }

    @Test fun codeWrappedOrWithInvisibleCharacters() {
        val wrapped = code.chunked(37).joinToString("\n")
        assertEquals(invite, Invite.decode("Join us: $wrapped"))
        assertEquals(invite, Invite.decode("⁦" + code.chunked(50).joinToString("​ ") + "⁩"))
    }

    @Test fun truncatedOrMissingCodeGivesClearError() {
        val e1 = kotlin.test.assertFailsWith<IllegalArgumentException> { Invite.decode(code.dropLast(20)) }
        assertTrue(e1.message!!.contains("incomplete"))
        kotlin.test.assertFailsWith<IllegalArgumentException> { Invite.decode("hello there") }
    }
}
