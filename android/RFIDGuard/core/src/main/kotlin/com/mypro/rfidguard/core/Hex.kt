package com.mypro.rfidguard.core

/** Hex helpers. Decoding ignores spaces, colons and dashes so pasted dumps work. */
object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    fun encode(bytes: ByteArray, separator: String = ""): String {
        val sb = StringBuilder(bytes.size * (2 + separator.length))
        for ((i, b) in bytes.withIndex()) {
            if (i > 0) sb.append(separator)
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray =
        decodeOrNull(text) ?: throw IllegalArgumentException("Not valid hexadecimal")

    fun decodeOrNull(text: String): ByteArray? {
        val clean = text.filterNot { it == ' ' || it == ':' || it == '-' || it == '\n' || it == '\t' }
        if (clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

fun ByteArray.toHex(separator: String = ""): String = Hex.encode(this, separator)
internal fun Byte.u(): Int = toInt() and 0xFF
