package com.mypro.rfidguard.core.security

import com.mypro.rfidguard.core.ndef.NdefContents
import com.mypro.rfidguard.core.ndef.NdefRecordData
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticity seal for tags written by this app: an extra NDEF record holding a truncated
 * HMAC-SHA256 over the tag UID and every other record. It shows that the content was written
 * with this vault's key and has not been edited or copied onto a tag with a different UID.
 * It cannot detect a copy onto a "magic" tag that also fakes the UID.
 */
object TagSigner {
    enum class Status { NONE, VALID, INVALID, OTHER_KEY }

    private const val VERSION = 1
    private const val MAC_BYTES = 16
    private const val KEY_ID_BYTES = 4

    fun keyId(key: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update("RFIDGuard-keyid-v1".toByteArray()); update(key); digest()
        }.copyOf(KEY_ID_BYTES)

    private fun isSignature(r: NdefRecordData) =
        r.tnf == NdefRecordData.TNF_EXTERNAL && String(r.type, Charsets.US_ASCII).equals(NdefContents.SIGNATURE_TYPE, true)

    private fun mac(key: ByteArray, uid: ByteArray, records: List<NdefRecordData>): ByteArray {
        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).run {
            write("RFIDGuard-sig-v1".toByteArray())
            writeInt(uid.size); write(uid)
            writeInt(records.size)
            for (r in records) {
                writeInt(r.tnf)
                writeInt(r.type.size); write(r.type)
                writeInt(r.id.size); write(r.id)
                writeInt(r.payload.size); write(r.payload)
            }
            flush()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(buf.toByteArray()).copyOf(MAC_BYTES)
    }

    /** Returns [records] (without any earlier seal) followed by a fresh signature record. */
    fun sign(key: ByteArray, uid: ByteArray, records: List<NdefRecordData>): List<NdefRecordData> {
        require(key.size >= 32) { "Signing key too short" }
        val content = records.filterNot(::isSignature)
        val payload = byteArrayOf(VERSION.toByte()) + keyId(key) + mac(key, uid, content)
        return content + NdefRecordData(NdefRecordData.TNF_EXTERNAL, NdefContents.SIGNATURE_TYPE.toByteArray(), payload = payload)
    }

    fun verify(key: ByteArray, uid: ByteArray, records: List<NdefRecordData>): Status {
        val sigs = records.filter(::isSignature)
        if (sigs.isEmpty()) return Status.NONE
        if (sigs.size != 1 || !isSignature(records.last())) return Status.INVALID
        val p = sigs[0].payload
        if (p.size != 1 + KEY_ID_BYTES + MAC_BYTES || p[0].toInt() != VERSION) return Status.INVALID
        if (!MessageDigest.isEqual(p.copyOfRange(1, 1 + KEY_ID_BYTES), keyId(key))) return Status.OTHER_KEY
        val expected = mac(key, uid, records.dropLast(1))
        return if (MessageDigest.isEqual(expected, p.copyOfRange(1 + KEY_ID_BYTES, p.size))) Status.VALID else Status.INVALID
    }
}
