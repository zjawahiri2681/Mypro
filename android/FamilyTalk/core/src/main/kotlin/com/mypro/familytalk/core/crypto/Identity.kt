package com.mypro.familytalk.core.crypto

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Public half of a device identity. Everything another family member needs to
 * authenticate us (Ed25519) and to encrypt to us (X25519).
 */
@Serializable
data class PublicIdentity(val signKey: String, val dhKey: String) {
    val signKeyBytes: ByteArray get() = Bytes.unb64(signKey)
    val dhKeyBytes: ByteArray get() = Bytes.unb64(dhKey)

    /** Stable member id: 128-bit hash of the signing key, hex. Not secret. */
    val memberId: String get() = Bytes.hex(Bytes.sha256(Bytes.utf8("ft-member-id"), signKeyBytes).copyOf(16))

    fun verify(message: ByteArray, signature: ByteArray): Boolean = try {
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(signKeyBytes, 0))
            update(message, 0, message.size)
            verifySignature(signature)
        }
    } catch (e: Exception) {
        false
    }

    init {
        require(Bytes.unb64(signKey).size == 32) { "bad signing key" }
        require(Bytes.unb64(dhKey).size == 32) { "bad DH key" }
    }
}

/** Private device identity. Generated on the phone, never leaves it. */
class IdentityKeys private constructor(
    private val signPriv: Ed25519PrivateKeyParameters,
    private val dhPriv: X25519PrivateKeyParameters,
) {
    val public: PublicIdentity = PublicIdentity(
        Bytes.b64(signPriv.generatePublicKey().encoded),
        Bytes.b64(dhPriv.generatePublicKey().encoded),
    )
    val memberId: String get() = public.memberId

    fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, signPriv)
        update(message, 0, message.size)
        generateSignature()
    }

    /** Raw X25519 agreement with a peer public key. Rejects all-zero (low-order) results. */
    fun agree(peerDhPub: ByteArray): ByteArray = x25519(dhPriv, peerDhPub)

    /** 64-byte private export for the app's encrypted key store. */
    fun exportPrivate(): ByteArray = signPriv.encoded + dhPriv.encoded

    companion object {
        fun generate(rng: SecureRandom = SecureRandom()): IdentityKeys =
            IdentityKeys(Ed25519PrivateKeyParameters(rng), X25519PrivateKeyParameters(rng))

        fun import(bytes: ByteArray): IdentityKeys {
            require(bytes.size == 64) { "bad private key blob" }
            return IdentityKeys(Ed25519PrivateKeyParameters(bytes, 0), X25519PrivateKeyParameters(bytes, 32))
        }

        internal fun x25519(priv: X25519PrivateKeyParameters, peerPub: ByteArray): ByteArray {
            require(peerPub.size == 32) { "bad peer key" }
            val out = ByteArray(32)
            priv.generateSecret(X25519PublicKeyParameters(peerPub, 0), out, 0)
            require(out.any { it != 0.toByte() }) { "low-order peer key" }
            return out
        }
    }
}
