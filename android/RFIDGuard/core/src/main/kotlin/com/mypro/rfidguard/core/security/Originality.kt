package com.mypro.rfidguard.core.security

import java.math.BigInteger

/**
 * NXP originality check for NTAG21x and MIFARE Ultralight EV1. Each genuine chip carries an
 * ECDSA signature (curve secp128r1, no hash) over its UID, readable with READ_SIG. The public
 * keys are published by NXP (application note AN11350). A valid signature means the UID and
 * signature pair came from NXP; a chip that copies both from a genuine tag would still pass.
 */
object Originality {
    internal val P = BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFF", 16)
    internal val A = BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFC", 16)
    internal val B = BigInteger("E87579C11079F43DD824993C2CEE5ED3", 16)
    internal val N = BigInteger("FFFFFFFE0000000075A30D1B9038A115", 16)
    internal val G = Point(BigInteger("161FF7528B899B2D0C28607CA52C5B86", 16), BigInteger("CF5AC8395BAFEB13C02DA292DDED7A83", 16))

    internal val NXP_PUBLIC_KEYS: Map<String, Point> = mapOf(
        "NTAG21x" to Point(BigInteger("494E1A386D3D3CFE3DC10E5DE68A499B", 16), BigInteger("1C202DB5B132393E89ED19FE5BE8BC61", 16)),
        "MIFARE Ultralight EV1" to Point(BigInteger("90933BDCD6E99B4E255E3DA55389A827", 16), BigInteger("564E11718E017292FAF23226A96614B8", 16)),
    )

    /** Affine point; null stands for the point at infinity. */
    internal data class Point(val x: BigInteger, val y: BigInteger)

    internal fun onCurve(p: Point): Boolean =
        p.y.multiply(p.y).mod(P) == p.x.pow(3).add(A.multiply(p.x)).add(B).mod(P)

    internal fun add(p: Point?, q: Point?): Point? {
        if (p == null) return q
        if (q == null) return p
        val lambda: BigInteger
        if (p.x == q.x) {
            if (p.y.add(q.y).mod(P).signum() == 0) return null
            lambda = p.x.multiply(p.x).multiply(BigInteger.valueOf(3)).add(A).multiply(p.y.shiftLeft(1).modInverse(P)).mod(P)
        } else {
            lambda = q.y.subtract(p.y).multiply(q.x.subtract(p.x).mod(P).modInverse(P)).mod(P)
        }
        val x = lambda.multiply(lambda).subtract(p.x).subtract(q.x).mod(P)
        val y = lambda.multiply(p.x.subtract(x)).subtract(p.y).mod(P)
        return Point(x, y)
    }

    internal fun multiply(k: BigInteger, p: Point?): Point? {
        var result: Point? = null
        var addend = p
        var n = k
        while (n.signum() > 0) {
            if (n.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            n = n.shiftRight(1)
        }
        return result
    }

    internal fun verify(publicKey: Point, message: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 32 || message.isEmpty() || message.size > 16) return false
        val r = BigInteger(1, signature.copyOfRange(0, 16))
        val s = BigInteger(1, signature.copyOfRange(16, 32))
        if (r.signum() <= 0 || s.signum() <= 0 || r >= N || s >= N) return false
        val e = BigInteger(1, message)
        val w = s.modInverse(N)
        val point = add(multiply(e.multiply(w).mod(N), G), multiply(r.multiply(w).mod(N), publicKey)) ?: return false
        return point.x.mod(N) == r
    }

    /** Name of the NXP key that validates [signature] for [uid], or null when none does. */
    fun verifiedFamily(uid: ByteArray, signature: ByteArray): String? =
        NXP_PUBLIC_KEYS.entries.firstOrNull { verify(it.value, uid, signature) }?.key
}
