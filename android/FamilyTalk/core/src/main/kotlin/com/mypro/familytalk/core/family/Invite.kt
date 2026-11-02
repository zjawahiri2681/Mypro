package com.mypro.familytalk.core.family

import com.mypro.familytalk.core.crypto.Bytes
import com.mypro.familytalk.core.crypto.PublicIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Shown as a QR code on the admin's phone and scanned in person. Carries the one-time
 * secret that proves a join request came from someone who physically saw the code,
 * and the admin's keys, which the new phone pins as its root of trust.
 */
@Serializable
data class Invite(
    val familyId: String,
    val familyName: String,
    val admin: PublicIdentity,
    val adminName: String,
    val relay: RelayConfig,
    val secret: String,
    val expiresAt: Long,
    /** Optional: the extension and name the admin pre-assigned for this invitee. */
    val forName: String? = null,
) {
    val inviteId: String get() = Bytes.b64(Bytes.sha256(Bytes.utf8("ft-invite-id"), Bytes.unb64(secret)).copyOf(12))

    fun encode(): String = PREFIX + Bytes.b64(Bytes.utf8(RosterCodec.json.encodeToString(this)))

    fun proof(name: String, joiner: PublicIdentity): String = Bytes.b64(hmac(Bytes.unb64(secret), proofInput(name, joiner)))

    fun checkProof(name: String, joiner: PublicIdentity, proof: String): Boolean =
        Bytes.equal(hmac(Bytes.unb64(secret), proofInput(name, joiner)), Bytes.unb64(proof))

    private fun proofInput(name: String, joiner: PublicIdentity) =
        Bytes.lp(Bytes.utf8("ft-join-v1"), Bytes.utf8(familyId), Bytes.utf8(name), joiner.signKeyBytes, joiner.dhKeyBytes)

    companion object {
        const val PREFIX = "FT1:"
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000

        fun decode(text: String): Invite {
            val t = text.trim()
            require(t.startsWith(PREFIX)) { "Not a FamilyTalk invite" }
            return RosterCodec.json.decodeFromString(String(Bytes.unb64(t.removePrefix(PREFIX)), Charsets.UTF_8))
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val m = HMac(SHA256Digest()); m.init(KeyParameter(key)); m.update(data, 0, data.size)
            return ByteArray(32).also { m.doFinal(it, 0) }
        }
    }
}
