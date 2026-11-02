package com.mypro.familytalk.core.crypto

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom

/**
 * Wire envelope that travels through the relay. The relay sees only these fields;
 * the payload is end-to-end encrypted to the recipient and signed by the sender.
 *
 * Construction (per message):
 *   eph   = fresh X25519 key pair
 *   ikm   = X25519(eph, recipientDh) || X25519(senderDh, recipientDh)
 *   key   = HKDF-SHA256(ikm, salt = ephPub || recipientDh, info = "FamilyTalk v1 envelope" || header)
 *   ct    = ChaCha20-Poly1305(key, nonce, payload, aad = header)
 *   sig   = Ed25519(sender, "ft-env-v1" || header || nonce || ct)
 *
 * The ephemeral half gives sender-side forward secrecy for each message; the static
 * half binds the key to the sender's DH key as well as to the Ed25519 signature.
 */
@Serializable
data class Envelope(
    val v: Int = 1,
    val family: String,
    val from: String,
    val to: String,
    val id: String,
    val ts: Long,
    val eph: String,
    val nonce: String,
    val ct: String,
    val sig: String,
) {
    internal fun header(): ByteArray = Bytes.lp(
        Bytes.utf8("v$v"), Bytes.utf8(family), Bytes.utf8(from), Bytes.utf8(to),
        Bytes.utf8(id), Bytes.utf8(ts.toString()), Bytes.unb64(eph),
    )
}

class SealException(msg: String) : Exception(msg)

object Sealed {
    const val MAX_PAYLOAD = 256 * 1024
    private val rng = SecureRandom()

    fun seal(
        payload: ByteArray,
        familyId: String,
        sender: IdentityKeys,
        recipient: PublicIdentity,
        nowMs: Long = System.currentTimeMillis(),
        messageId: String = Bytes.b64(Bytes.random(16)),
    ): Envelope {
        require(payload.size <= MAX_PAYLOAD) { "payload too large" }
        val ephPriv = X25519PrivateKeyParameters(rng)
        val ephPub = ephPriv.generatePublicKey().encoded
        val draft = Envelope(
            family = familyId, from = sender.memberId, to = recipient.memberId, id = messageId,
            ts = nowMs, eph = Bytes.b64(ephPub), nonce = "", ct = "", sig = "",
        )
        val header = draft.header()
        val ikm = IdentityKeys.x25519(ephPriv, recipient.dhKeyBytes) + sender.agree(recipient.dhKeyBytes)
        val key = hkdf(ikm, ephPub + recipient.dhKeyBytes, header)
        val nonce = Bytes.random(12)
        val ct = aead(true, key, nonce, header, payload)
        val sig = sender.sign(Bytes.lp(Bytes.utf8("ft-env-v1"), header, nonce, ct))
        return draft.copy(nonce = Bytes.b64(nonce), ct = Bytes.b64(ct), sig = Bytes.b64(sig))
    }

    /**
     * Verifies the sender signature first, then decrypts. [senderKeys] must come from
     * the trusted family roster, never from the envelope itself.
     */
    fun open(env: Envelope, recipient: IdentityKeys, senderKeys: PublicIdentity): ByteArray {
        if (env.v != 1) throw SealException("unsupported version")
        if (env.to != recipient.memberId) throw SealException("not for us")
        if (env.from != senderKeys.memberId) throw SealException("sender mismatch")
        val header = env.header()
        val nonce = Bytes.unb64(env.nonce)
        val ct = Bytes.unb64(env.ct)
        if (nonce.size != 12) throw SealException("bad nonce")
        if (!senderKeys.verify(Bytes.lp(Bytes.utf8("ft-env-v1"), header, nonce, ct), Bytes.unb64(env.sig))) {
            throw SealException("bad signature")
        }
        val ephPub = Bytes.unb64(env.eph)
        val ikm = try {
            recipient.agree(ephPub) + recipient.agree(senderKeys.dhKeyBytes)
        } catch (e: IllegalArgumentException) {
            throw SealException("bad key")
        }
        val key = hkdf(ikm, ephPub + recipient.public.dhKeyBytes, header)
        return try {
            aead(false, key, nonce, header, ct)
        } catch (e: Exception) {
            throw SealException("decrypt failed")
        }
    }

    internal fun hkdf(ikm: ByteArray, salt: ByteArray, header: ByteArray): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, Bytes.utf8("FamilyTalk v1 envelope") + Bytes.sha256(header)))
        return ByteArray(32).also { gen.generateBytes(it, 0, 32) }
    }

    fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(input.size))
        var n = c.processBytes(input, 0, input.size, out, 0)
        n += c.doFinal(out, n)
        return if (n == out.size) out else out.copyOf(n)
    }
}
