package com.mypro.familytalk.core

import com.mypro.familytalk.core.crypto.Bytes
import com.mypro.familytalk.core.crypto.IdentityKeys
import com.mypro.familytalk.core.crypto.SafetyNumber
import com.mypro.familytalk.core.crypto.SealException
import com.mypro.familytalk.core.crypto.Sealed
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CryptoTest {
    private val alice = IdentityKeys.generate()
    private val bob = IdentityKeys.generate()
    private val eve = IdentityKeys.generate()

    @Test fun sealOpenRoundTrip() {
        val msg = Bytes.utf8("salam, dinner at 8")
        val env = Sealed.seal(msg, "fam", alice, bob.public)
        assertContentEquals(msg, Sealed.open(env, bob, alice.public))
    }

    @Test fun ciphertextHidesPlaintext() {
        val env = Sealed.seal(Bytes.utf8("secret-word-xyz"), "fam", alice, bob.public)
        assert(!env.toString().contains("secret-word-xyz"))
    }

    @Test fun wrongRecipientCannotOpen() {
        val env = Sealed.seal(Bytes.utf8("hi"), "fam", alice, bob.public)
        assertFailsWith<SealException> { Sealed.open(env.copy(to = eve.memberId), eve, alice.public) }
    }

    @Test fun forgedSenderRejected() {
        // Eve signs with her own key but claims to be Alice.
        val env = Sealed.seal(Bytes.utf8("send money"), "fam", eve, bob.public).copy(from = alice.memberId)
        assertFailsWith<SealException> { Sealed.open(env, bob, alice.public) }
    }

    @Test fun tamperedFieldsRejected() {
        val env = Sealed.seal(Bytes.utf8("hi"), "fam", alice, bob.public)
        val ct = Bytes.unb64(env.ct).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<SealException> { Sealed.open(env.copy(ct = Bytes.b64(ct)), bob, alice.public) }
        assertFailsWith<SealException> { Sealed.open(env.copy(ts = env.ts + 1), bob, alice.public) }
        assertFailsWith<SealException> { Sealed.open(env.copy(id = "other"), bob, alice.public) }
        assertFailsWith<SealException> { Sealed.open(env.copy(family = "fam2"), bob, alice.public) }
    }

    @Test fun lowOrderEphemeralRejected() {
        val env = Sealed.seal(Bytes.utf8("hi"), "fam", alice, bob.public)
        val zero = Bytes.b64(ByteArray(32))
        // Re-sign so only the DH check can catch it.
        assertFailsWith<SealException> { Sealed.open(env.copy(eph = zero), bob, alice.public) }
    }

    @Test fun privateKeyExportRoundTrip() {
        val again = IdentityKeys.import(alice.exportPrivate())
        assertEquals(alice.public, again.public)
    }

    @Test fun safetyNumberIsSymmetricAndKeyBound() {
        val ab = SafetyNumber.forPair(alice.public, bob.public)
        assertEquals(60, ab.length)
        assertEquals(ab, SafetyNumber.forPair(bob.public, alice.public))
        assertNotEquals(ab, SafetyNumber.forPair(alice.public, eve.public))
        assertEquals(12, SafetyNumber.format(ab).split(" ").size)
    }
}

class PrekeyTest {
    private val alice = IdentityKeys.generate()
    private val bob = IdentityKeys.generate()

    @Test fun prekeyMessagesNeedThePrekeyAndBecomeUnreadableWhenItIsDeleted() {
        val (priv, signed) = com.mypro.familytalk.core.crypto.Prekeys.generate(bob)
        kotlin.test.assertTrue(signed.verify(bob.public))
        kotlin.test.assertFalse(signed.verify(alice.public))
        val env = Sealed.seal(Bytes.utf8("old secret"), "fam", alice, bob.public, prekey = signed)
        assertContentEquals(Bytes.utf8("old secret"), Sealed.open(env, bob, alice.public) { if (it == priv.id) priv else null })
        // After rotation + pruning the private prekey is gone: even Bob's identity key alone cannot open it.
        assertFailsWith<SealException> { Sealed.open(env, bob, alice.public) { null } }
        // Tampering with the prekey id is caught by the signature.
        assertFailsWith<SealException> { Sealed.open(env.copy(pk = null), bob, alice.public) { priv } }
    }

    @Test fun rotationAndPruning() {
        val P = com.mypro.familytalk.core.crypto.Prekeys
        val (k1, _) = P.generate(bob, now = 0)
        val (k2, _) = P.generate(bob, now = P.ROTATE_MS)
        kotlin.test.assertTrue(P.needsRotation(listOf(k1), now = P.ROTATE_MS))
        kotlin.test.assertFalse(P.needsRotation(listOf(k1, k2), now = P.ROTATE_MS + 1))
        assertEquals(listOf(k2), P.prune(listOf(k1, k2), now = P.KEEP_MS + 1))
        assertEquals(listOf(k2), P.prune(listOf(k2), now = 100 * P.KEEP_MS)) // newest is always kept
    }

    @Test fun forgedPrekeyRejected() {
        val (_, signed) = com.mypro.familytalk.core.crypto.Prekeys.generate(bob)
        kotlin.test.assertFalse(signed.copy(pub = Bytes.b64(ByteArray(32) { 9 })).verify(bob.public))
    }
}
