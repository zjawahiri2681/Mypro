package com.mypro.familytalk.core.crypto

/**
 * Human-comparable fingerprint for a pair of family members (same idea as Signal's
 * safety numbers). Both phones show the same 60 digits; read them aloud or scan the
 * QR to prove no one swapped keys in the middle.
 */
object SafetyNumber {
    private const val ITERATIONS = 5200

    fun forPair(a: PublicIdentity, b: PublicIdentity): String {
        val fa = fingerprint(a)
        val fb = fingerprint(b)
        val (first, second) = if (a.memberId <= b.memberId) fa to fb else fb to fa
        return first + second
    }

    /** Groups of five digits for display: "12345 67890 ...". */
    fun format(number: String): String = number.chunked(5).joinToString(" ")

    private fun fingerprint(id: PublicIdentity): String {
        val key = id.signKeyBytes + id.dhKeyBytes
        var hash = Bytes.sha512(byteArrayOf(0, 1), key, Bytes.utf8(id.memberId))
        repeat(ITERATIONS) { hash = Bytes.sha512(hash, key) }
        val sb = StringBuilder()
        for (i in 0 until 6) {
            val o = i * 5
            var v = 0L
            for (j in 0 until 5) v = (v shl 8) or (hash[o + j].toLong() and 0xff)
            sb.append("%05d".format(v % 100000))
        }
        return sb.toString()
    }
}
