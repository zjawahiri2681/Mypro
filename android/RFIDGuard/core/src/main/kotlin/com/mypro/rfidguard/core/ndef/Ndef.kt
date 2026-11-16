package com.mypro.rfidguard.core.ndef

import java.io.ByteArrayOutputStream

class NdefFormatException(message: String) : Exception(message)

/** One NDEF record, independent of the Android framework so it can be unit-tested on the JVM. */
class NdefRecordData(
    val tnf: Int,
    val type: ByteArray = ByteArray(0),
    val id: ByteArray = ByteArray(0),
    val payload: ByteArray = ByteArray(0),
) {
    companion object {
        const val TNF_EMPTY = 0
        const val TNF_WELL_KNOWN = 1
        const val TNF_MIME = 2
        const val TNF_ABSOLUTE_URI = 3
        const val TNF_EXTERNAL = 4
        const val TNF_UNKNOWN = 5
    }
}

object NdefCodec {
    /** Upper bound for a message we are willing to parse; phone-readable tags hold far less. */
    const val MAX_MESSAGE_BYTES = 64 * 1024
    private const val MAX_RECORDS = 256

    fun parse(bytes: ByteArray): List<NdefRecordData> {
        if (bytes.size > MAX_MESSAGE_BYTES) throw NdefFormatException("Message too large")
        val out = ArrayList<NdefRecordData>()
        var pos = 0
        fun need(n: Int) {
            if (n < 0 || pos + n > bytes.size) throw NdefFormatException("Truncated NDEF record")
        }
        while (pos < bytes.size) {
            if (out.size >= MAX_RECORDS) throw NdefFormatException("Too many records")
            need(2)
            val header = bytes[pos++].toInt() and 0xFF
            val messageEnd = header and 0x40 != 0
            val chunked = header and 0x20 != 0
            val short = header and 0x10 != 0
            val hasId = header and 0x08 != 0
            val tnf = header and 0x07
            if (chunked) throw NdefFormatException("Chunked records are not supported")
            val typeLen = bytes[pos++].toInt() and 0xFF
            val payloadLen: Int
            if (short) {
                need(1)
                payloadLen = bytes[pos++].toInt() and 0xFF
            } else {
                need(4)
                val l = ((bytes[pos].toLong() and 0xFF) shl 24) or ((bytes[pos + 1].toLong() and 0xFF) shl 16) or
                    ((bytes[pos + 2].toLong() and 0xFF) shl 8) or (bytes[pos + 3].toLong() and 0xFF)
                pos += 4
                if (l > MAX_MESSAGE_BYTES) throw NdefFormatException("Record payload too large")
                payloadLen = l.toInt()
            }
            var idLen = 0
            if (hasId) {
                need(1)
                idLen = bytes[pos++].toInt() and 0xFF
            }
            need(typeLen); val type = bytes.copyOfRange(pos, pos + typeLen); pos += typeLen
            need(idLen); val id = bytes.copyOfRange(pos, pos + idLen); pos += idLen
            need(payloadLen); val payload = bytes.copyOfRange(pos, pos + payloadLen); pos += payloadLen
            out += NdefRecordData(tnf, type, id, payload)
            if (messageEnd) break
        }
        return out
    }

    fun encode(records: List<NdefRecordData>): ByteArray {
        if (records.isEmpty()) return byteArrayOf(0xD0.toByte(), 0, 0)
        val out = ByteArrayOutputStream()
        for ((i, r) in records.withIndex()) {
            require(r.type.size <= 255 && r.id.size <= 255) { "Record type or id too long" }
            var header = r.tnf and 0x07
            if (i == 0) header = header or 0x80
            if (i == records.lastIndex) header = header or 0x40
            val short = r.payload.size < 256
            if (short) header = header or 0x10
            if (r.id.isNotEmpty()) header = header or 0x08
            out.write(header)
            out.write(r.type.size)
            if (short) out.write(r.payload.size) else {
                out.write(r.payload.size ushr 24); out.write(r.payload.size ushr 16)
                out.write(r.payload.size ushr 8); out.write(r.payload.size)
            }
            if (r.id.isNotEmpty()) out.write(r.id.size)
            out.write(r.type); out.write(r.id); out.write(r.payload)
        }
        return out.toByteArray()
    }
}
