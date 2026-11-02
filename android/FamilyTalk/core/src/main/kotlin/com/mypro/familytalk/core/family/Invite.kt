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

        /**
         * Accepts the bare code, a scanned QR, or a whole forwarded message (WhatsApp, SMS,
         * e-mail) that contains the code somewhere — even if the app wrapped it over
         * several lines or added spaces.
         */
        fun decode(text: String): Invite {
            val start = text.indexOf(PREFIX, ignoreCase = true)
            require(start >= 0) { "No FamilyTalk invite code found. It starts with FT1:" }
            val rest = text.substring(start + PREFIX.length)
            // 1) the code as one unbroken token
            val token = rest.takeWhile { it in B64 }
            tryParse(token)?.let { return it }
            // 2) the code broken by line wraps / spaces / invisible characters
            val joined = StringBuilder()
            for (c in rest) {
                if (c in B64) joined.append(c)
                else if (c.isWhitespace() || c in INVISIBLE) continue
                else break
                tryParse(joined.toString())?.let { return it }
            }
            throw IllegalArgumentException("The invite code is incomplete. Copy the whole message again, or scan the QR.")
        }

        private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private const val INVISIBLE = "\u200B\u200C\u200D\u200E\u200F\u2066\u2067\u2068\u2069\uFEFF"

        private fun tryParse(b64: String): Invite? = if (b64.length < 40) null else runCatching {
            RosterCodec.json.decodeFromString<Invite>(String(Bytes.unb64(b64), Charsets.UTF_8))
        }.getOrNull()

        /** Text for sending the invite through another app. The code goes last so nothing is glued to it. */
        fun shareMessage(invite: Invite): String =
            "You're invited to join \"${invite.familyName}\" on FamilyTalk (private family calls and chat).\n\n" +
                "1. Install the FamilyTalk app.\n" +
                "2. Open it, tap \"I have an invite code instead\" and paste this whole message.\n" +
                "3. ${invite.adminName} will confirm it's you.\n\n" +
                "Valid for 24 hours, one person only.\n\n" + invite.encode()

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val m = HMac(SHA256Digest()); m.init(KeyParameter(key)); m.update(data, 0, data.size)
            return ByteArray(32).also { m.doFinal(it, 0) }
        }
    }
}
