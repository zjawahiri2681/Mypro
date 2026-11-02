package com.mypro.familytalk.core.crypto

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom

/**
 * Medium-term X25519 key, signed by the owner's identity key and replaced every week.
 * Senders encrypt to the newest prekey; the owner deletes private prekeys after
 * [Prekeys.KEEP_MS]. Once deleted, recorded traffic to this phone can no longer be
 * decrypted even if the phone (and its identity key) is later stolen.
 */
@Serializable
data class SignedPrekey(val owner: String, val id: String, val pub: String, val created: Long, val sig: String) {
    fun verify(ownerKeys: PublicIdentity): Boolean =
        ownerKeys.memberId == owner && Bytes.unb64(pub).size == 32 && ownerKeys.verify(signedBytes(owner, id, pub, created), Bytes.unb64(sig))

    companion object {
        internal fun signedBytes(owner: String, id: String, pub: String, created: Long) =
            Bytes.lp(Bytes.utf8("ft-prekey-v1"), Bytes.utf8(owner), Bytes.utf8(id), Bytes.unb64(pub), Bytes.utf8(created.toString()))
    }
}

@Serializable
data class PrivatePrekey(val id: String, val priv: String, val created: Long)

object Prekeys {
    const val ROTATE_MS = 7L * 24 * 60 * 60 * 1000
    /** Long enough for a phone that was off for a while to still read queued messages. */
    const val KEEP_MS = 21L * 24 * 60 * 60 * 1000

    fun generate(me: IdentityKeys, now: Long = System.currentTimeMillis()): Pair<PrivatePrekey, SignedPrekey> {
        val priv = X25519PrivateKeyParameters(SecureRandom())
        val pub = Bytes.b64(priv.generatePublicKey().encoded)
        val id = Bytes.b64(Bytes.random(9))
        val sig = me.sign(SignedPrekey.signedBytes(me.memberId, id, pub, now))
        return PrivatePrekey(id, Bytes.b64(priv.encoded), now) to SignedPrekey(me.memberId, id, pub, now, Bytes.b64(sig))
    }

    /** Keeps the private prekeys that may still be needed; everything older is forgotten. */
    fun prune(keys: List<PrivatePrekey>, now: Long = System.currentTimeMillis()): List<PrivatePrekey> {
        val newest = keys.maxByOrNull { it.created }
        return keys.filter { it == newest || now - it.created <= KEEP_MS }
    }

    fun needsRotation(keys: List<PrivatePrekey>, now: Long = System.currentTimeMillis()): Boolean =
        keys.maxOfOrNull { it.created }?.let { now - it >= ROTATE_MS } ?: true

    internal fun agree(p: PrivatePrekey, peerPub: ByteArray): ByteArray =
        IdentityKeys.x25519(X25519PrivateKeyParameters(Bytes.unb64(p.priv), 0), peerPub)
}
