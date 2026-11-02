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
 *   R     = recipient's newest signed prekey (or its identity DH key before one is known)
 *   ikm   = X25519(eph, R) || X25519(senderDh, recipientDh)
 *   key   = HKDF-SHA256(ikm, salt = ephPub || recipientDh, info = "FamilyTalk v1 envelope" || header)
 *   ct    = ChaCha20-Poly1305(key, nonce, payload, aad = header)
 *   sig   = Ed25519(sender, "ft-env-v1" || header || nonce || ct)
 *
 * The eph x prekey half gives forward secrecy once the recipient deletes old prekeys
 * (see [Prekeys]); the static half binds the key to the sender's DH key as well as to
 * the Ed25519 signature.  The whole envelope is then wrapped by [sealOuter].
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
    /** Id of the recipient prekey used, or null when the identity DH key was used. */
    val pk: String? = null,
) {
    internal fun header(): ByteArray = Bytes.lp(
        Bytes.utf8("v$v"), Bytes.utf8(family), Bytes.utf8(from), Bytes.utf8(to),
        Bytes.utf8(id), Bytes.utf8(ts.toString()), Bytes.unb64(eph), Bytes.utf8(pk ?: "-"),
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
        prekey: SignedPrekey? = null,
    ): Envelope {
        require(prekey == null || prekey.owner == recipient.memberId) { "prekey of someone else" }
        require(payload.size <= MAX_PAYLOAD) { "payload too large" }
        val ephPriv = X25519PrivateKeyParameters(rng)
        val ephPub = ephPriv.generatePublicKey().encoded
        val draft = Envelope(
            family = familyId, from = sender.memberId, to = recipient.memberId, id = messageId,
            ts = nowMs, eph = Bytes.b64(ephPub), nonce = "", ct = "", sig = "", pk = prekey?.id,
        )
        val header = draft.header()
        val target = prekey?.let { Bytes.unb64(it.pub) } ?: recipient.dhKeyBytes
        val ikm = IdentityKeys.x25519(ephPriv, target) + sender.agree(recipient.dhKeyBytes)
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
    fun open(env: Envelope, recipient: IdentityKeys, senderKeys: PublicIdentity, prekeys: (String) -> PrivatePrekey? = { null }): ByteArray {
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
            val first = env.pk?.let { id -> Prekeys.agree(prekeys(id) ?: throw SealException("prekey expired"), ephPub) } ?: recipient.agree(ephPub)
            first + recipient.agree(senderKeys.dhKeyBytes)
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

    /**
     * Outer "sealed sender" layer. Everything the relay could learn from the inner
     * envelope (family, sender, recipient, time, message id, exact size) is hidden:
     * only the recipient's inbox topic, a padded size bucket and timing remain visible.
     * Anonymous box: fresh X25519 key to the recipient's DH key, ChaCha20-Poly1305.
     */
    fun sealOuter(inner: ByteArray, recipientDh: ByteArray): ByteArray {
        val ephPriv = X25519PrivateKeyParameters(rng)
        val ephPub = ephPriv.generatePublicKey().encoded
        val key = outerKey(IdentityKeys.x25519(ephPriv, recipientDh), ephPub, recipientDh)
        val nonce = Bytes.random(12)
        val ct = aead(true, key, nonce, OUTER_AAD, pad(inner))
        return byteArrayOf(OUTER_VERSION) + ephPub + nonce + ct
    }

    fun openOuter(data: ByteArray, recipient: IdentityKeys): ByteArray {
        if (data.size < 1 + 32 + 12 + 16 + 4 || data[0] != OUTER_VERSION) throw SealException("bad outer")
        val ephPub = data.copyOfRange(1, 33)
        val nonce = data.copyOfRange(33, 45)
        val key = try { outerKey(recipient.agree(ephPub), ephPub, recipient.public.dhKeyBytes) } catch (e: IllegalArgumentException) { throw SealException("bad key") }
        val plain = try { aead(false, key, nonce, OUTER_AAD, data.copyOfRange(45, data.size)) } catch (e: Exception) { throw SealException("not for us") }
        return unpad(plain)
    }

    private const val OUTER_VERSION: Byte = 2
    private val OUTER_AAD = Bytes.utf8("ft-outer-v2")

    private fun outerKey(shared: ByteArray, ephPub: ByteArray, recipientDh: ByteArray): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(shared, ephPub + recipientDh, Bytes.utf8("FamilyTalk v2 outer")))
        return ByteArray(32).also { gen.generateBytes(it, 0, 32) }
    }

    /** Pads to 1 KiB buckets (powers of two above 16 KiB) so sizes reveal little. */
    internal fun pad(b: ByteArray): ByteArray {
        val need = b.size + 4
        var bucket = 1024
        while (bucket < need) bucket = if (bucket < 16 * 1024) bucket + 1024 else bucket * 2
        val out = ByteArray(bucket)
        out[0] = (b.size ushr 24).toByte(); out[1] = (b.size ushr 16).toByte(); out[2] = (b.size ushr 8).toByte(); out[3] = b.size.toByte()
        System.arraycopy(b, 0, out, 4, b.size)
        return out
    }

    internal fun unpad(b: ByteArray): ByteArray {
        if (b.size < 4) throw SealException("bad padding")
        val n = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        if (n < 0 || n > b.size - 4) throw SealException("bad padding")
        return b.copyOfRange(4, 4 + n)
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
