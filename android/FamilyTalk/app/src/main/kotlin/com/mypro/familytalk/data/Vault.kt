package com.mypro.familytalk.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted files in app-private storage. The AES-256-GCM key lives in the Android
 * Keystore (hardware-backed on most phones) and can never be exported, so a copied
 * data folder is useless without this exact phone.
 */
class Vault(context: Context) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    fun write(name: String, plain: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(name.toByteArray())
        val out = byteArrayOf(1) + c.iv + c.doFinal(plain)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(File(dir, name))) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) }
    }

    @Synchronized
    fun read(name: String): ByteArray? {
        val f = File(dir, name)
        if (!f.exists()) return null
        val b = f.readBytes()
        require(b.isNotEmpty() && b[0] == 1.toByte()) { "unknown vault format" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 1, 12))
        c.updateAAD(name.toByteArray())
        return c.doFinal(b, 13, b.size - 13)
    }

    @Synchronized
    fun delete(name: String) { File(dir, name).delete() }

    /** Destroys everything: keys, family, chats. Used by "Leave family / wipe". */
    fun wipe() {
        dir.deleteRecursively()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object { const val ALIAS = "familytalk-vault-v1" }
}
