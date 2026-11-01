package com.mypro.iptvbox.core.sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-128-GCM wrapper so messages crossing a public MQTT broker are opaque to it. */
class SyncCrypto(keyB64Url: String) {
    private val key = SecretKeySpec(WatchLink.unb64url(keyB64Url), "AES")
    private val rnd = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    fun decrypt(payload: String): String? = try {
        val all = Base64.getDecoder().decode(payload.trim())
        if (all.size < 13) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }
}
