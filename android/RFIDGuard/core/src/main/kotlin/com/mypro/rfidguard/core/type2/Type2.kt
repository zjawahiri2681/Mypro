package com.mypro.rfidguard.core.type2

/** NFC Forum Type 2 tags: NXP NTAG21x and MIFARE Ultralight families. */
data class Type2Spec(
    val name: String,
    val totalPages: Int,
    /** Bytes of user memory starting at page 4. */
    val userBytes: Int,
    /** First of the four configuration pages (CFG0, CFG1, PWD, PACK); null if the chip has no password feature. */
    val configPage: Int?,
    val hasOriginalitySignature: Boolean,
)

class Type2Config(val auth0: Int, val access: Int) {
    val readProtected: Boolean get() = access and 0x80 != 0
    val configLocked: Boolean get() = access and 0x40 != 0
    val authLimit: Int get() = access and 0x07
    fun protectionActive(totalPages: Int): Boolean = auth0 < totalPages
}

object Type2 {
    const val CMD_GET_VERSION = 0x60
    const val CMD_READ = 0x30
    const val CMD_FAST_READ = 0x3A
    const val CMD_WRITE = 0xA2
    const val CMD_PWD_AUTH = 0x1B
    const val CMD_READ_SIG = 0x3C
    const val FIRST_USER_PAGE = 4

    /** Interprets the 8-byte GET_VERSION answer. */
    fun specFromVersion(v: ByteArray?): Type2Spec? {
        if (v == null || v.size != 8 || v[0].toInt() != 0x00 || v[1].toInt() != 0x04 || v[7].toInt() != 0x03) return null
        val product = v[2].toInt() and 0xFF
        val storage = v[6].toInt() and 0xFF
        // Sub-type and version separate these chips from look-alikes (NTAG I2C, NTAG210u, Ultralight Nano)
        // that report the same storage size but have a different memory map.
        if (v[3].toInt() !in setOf(0x01, 0x02, 0x04) || v[4].toInt() != 0x01) return null
        return when (product) {
            0x04 -> when (storage) {
                0x0B -> Type2Spec("NTAG210", 20, 48, 16, true)
                0x0E -> Type2Spec("NTAG212", 41, 128, 37, true)
                0x0F -> Type2Spec("NTAG213", 45, 144, 41, true)
                0x11 -> Type2Spec("NTAG215", 135, 504, 131, true)
                0x13 -> Type2Spec("NTAG216", 231, 888, 227, true)
                else -> null
            }
            0x03 -> when (storage) {
                0x0B -> Type2Spec("MIFARE Ultralight EV1 (48 bytes)", 20, 48, 16, true)
                0x0E -> Type2Spec("MIFARE Ultralight EV1 (128 bytes)", 41, 128, 37, true)
                else -> null
            }
            else -> null
        }
    }

    /** Total pages guessed from the capability container (page 3) when the chip has no GET_VERSION. */
    fun pagesFromCapabilityContainer(cc: ByteArray): Int? {
        if (cc.size < 4 || cc[0].toInt() and 0xFF != 0xE1) return null
        val dataBytes = (cc[2].toInt() and 0xFF) * 8
        if (dataBytes == 0) return null
        return FIRST_USER_PAGE + dataBytes / 4
    }

    /** True when the capability container says the tag is NDEF read-only. */
    fun ccReadOnly(cc: ByteArray): Boolean = cc.size >= 4 && cc[3].toInt() and 0x0F != 0

    /** All static lock bits set (pages 3..15 locked). */
    fun staticLocked(page2: ByteArray): Boolean =
        page2.size >= 4 && page2[2].toInt() and 0xF8 == 0xF8 && page2[3].toInt() and 0xFF == 0xFF

    fun config(cfg0: ByteArray, cfg1: ByteArray): Type2Config? {
        if (cfg0.size < 4 || cfg1.size < 4) return null
        return Type2Config(cfg0[3].toInt() and 0xFF, cfg1[0].toInt() and 0xFF)
    }

    /** Location of the NDEF message TLV inside the data area (the bytes from page 4 on). */
    class TlvLocation(val tlvOffset: Int, val valueOffset: Int, val length: Int)

    fun findNdefTlv(area: ByteArray): TlvLocation? {
        var pos = 0
        while (pos < area.size) {
            val t = area[pos].toInt() and 0xFF
            if (t == 0x00) { pos++; continue }
            if (t == 0xFE) return null
            if (pos + 1 >= area.size) return null
            var len = area[pos + 1].toInt() and 0xFF
            var valueOffset = pos + 2
            if (len == 0xFF) {
                if (pos + 3 >= area.size) return null
                len = ((area[pos + 2].toInt() and 0xFF) shl 8) or (area[pos + 3].toInt() and 0xFF)
                valueOffset = pos + 4
            }
            if (t == 0x03) return if (valueOffset + len <= area.size) TlvLocation(pos, valueOffset, len) else null
            pos = valueOffset + len
        }
        return null
    }

    fun extractNdef(area: ByteArray): ByteArray? =
        findNdefTlv(area)?.let { area.copyOfRange(it.valueOffset, it.valueOffset + it.length) }

    /**
     * Bytes to write from page 4 so that the data area carries [message]. TLVs that precede the
     * existing NDEF TLV (lock/memory control) are kept. The result is padded to whole pages.
     * Returns null when the message does not fit in [capacity] bytes.
     */
    fun buildNdefArea(existingArea: ByteArray, message: ByteArray, capacity: Int): ByteArray? {
        val keep = findNdefTlv(existingArea)?.tlvOffset ?: 0
        val header = if (message.size < 0xFF) byteArrayOf(0x03, message.size.toByte())
        else byteArrayOf(0x03, 0xFF.toByte(), (message.size ushr 8).toByte(), message.size.toByte())
        var out = existingArea.copyOfRange(0, keep) + header + message
        if (out.size > capacity) return null
        if (out.size < capacity) out += 0xFE.toByte()
        val padded = (out.size + 3) / 4 * 4
        return out.copyOf(padded)
    }
}
