package com.mypro.rfidguard.security

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import com.mypro.rfidguard.core.toHex
import com.mypro.rfidguard.core.vault.AeadBox
import com.mypro.rfidguard.core.vault.VaultData
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted storage for everything the app keeps.
 *
 * Layout: a random 256-bit data key encrypts the vault file (AES-256-GCM). The data key itself
 * is stored wrapped by a non-exportable AES key inside the Android Keystore (StrongBox when the
 * phone has one). When the phone has a screen lock, that Keystore key demands a fresh biometric
 * or screen-lock authentication for every single use, so the vault cannot be opened by code
 * alone - not even by this app - without the owner present. While unlocked the data key lives
 * only in memory and is zeroed on lock.
 *
 * The key file names the Keystore alias that wraps it, so replacing the wrapping key is a single
 * atomic file write: either the old file (and old alias) or the new one is in effect, never a mix.
 */
class KeystoreVault(context: Context) {
    private val app = context.applicationContext
    private val keyFile = File(app.filesDir, "vault.key")
    private val dataFile = File(app.filesDir, "vault.bin")
    private var dataKey: ByteArray? = null
    private var header: Header? = null

    private class Header(val alias: String, val authBound: Boolean, val strongBox: Boolean, val iv: ByteArray, val wrapped: ByteArray)

    /** A Keystore cipher waiting for the user's authentication. */
    class Pending internal constructor(
        val cipher: Cipher,
        internal val alias: String,
        val authBound: Boolean,
        internal val strongBox: Boolean,
        internal val creating: Boolean,
    )

    val exists: Boolean get() = keyFile.exists()
    val authBound: Boolean get() = readHeader()?.authBound == true
    val strongBox: Boolean get() = readHeader()?.strongBox == true
    val unlocked: Boolean get() = dataKey != null

    fun deviceSecure(): Boolean = app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun keystore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun readHeader(): Header? {
        header?.let { return it }
        if (!keyFile.exists()) return null
        return try {
            val b = keyFile.readBytes()
            if (b.size < KEY_MAGIC.size + 2 || !b.copyOfRange(0, KEY_MAGIC.size).contentEquals(KEY_MAGIC)) return null
            var pos = KEY_MAGIC.size
            val flags = b[pos++].toInt()
            val aliasLen = b[pos++].toInt() and 0xFF
            if (b.size < pos + aliasLen + IV_SIZE + 16) return null
            val alias = String(b, pos, aliasLen, Charsets.US_ASCII); pos += aliasLen
            val iv = b.copyOfRange(pos, pos + IV_SIZE); pos += IV_SIZE
            Header(alias, flags and 1 != 0, flags and 2 != 0, iv, b.copyOfRange(pos, b.size)).also { header = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Creates a Keystore key and reports whether it ended up in StrongBox. */
    private fun generateKey(alias: String, requireAuth: Boolean): Boolean {
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (requireAuth) {
                b.setUserAuthenticationRequired(true)
                // 0 = every use needs its own authentication, bound to the cipher operation.
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                // The screen lock is always accepted, so a newly enrolled fingerprint gains nothing
                // its owner could not already do; keeping the key avoids losing the vault.
                b.setInvalidatedByBiometricEnrollment(false)
            }
            if (strongBox) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        try {
            generator.init(spec(true))
            generator.generateKey()
            return true
        } catch (e: StrongBoxUnavailableException) {
            // fall through to the TEE-backed keystore
        } catch (e: GeneralSecurityException) {
            // some devices report StrongBox problems with a generic provider error
        } catch (e: RuntimeException) {
            // ditto
        }
        runCatching { keystore().deleteEntry(alias) }
        generator.init(spec(false))
        generator.generateKey()
        return false
    }

    private fun beginWrap(creating: Boolean): Pending {
        val alias = ALIAS_PREFIX + System.currentTimeMillis()
        val auth = deviceSecure()
        val strong = generateKey(alias, auth)
        val key = keystore().getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Pending(cipher, alias, auth, strong, creating)
    }

    /** First run: prepares a new Keystore key. Complete with [finishCreate] after authentication. */
    fun beginCreate(): Pending {
        // Keys left behind by an abandoned first run are of no use to anyone.
        if (!exists) deleteAliasesExcept(null)
        return beginWrap(true)
    }

    /** Re-protects an already unlocked vault with a new Keystore key (after a screen lock was added). */
    fun beginRewrap(): Pending {
        check(unlocked) { "Vault is locked" }
        return beginWrap(false)
    }

    /** Throws KeyPermanentlyInvalidatedException when the screen lock was removed since the vault was made. */
    fun beginUnlock(): Pending {
        val h = readHeader() ?: throw GeneralSecurityException("Vault key file is damaged")
        val key = keystore().getKey(h.alias, null) as? SecretKey ?: throw GeneralSecurityException("Vault key is missing from the Keystore")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, h.iv))
        return Pending(cipher, h.alias, h.authBound, h.strongBox, false)
    }

    /** Drops a prepared key when the user cancelled authentication. */
    fun discard(p: Pending) {
        if (p.alias != readHeader()?.alias) runCatching { keystore().deleteEntry(p.alias) }
    }

    private fun deleteAliasesExcept(keep: String?) {
        runCatching {
            val ks = keystore()
            for (alias in ks.aliases().toList()) if (alias.startsWith(ALIAS_PREFIX) && alias != keep) ks.deleteEntry(alias)
        }
    }

    private fun storeWrapped(p: Pending, key: ByteArray) {
        val wrapped = p.cipher.doFinal(key)
        val alias = p.alias.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write(KEY_MAGIC)
        out.write((if (p.authBound) 1 else 0) or (if (p.strongBox) 2 else 0))
        out.write(alias.size)
        out.write(alias)
        out.write(p.cipher.iv)
        out.write(wrapped)
        writeAtomically(keyFile, out.toByteArray())
        header = null
        // Only once the new file is durably in place and reads back naming the new key is the old key dropped.
        if (readHeader()?.alias == p.alias) deleteAliasesExcept(p.alias)
    }

    fun finishCreate(p: Pending): VaultData {
        require(p.creating)
        val random = SecureRandom()
        val key = ByteArray(32).also(random::nextBytes)
        val signing = ByteArray(32).also(random::nextBytes)
        val data = VaultData(signingKeyHex = signing.toHex())
        signing.fill(0)
        // Write the data file before the wrapped key becomes the active one.
        dataKey = key
        try {
            save(data)
            storeWrapped(p, key)
        } catch (e: Exception) {
            lock()
            throw e
        }
        return data
    }

    fun finishRewrap(p: Pending) {
        require(!p.creating)
        storeWrapped(p, dataKey ?: throw IllegalStateException("Vault is locked"))
    }

    fun finishUnlock(p: Pending): VaultData {
        val h = readHeader() ?: throw GeneralSecurityException("Vault key file is damaged")
        val key = p.cipher.doFinal(h.wrapped)
        try {
            val blob = dataFile.readBytes()
            if (blob.size < MAGIC.size || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw GeneralSecurityException("Vault file is damaged")
            val plain = AeadBox.open(key, blob.copyOfRange(MAGIC.size, blob.size), MAGIC)
            val data = try {
                VaultData.decode(plain)
            } catch (e: RuntimeException) {
                throw GeneralSecurityException("Vault content could not be read")
            } finally {
                plain.fill(0)
            }
            dataKey = key
            return data
        } catch (e: Exception) {
            key.fill(0)
            throw e
        }
    }

    @Synchronized
    fun save(data: VaultData) {
        val key = dataKey ?: throw IllegalStateException("Vault is locked")
        val plain = VaultData.encode(data)
        try {
            writeAtomically(dataFile, MAGIC + AeadBox.seal(key, plain, MAGIC))
        } finally {
            plain.fill(0)
        }
    }

    @Synchronized
    fun lock() {
        dataKey?.fill(0)
        dataKey = null
    }

    /** Destroys the vault and its keys. Nothing can be recovered afterwards. */
    fun wipe() {
        lock()
        header = null
        deleteAliasesExcept(null)
        for (f in listOf(dataFile, keyFile)) {
            // Overwrite before deleting. Flash storage may keep old blocks, but the wrapping key is gone anyway.
            runCatching { if (f.exists()) f.writeBytes(ByteArray(f.length().toInt().coerceIn(0, 1 shl 20))) }
            f.delete()
            File(f.path + ".new").delete()
            File(f.path + ".bak").delete()
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ALIAS_PREFIX = "rfidguard_master_"
        const val IV_SIZE = 12
        val MAGIC = "RGV1".toByteArray(Charsets.US_ASCII)
        val KEY_MAGIC = "RGK1".toByteArray(Charsets.US_ASCII)
    }
}
