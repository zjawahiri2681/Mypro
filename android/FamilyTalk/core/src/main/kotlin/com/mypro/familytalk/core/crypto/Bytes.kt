package com.mypro.familytalk.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Bytes {
    private val rng = SecureRandom()
    private val b64e = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun b64(b: ByteArray): String = b64e.encodeToString(b)
    fun unb64(s: String): ByteArray = b64d.decode(s)
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }
    fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").run { parts.forEach { update(it) }; digest() }

    /** Constant-time comparison for MACs, proofs and key fingerprints. */
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** Length-prefixed concatenation, so that ("ab","c") and ("a","bc") never collide. */
    fun lp(vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) {
            out.write(p.size ushr 24); out.write(p.size ushr 16); out.write(p.size ushr 8); out.write(p.size)
            out.write(p)
        }
        return out.toByteArray()
    }

    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
}
